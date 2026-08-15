package dev.forcetower.lever

import android.content.Context
import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.logging.debug
import dev.forcetower.lever.logging.info
import dev.forcetower.lever.runtime.LeverEnvironment
import dev.forcetower.lever.runtime.LeverRuntime
import dev.forcetower.lever.storage.CacheStore
import dev.forcetower.lever.storage.CachedSnapshot
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A validated payload the client holds, with the network metadata that belongs
 * to *it* rather than to the client globally (spec 0002 §4).
 *
 * The split is load-bearing: a 304 confirms whichever representation's
 * validator was sent, so a single shared `etag`/`fetchedAt` pair would let
 * staged metadata corrupt activated state.
 */
internal data class Representation(
    val version: Int,
    val values: Map<String, WireValue>,
    val etag: String?,
    val fetchedAt: Long,
    val activatedAt: Long?,
)

internal class Newest(val representation: Representation, val isStaged: Boolean)

/**
 * The lever client: synchronous typed reads over the last activated snapshot,
 * with fetching, activation, and updates around them.
 *
 * Reads never suspend — that is the whole point. A composable or a view model
 * reading a flag is a lock-protected map lookup, not a coroutine hop
 * (research 0003 §4.2).
 *
 * The client retains only `context.applicationContext`, never an Activity.
 */
public class LeverClient internal constructor(
    configuration: LeverConfiguration,
    environment: LeverEnvironment,
    defaultCacheDirectory: () -> File,
) {
    /**
     * Builds a client and loads its cache synchronously, so the first read is
     * already correct. Only `context.applicationContext` is retained.
     *
     * @throws IllegalArgumentException if `configuration.baseUrl` is not an
     *   absolute http(s) origin.
     */
    public constructor(
        context: Context,
        configuration: LeverConfiguration,
    ) : this(
        configuration,
        LeverEnvironment.live(),
        { context.applicationContext.noBackupFilesDir },
    )

    internal val config: ValidatedConfiguration = validate(configuration, defaultCacheDirectory)

    private val cache = CacheStore(config.cacheDirectory, config.cacheKeyHash, config.logSink)
    private val lock = ReentrantLock()
    private val state = State()

    /**
     * One lock per memoizing key, so a slow serializer blocks only readers of
     * that key. Bounded by the number of distinct `(name, type)` pairs read,
     * exactly like the memo itself.
     */
    private val decodeGates = ConcurrentHashMap<MemoKey, ReentrantLock>()
    private val gate = CommitGate()
    private val calloutsDrained = lock.newCondition()
    private val now: () -> Long = environment.now
    private val runtime = LeverRuntime(config, environment)

    internal val clientId: String

    /** `Lever.shared` is process-lived, so the API refuses to close it. */
    internal var isSharedInstance: Boolean = false

    /**
     * Test seam: runs between a commit's state-lock phase and its gate phase,
     * which is where the close-linearization tests need a barrier.
     */
    internal var afterStateLock: ((Long) -> Unit)? = null

    /**
     * Test seam: runs after a read diagnostic has been admitted but before it
     * reaches the sink — the window `close()` has to drain.
     */
    internal var beforeReadLog: (() -> Unit)? = null

    init {
        // Both before anything asynchronous exists: the identity must be there
        // before the first fetch can send it, and the cache must be in memory
        // before the first read can happen. This ordering is the three-layer
        // floor made structural (spec 0002 §4).
        clientId = cache.loadOrCreateClientId()
        cache.loadSnapshot()?.let { cached ->
            state.activated =
                Representation(
                    version = cached.version,
                    values = cached.values,
                    etag = cached.etag,
                    fetchedAt = cached.fetchedAt,
                    activatedAt = cached.activatedAt,
                )
        }
        runtime.start(this, clientId)
    }

    private class State {
        var activated: Representation? = null
        var staged: Representation? = null

        /**
         * Bumped on every activated swap, so a decode that raced an activation
         * cannot install a stale memo entry.
         */
        var generation: Long = 0
        /**
         * Keyed by the key **instance**, so two keys that collide on
         * `(name, typeId)` keep their own entries instead of evicting each
         * other on every alternating read. Weak, because a caller who builds
         * keys ad hoc instead of declaring them once must not grow this map
         * without bound.
         */
        val memo = WeakHashMap<LeverKey<*>, MemoEntry>()
        val logged = mutableSetOf<LogKey>()

        /**
         * Allocated under the state lock on every commit, so persistence, logs,
         * and updates can be replayed in the order the commits happened
         * (spec 0002 §12.1).
         */
        var commitSequence: Long = 0
        val collectors = mutableMapOf<Long, SendChannel<LeverUpdate>>()
        var nextCollectorId: Long = 0

        /**
         * `CLOSING` already refuses new work; it becomes `CLOSED` only once the
         * teardown is a fact, so a concurrent closer can wait for the same
         * boundary instead of racing past it.
         */
        var closeState = CloseState.OPEN
        var closeBarrier: CountDownLatch? = null

        /**
         * Read diagnostics that have left the lock but not yet reached the
         * sink. `close()` drains them, so a callout can never *begin* before
         * the mark and still be running after `close()` returns.
         */
        var readCallouts = 0

        val isClosed: Boolean get() = closeState != CloseState.OPEN
    }

    private enum class CloseState { OPEN, CLOSING, CLOSED }

    private data class MemoKey(val name: String, val typeId: String)

    /**
     * A decoded `json` value. It is reached only through its own key instance,
     * which is what keeps one key's object from ever being served to another:
     * a serializer's descriptor name is not a type identity, and under erasure
     * the mix-up would surface as `ClassCastException` in the caller — from a
     * read that promises never to throw.
     */
    private class MemoEntry(val value: Any?)

    /**
     * `typeId` is `null` for absence, which dedupes per `(key, version)`; a
     * mismatch dedupes per `(key, version, type)` because two keys may share a
     * wire name with different Kotlin types (spec 0002 §2.3).
     */
    private data class LogKey(val name: String, val version: Int?, val typeId: String?)

    // MARK: reads

    /** `lever[Flags.enableEnrollment]` — the read surface, in operator form. */
    public operator fun <V> get(key: LeverKey<V>): V = value(key)

    /**
     * Reads [key] from the activated snapshot.
     *
     * Never suspends, never throws, never returns null: an absent key, a wire
     * type that does not fit, or a `json` payload that fails to decode all
     * resolve to [LeverKey.defaultValue] and log (spec 0003 §2.3). Values are
     * stable until the next [activate].
     */
    public fun <V> value(key: LeverKey<V>): V {
        // The fast path a hot read site takes: one lock-protected map lookup.
        if (!key.memoizes) return resolve(key, memoize = false)

        memoEntry(key)?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as V
        }

        // Exactly one reader runs a consumer's serializer for this key; the rest
        // wait for its result rather than decoding again. Neither the state lock
        // nor the commit gate is held here, so the decoder is free to be slow —
        // and free to read other flags (spec 0002 §4.1).
        // The gate stays coarse — one lock per `(name, type)` rather than per
        // key — because colliding keys only serialize each other here, and a
        // per-instance gate map would grow with every ad-hoc key.
        return decodeGates.computeIfAbsent(MemoKey(key.name, key.typeId)) { ReentrantLock() }
            .withLock {
                val entry = memoEntry(key)
                if (entry != null) {
                    @Suppress("UNCHECKED_CAST")
                    entry.value as V
                } else {
                    resolve(key, memoize = true)
                }
            }
    }

    /** The memoized value for this exact key, or `null` when there is none. */
    private fun <V> memoEntry(key: LeverKey<V>): MemoEntry? = lock.withLock { state.memo[key] }

    private fun <V> resolve(key: LeverKey<V>, memoize: Boolean): V {
        var raw: WireValue? = null
        var version: Int? = null
        var generation = 0L
        lock.withLock {
            val activated = state.activated
            raw = activated?.values?.get(key.name)
            version = activated?.version
            generation = state.generation
        }

        // Everything below runs outside the lock: a `json` decoder is arbitrary
        // consumer code and the sink is the host app's (spec 0002 §4.1).
        val present = raw
        val values = if (present == null) emptyMap() else mapOf(key.name to present)
        return when (val outcome = resolveRead(key, values)) {
            is ReadOutcome.Resolved -> {
                if (memoize) {
                    lock.withLock {
                        // A decode that raced an activation must not install a
                        // stale memo entry into the newer representation.
                        if (state.generation == generation) {
                            state.memo[key] = MemoEntry(outcome.value)
                        }
                    }
                }
                outcome.value
            }

            // Absence is the normal state mid-rollout, and a hot read site must
            // not flood even the debug channel.
            ReadOutcome.Absent -> {
                logOnce(LogKey(key.name, version, null), LeverLogLevel.DEBUG) {
                    "key absent key=${key.name}"
                }
                key.defaultValue
            }

            ReadOutcome.Mismatch -> {
                logOnce(LogKey(key.name, version, key.typeId), LeverLogLevel.WARN) {
                    "type mismatch key=${key.name} wire=${present?.type ?: "none"} as=${key.typeId}"
                }
                key.defaultValue
            }
        }
    }

    /**
     * `null` until the first activation ever; `0` after activating a
     * never-published environment (spec 0003 §2).
     */
    public val activatedVersion: Int?
        get() = lock.withLock { state.activated?.version }

    /**
     * Emits on every value-changing activation, from subscription onward.
     *
     * Each collector gets its own unlimited channel, so a slow collector never
     * blocks the synchronous `activate()` that feeds it and never loses an
     * update; a collector that arrives after [close] gets an already-completed
     * flow (spec 0003 §4).
     */
    public val updates: Flow<LeverUpdate>
        get() = flow {
            val channel = Channel<LeverUpdate>(Channel.UNLIMITED)
            val id =
                lock.withLock {
                    if (state.isClosed) return@withLock null
                    val id = ++state.nextCollectorId
                    state.collectors[id] = channel
                    id
                } ?: return@flow

            try {
                for (update in channel) emit(update)
            } finally {
                lock.withLock { state.collectors.remove(id) }
            }
        }

    // MARK: control

    /**
     * Fetches and stages; reads are unchanged until [activate].
     *
     * Explicit calls always hit the network: the interval throttles the SDK,
     * not the developer (spec 0002 §5.1). Cancelling one waiter of a coalesced
     * fetch rethrows `CancellationException` to that waiter — never wrapped —
     * and never cancels the shared request while other interest remains.
     *
     * @throws LeverException on any transport or protocol failure.
     * @throws IllegalStateException if the client is closed.
     */
    public suspend fun fetch() {
        checkOpen()
        runtime.fetch(LeverRuntime.Reason.EXPLICIT)
    }

    /**
     * Commits the staged representation, if any.
     *
     * Returns `true` only when the **serving values** changed. The service
     * bumps version and ETag on every publish even when this client's resolved
     * values are identical, so representation commit and observable value
     * change are deliberately separate (spec 0002 §4).
     *
     * This is a durability boundary: it does not return until its snapshot
     * write has completed or failed-and-logged, so a caller that observed a
     * completed activation finds it again after process death.
     */
    public fun activate(): Boolean {
        val activatedAt = now()

        val commit =
            lock.withLock {
                if (state.isClosed) return false
                val staged = state.staged ?: return false

                val before = state.activated?.values ?: emptyMap()
                val changed = !valuesEqual(staged.values, before)

                val committed = staged.copy(activatedAt = activatedAt)
                state.activated = committed
                state.staged = null
                state.generation++
                // A version bump re-opens the per-version dedupe either way.
                state.logged.clear()
                if (changed) state.memo.clear()

                val ticket = ++state.commitSequence
                Commit(
                    ticket = ticket,
                    snapshot =
                        CachedSnapshot(
                            version = committed.version,
                            etag = committed.etag,
                            values = committed.values,
                            fetchedAt = committed.fetchedAt,
                            activatedAt = activatedAt,
                        ),
                    update =
                        if (changed) {
                            LeverUpdate(committed.version, changedKeys(before, committed.values))
                        } else {
                            null
                        },
                    collectors = if (changed) state.collectors.values.toList() else emptyList(),
                )
            }

        afterStateLock?.invoke(commit.ticket)
        runCommit(commit)
        return commit.update != null
    }

    /** @throws IllegalStateException if the client is closed. */
    public suspend fun fetchAndActivate(): Boolean {
        fetch()
        return activate()
    }

    /**
     * Teardown, and the full contract is spec 0003 §4: activations already
     * admitted to the commit gate finish their durability and callouts before
     * this returns; one arriving afterwards changes nothing; reads keep serving
     * forever; collectors drain what they were owed and complete; the runtime
     * thread, the timer, the stream, and the HTTP client's pools are released.
     *
     * Idempotent. Closing the installed [Lever.shared] is a programmer error.
     */
    public fun close() {
        check(!isSharedInstance) {
            "Lever.shared cannot be closed — the singleton is process-lived. " +
                "Construct a LeverClient directly if you need one you can close."
        }

        // Decide once, under the lock; wait outside it, or the joiner would
        // hold the very lock the closer needs to finish.
        var joining: CountDownLatch? = null
        val admitted =
            lock.withLock {
                if (state.isClosed) {
                    joining = state.closeBarrier
                    return@withLock -1L
                }
                state.closeState = CloseState.CLOSING
                state.closeBarrier = CountDownLatch(1)
                // Everything allocated before the mark is already admitted; no
                // ticket can be allocated after it.
                state.commitSequence
            }

        // A concurrent caller joins the same boundary. Returning early would let
        // it observe "closed" while the first caller's admitted commit was still
        // logging and delivering — the one thing close() promises cannot happen
        // after it returns.
        joining?.let {
            it.await()
            return
        }
        if (admitted < 0) return

        try {
            gate.awaitDrain(admitted)
            // Read diagnostics live outside the commit gate — they are reads,
            // not commits — so they are drained on their own.
            lock.withLock { while (state.readCallouts > 0) calloutsDrained.awaitUninterruptibly() }

            val collectors =
                lock.withLock {
                    val open = state.collectors.values.toList()
                    state.collectors.clear()
                    open
                }
            // Whatever was enqueued before the boundary still drains to a live
            // collector; nothing new is ever enqueued after it.
            collectors.forEach { it.close() }

            runtime.close()
        } finally {
            val barrier = lock.withLock {
                state.closeState = CloseState.CLOSED
                state.closeBarrier
            }
            barrier?.countDown()
        }
    }

    private class Commit(
        val ticket: Long,
        val snapshot: CachedSnapshot,
        val update: LeverUpdate?,
        val collectors: List<SendChannel<LeverUpdate>>,
    )

    /**
     * Persist, log, deliver — in ticket order, never under the state lock. The
     * gate admits strictly in commit order regardless of arrival order, so disk
     * order, log order, and update order all equal activation order
     * (spec 0003 §4).
     */
    private fun runCommit(commit: Commit) {
        gate.admit(commit.ticket) {
            // A metadata-only commit still persists, so `activatedVersion` and
            // the cached snapshot track the server across value-identical
            // publishes (spec 0002 §4).
            cache.save(commit.snapshot)

            val update = commit.update
            if (update == null) {
                config.logSink.debug("committed version=${commit.snapshot.version} changed=0")
                return@admit
            }
            config.logSink.info(
                "activated version=${update.version} changed=${update.changedKeys.size}"
            )
            // Unlimited capacity, so this never blocks; the one way it fails is
            // a channel `close()` raced, and that collector is gone anyway.
            commit.collectors.forEach { it.trySend(update) }
        }
    }

    // MARK: runtime seam

    /**
     * The version of the newest validated representation this process holds.
     * Derived, never stored — this, never a nudge frame, is what nudge dedupe
     * compares against (spec 0002 §4).
     */
    internal fun lastKnownVersion(): Int? =
        lock.withLock { state.staged?.version ?: state.activated?.version }

    /**
     * The validator and the min-interval clock both read the newest
     * representation: staged when present, otherwise activated. Which one it
     * was decides who a later 304 confirms (spec 0002 §4).
     */
    internal fun newestRepresentation(): Newest? =
        lock.withLock {
            state.staged?.let { return@withLock Newest(it, isStaged = true) }
            state.activated?.let { Newest(it, isStaged = false) }
        }

    internal fun stage(representation: Representation) {
        lock.withLock { if (!state.isClosed) state.staged = representation }
    }

    /**
     * A 304 refreshes the freshness of whichever representation's validator was
     * sent (spec 0002 §6.1). The activated case is persisted — otherwise the
     * refreshed clock is lost on relaunch and the next launch refetches inside
     * the interval — and takes a ticket through the same gate, so a delayed
     * freshness write can never land on top of a newer activation.
     */
    internal fun confirmFreshness(ofStaged: Boolean, fetchedAt: Long) {
        val commit =
            lock.withLock {
                if (state.isClosed) return
                if (ofStaged) {
                    // Staged metadata must never be combined with activated values.
                    state.staged = state.staged?.copy(fetchedAt = fetchedAt) ?: return
                    return
                }
                val activated = state.activated ?: return
                val refreshed = activated.copy(fetchedAt = fetchedAt)
                state.activated = refreshed
                Commit(
                    ticket = ++state.commitSequence,
                    snapshot =
                        CachedSnapshot(
                            version = refreshed.version,
                            etag = refreshed.etag,
                            values = refreshed.values,
                            fetchedAt = fetchedAt,
                            activatedAt = refreshed.activatedAt ?: fetchedAt,
                        ),
                    update = null,
                    collectors = emptyList(),
                )
            }

        afterStateLock?.invoke(commit.ticket)
        runCommit(commit)
    }

    internal fun cacheStore(): CacheStore = cache

    private fun checkOpen() {
        check(!lock.withLock { state.isClosed }) { LeverRuntime.CLOSED_MESSAGE }
    }

    /**
     * A read after `close()` still serves its value, but it must not start a
     * new sink callback: "no sink invocation begins after `close()` returns" is
     * the boundary, and a first post-close absent or mismatched read would
     * otherwise cross it (spec 0003 §4).
     *
     * Refusing new callouts is only half of it. A read that passed the check
     * microseconds before `close()` began would still reach the sink after it
     * returned, so the callout is registered under the lock and `close()` waits
     * for the ones already in flight.
     */
    private fun logOnce(key: LogKey, level: LeverLogLevel, message: () -> String) {
        val isFirst =
            lock.withLock {
                if (state.isClosed || !state.logged.add(key)) return@withLock false
                state.readCallouts++
                true
            }
        if (!isFirst) return

        try {
            beforeReadLog?.invoke()
            config.logSink.log(level, message())
        } finally {
            lock.withLock {
                state.readCallouts--
                if (state.readCallouts == 0) calloutsDrained.signalAll()
            }
        }
    }
}
