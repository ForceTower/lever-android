package dev.forcetower.lever.runtime

import dev.forcetower.lever.LeverClient
import dev.forcetower.lever.LeverException
import dev.forcetower.lever.Representation
import dev.forcetower.lever.ValidatedConfiguration
import dev.forcetower.lever.logging.debug
import dev.forcetower.lever.logging.warn
import dev.forcetower.lever.transport.HttpStream
import dev.forcetower.lever.transport.ResolveEndpoint
import dev.forcetower.lever.transport.ServerSentEventParser
import dev.forcetower.lever.transport.VersionFrame
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything asynchronous the SDK does: scheduling, lifecycle reaction, the SSE
 * connection, and fetch execution — every job, so cancellation has exactly one
 * home (spec 0003 §4).
 *
 * The ownership boundary is one-way: the runtime calls into the client's
 * thread-safe core to stage, activate, or confirm freshness; the core never
 * calls into the runtime. Everything below `start` runs on the runtime
 * dispatcher, so the mutable fields need no lock of their own.
 */
internal class LeverRuntime(
    private val configuration: ValidatedConfiguration,
    private val environment: LeverEnvironment,
) {
    enum class Reason { EXPLICIT, AUTOMATIC, NUDGE }

    private val runtimeThread = environment.runtimeThread()
    private val dispatcher = runtimeThread.dispatcher
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transport = environment.makeTransport(configuration)
    private val sink = configuration.logSink

    @Volatile private var client: LeverClient? = null
    @Volatile private var clientId: String = ""
    @Volatile private var closed = false

    private var inFlight: Deferred<Unit>? = null
    private var timer: Job? = null
    private var lifecycleJob: Job? = null
    private var streamJob: Job? = null
    /**
     * The latest differing announced version seen while a fetch was already in
     * flight. Last one wins — versions are identity tokens, never `max`ed
     * (spec 0002 §5.3).
     */
    private var pendingNudge: Int? = null
    private var lifecycleSource: LifecycleSource? = null
    private var foregrounded = false
    private var sawInitialPhase = false
    /** A 401 on the stream stops reconnecting until the next foreground. */
    private var streamStopped = false

    /**
     * Set synchronously, not on a hop: an explicit `fetch()` issued on the line
     * after construction must not race the runtime's first turn and silently
     * find no client.
     */
    fun start(client: LeverClient, clientId: String) {
        this.client = client
        this.clientId = clientId
        scope.launch { begin() }
    }

    private fun begin() {
        // Cache-only reader: reads and explicit fetches still work, but nothing
        // here starts — no fetch, timer, lifecycle observer, or stream
        // (spec 0002 §5).
        if (!configuration.automaticUpdates) return

        val source = environment.lifecycle()
        lifecycleSource = source
        lifecycleJob = scope.launch { source.phases().collect { handle(it) } }
    }

    fun close() {
        closed = true
        // Before cancelling, not as a consequence of it: cancellation only
        // queues the collector's teardown, and the observer must be gone when
        // close() returns (spec 0003 §4).
        lifecycleSource?.detach()
        lifecycleSource = null
        scope.cancel()
        // Cancelling the calls in flight first means the thread has nothing left
        // to wait on when it drains.
        transport.close()
        // Runs whatever cancellation left queued, then terminates the thread —
        // release, not just a cancelled job.
        runtimeThread.shutdown()
    }

    // MARK: lifecycle

    private suspend fun handle(phase: LifecyclePhase) {
        if (closed) return
        // The source reports the current phase at subscription, so the first
        // event *is* the init trigger — including for a client born
        // backgrounded. Running both would issue two requests at launch
        // whenever the first one failed (spec 0002 §12).
        val isFirstPhase = !sawInitialPhase
        sawInitialPhase = true

        when (phase) {
            LifecyclePhase.FOREGROUND -> {
                foregrounded = true
                streamStopped = false
                connectStream()
                scope.launch { runAutomaticFetch() }
            }

            LifecyclePhase.BACKGROUND -> {
                foregrounded = false
                timer?.cancel()
                timer = null
                disconnectStream()
                if (isFirstPhase) scope.launch { runAutomaticFetch() }
            }
        }
    }

    // MARK: fetching

    /**
     * Explicit calls always hit the network; automatic ones honor the interval;
     * nudges bypass it by design and reset the clock on success
     * (spec 0002 §5.1).
     */
    suspend fun fetch(reason: Reason) {
        val shared =
            withContext(dispatcher) {
                if (closed) return@withContext null
                sink.debug("fetch reason=${reason.name.lowercase()}")
                sharedFetch()
            } ?: throw IllegalStateException(CLOSED_MESSAGE)

        try {
            shared.await()
        } catch (cause: CancellationException) {
            // A waiter whose own coroutine is still alive only gets here because
            // teardown cancelled the shared work.
            if (closed && currentCoroutineContext().isActive) {
                throw IllegalStateException(CLOSED_MESSAGE)
            }
            throw cause
        }
    }

    /**
     * What coalesces is **transport work**: each caller applies its own policy
     * to the shared result. A waiter that walks away never takes the request
     * with it, because the shared job is a child of the runtime scope rather
     * than of any waiter (spec 0002 §5.1).
     */
    private fun sharedFetch(): Deferred<Unit> {
        inFlight?.let { return it }
        val task = scope.async { execute() }
        inFlight = task
        return task
    }

    private suspend fun execute() {
        try {
            val client = client ?: return
            val newest = client.newestRepresentation()
            val validator = newest?.representation?.etag
            val request = ResolveEndpoint.request(configuration, clientId, validator)

            val response =
                try {
                    transport.send(request)
                } catch (cause: IOException) {
                    throw LeverException.Network(cause)
                }

            val fetchedAt = environment.now()
            val outcome =
                try {
                    ResolveEndpoint.outcome(response, validator != null)
                } catch (cause: LeverException) {
                    // The envelope's `error.code` reaches the log and nothing
                    // else — the status carries the branch (spec 0001 §5.1).
                    ResolveEndpoint.errorCode(response)?.let {
                        sink.debug("resolve rejected status=${response.status} code=$it")
                    }
                    throw cause
                }
            when (outcome) {
                is ResolveEndpoint.Outcome.Fresh ->
                    client.stage(
                        Representation(
                            version = outcome.version,
                            values = outcome.values,
                            etag = outcome.etag,
                            fetchedAt = fetchedAt,
                            activatedAt = null,
                        )
                    )

                // The 304 confirms whichever representation's validator we sent.
                ResolveEndpoint.Outcome.NotModified ->
                    client.confirmFreshness(ofStaged = newest?.isStaged == true, fetchedAt = fetchedAt)
            }
        } finally {
            inFlight = null
            drainPendingNudge()
        }
    }

    /** Init, foreground, and the in-session timer all land here. */
    private suspend fun runAutomaticFetch() {
        if (closed || !configuration.automaticUpdates) return
        val attemptAt = environment.now()

        if (!isDue(attemptAt)) {
            armTimer(null)
            return
        }

        try {
            fetch(Reason.AUTOMATIC)
            client?.activate()
        } catch (cause: CancellationException) {
            // Never logged as a network failure, and never re-armed: teardown.
            throw cause
        } catch (cause: LeverException) {
            sink.warn("automatic fetch failed error=${cause.message}")
        } catch (cause: IllegalStateException) {
            sink.debug("automatic fetch skipped error=${cause.message}")
            return
        }
        // Always from the attempt, never from an already-expired deadline: a
        // failed fetch does not advance `fetchedAt`, and re-arming from it would
        // hot-loop (spec 0002 §5.1).
        armTimer(attemptAt)
    }

    private fun isDue(now: Long): Boolean {
        val fetchedAt = client?.newestRepresentation()?.representation?.fetchedAt ?: return true
        // The wall clock moved backwards; one fetch rewrites it to now, so this
        // cannot loop.
        if (fetchedAt > now) return true
        return elapsed(fetchedAt, now) >= configuration.minimumFetchInterval.inWholeSeconds
    }

    // MARK: the in-session timer

    /**
     * The degraded polling mode when the stream is down — there is no second,
     * faster poll loop (spec 0002 §5.1).
     */
    private fun armTimer(attemptAt: Long?) {
        timer?.cancel()
        timer = null
        if (closed || !foregrounded || !configuration.automaticUpdates) return

        val interval = configuration.minimumFetchInterval
        // `ZERO` means "always eligible", never "continuously": automatic
        // fetching then happens on lifecycle edges only.
        if (interval <= Duration.ZERO) return

        // The 60 s polling floor is a timer-only clamp; lifecycle-edge
        // eligibility keeps the configured interval (spec 0002 §5.1).
        val armed = maxOf(interval, POLLING_FLOOR)
        val anchor =
            attemptAt
                ?: client?.newestRepresentation()?.representation?.fetchedAt
                ?: environment.now()
        val deadline = saturatingAdd(anchor, armed.inWholeSeconds)
        val delaySeconds = max(0L, elapsed(environment.now(), deadline))

        timer =
            scope.launch {
                delay(delaySeconds.seconds)
                runAutomaticFetch()
            }
    }

    // MARK: nudges

    /**
     * Spec 0002 §5.3's dedupe: identity, not ordering. A *lower* announced
     * version still means "different", which is what keeps a restored-from-
     * backup server self-healing.
     */
    private fun handleNudge(version: Int) {
        val client = client ?: return
        if (closed || version == client.lastKnownVersion()) return
        sink.debug("nudge version=$version")

        // Coalescing into the in-flight request can lose an update: the server
        // may have chosen that response before this version was published. The
        // announced token decides whether a *follow-up request* is needed.
        if (inFlight != null) pendingNudge = version

        // The activation policy is this caller's own, so the nudge joins
        // whatever transport work answers it — synchronously, so it cannot slip
        // between that work finishing and this hop. Without the join, a nudge
        // that lands on a staging-only `fetch()` is answered by that fetch and
        // then activated by nobody (spec 0002 §12.1).
        activateWhenDone(sharedFetch())
    }

    private fun drainPendingNudge() {
        val pending = pendingNudge ?: return
        pendingNudge = null
        val client = client ?: return
        if (closed || pending == client.lastKnownVersion()) return
        activateWhenDone(sharedFetch())
    }

    /**
     * Awaits shared transport work and applies the nudge's activation policy.
     * Redundant calls are harmless: activating with nothing staged is a no-op.
     */
    private fun activateWhenDone(task: Deferred<Unit>) {
        scope.launch {
            try {
                task.await()
                if (configuration.autoActivateOnNudge) client?.activate()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: LeverException) {
                sink.warn("nudge fetch failed error=${cause.message}")
            }
        }
    }

    // MARK: sse

    private fun connectStream() {
        if (closed || !configuration.automaticUpdates || !foregrounded || streamStopped) return
        if (streamJob != null) return
        streamJob = scope.launch { runStream() }
    }

    private fun disconnectStream() {
        streamJob?.cancel()
        streamJob = null
    }

    private sealed interface Round {
        /** A frame arrived, so the backoff counter resets. */
        data object Active : Round

        data class Failed(val retryAfter: Double?) : Round

        /** 401: stop reconnecting until the next foreground. */
        data object Stop : Round
    }

    private suspend fun runStream() {
        var attempt = 0
        while (currentCoroutineContext().isActive && foregrounded && !streamStopped && !closed) {
            var retryAfter: Double? = null
            when (val round = connectOnce()) {
                Round.Stop -> return
                // A connection that opened and delivered a frame earns a fresh
                // backoff budget — but still a delay, or a server that closes
                // right after the connect frame becomes a reconnect hot loop
                // (spec 0002 §12).
                Round.Active -> attempt = 0
                is Round.Failed -> retryAfter = round.retryAfter
            }

            // Full jitter over an exponential ceiling (spec 0002 §6.2).
            val ceiling = min(60.0, 2.0.pow(attempt))
            val delaySeconds = max(retryAfter ?: 0.0, environment.jitter(ceiling))
            attempt++
            delay((delaySeconds * 1000).toLong())
        }
    }

    private suspend fun connectOnce(): Round {
        val stream =
            try {
                transport.openStream(ResolveEndpoint.streamRequest(configuration))
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: IOException) {
                return Round.Failed(null)
            }

        // The stream owns a live response body from the headers onward, so every
        // branch below releases it — a rejected round leaks a connection
        // otherwise (spec 0003 §6.1's dedicated client is not a substitute).
        try {
            return round(stream)
        } finally {
            stream.close()
        }
    }

    private suspend fun round(stream: HttpStream): Round {
        // "Open" means validated: anything else never reaches the parser, so a
        // proxy's 200 HTML error page fails fast instead of sitting in the byte
        // loop until the watchdog fires (spec 0002 §6.2).
        when (stream.status) {
            200 -> Unit
            401 -> {
                streamStopped = true
                sink.warn("stream stopped, invalid key — retrying at the next foreground")
                return Round.Stop
            }
            503 -> return Round.Failed(retryAfterSeconds(stream.headers["Retry-After"]))
            else -> return Round.Failed(null)
        }

        val mediaType = stream.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
        if (mediaType != "text/event-stream") {
            sink.warn("stream refused, media type=${mediaType ?: "none"}")
            return Round.Failed(null)
        }

        return if (pump(stream)) Round.Active else Round.Failed(null)
    }

    /** Returns whether a **frame** completed, which is what resets the backoff. */
    private suspend fun pump(stream: HttpStream): Boolean {
        val parser = ServerSentEventParser()

        coroutineScope {
            val chunks = Channel<ByteArray>(Channel.UNLIMITED)
            val reader =
                launch {
                    try {
                        stream.chunks.collect { chunks.send(it) }
                        chunks.close()
                    } catch (cause: CancellationException) {
                        chunks.close()
                        throw cause
                    } catch (cause: Exception) {
                        // EOF and read errors are both "reconnect through backoff".
                        chunks.close(cause)
                    }
                }

            try {
                while (true) {
                    // Any bytes reset the idle timer — heartbeats included. Server
                    // heartbeats land every 25 s, so 60 s of silence is two lost
                    // beats: decisive without flapping (spec 0002 §6.2).
                    val received =
                        withTimeoutOrNull(IDLE_TIMEOUT) { chunks.receiveCatching() }
                    if (received == null) {
                        sink.debug("stream idle for 60s — reconnecting")
                        break
                    }
                    val chunk = received.getOrNull() ?: break

                    val events =
                        try {
                            parser.consume(chunk)
                        } catch (_: ServerSentEventParser.FrameTooLargeException) {
                            sink.warn("stream frame exceeded the 1MiB bound — reconnecting")
                            break
                        }

                    for (event in events) {
                        if (event.name != "version") continue
                        VersionFrame.version(event.data)?.let { handleNudge(it) }
                    }
                }
            } finally {
                reader.cancel()
                chunks.cancel()
            }
        }

        return parser.completedFrames > 0
    }

    companion object {
        val POLLING_FLOOR = 60.seconds
        val IDLE_TIMEOUT = 60.seconds
        const val CLOSED_MESSAGE =
            "this LeverClient is closed. Reads keep serving the last activated values; " +
                "construct a new client to fetch again."

        /**
         * Elapsed seconds, saturating rather than overflowing. These timestamps
         * come from a cache file and an injectable clock, and neither is allowed
         * to turn into a wrong deadline (spec 0002 §12.1).
         */
        fun elapsed(from: Long, to: Long): Long {
            val difference = to - from
            // Overflow: the operands had different signs and the result took the
            // wrong one.
            if ((to xor from) and (to xor difference) < 0) {
                return if (from < 0) Long.MAX_VALUE else Long.MIN_VALUE
            }
            return difference
        }

        fun saturatingAdd(left: Long, right: Long): Long {
            val sum = left + right
            if ((left xor sum) and (right xor sum) < 0) {
                return if (left > 0) Long.MAX_VALUE else Long.MIN_VALUE
            }
            return sum
        }

        /**
         * Integer seconds only — the syntax the service emits — capped at 300 s.
         * An unparseable value is ignored (spec 0002 §6.2).
         */
        fun retryAfterSeconds(header: String?): Double? {
            val seconds = header?.trim()?.toIntOrNull() ?: return null
            if (seconds < 0) return null
            return min(seconds, 300).toDouble()
        }
    }
}
