package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.logging.LeverLogSink
import dev.forcetower.lever.runtime.LeverEnvironment
import dev.forcetower.lever.runtime.LifecyclePhase
import dev.forcetower.lever.runtime.LifecycleSource
import dev.forcetower.lever.runtime.RuntimeThread
import dev.forcetower.lever.storage.CacheStore
import dev.forcetower.lever.storage.CachedSnapshot
import dev.forcetower.lever.transport.HttpHeaders
import dev.forcetower.lever.transport.HttpRequest
import dev.forcetower.lever.transport.HttpResponse
import dev.forcetower.lever.transport.HttpStream
import dev.forcetower.lever.transport.LeverTransport
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonElement

// MARK: log sink

/**
 * Records everything the SDK logs, and can run an arbitrary block from inside
 * `log` — which is how the reentrancy tests prove no lock is held during a
 * callout (spec 0002 §4.1).
 */
internal class RecordingLogSink : LeverLogSink {
    data class Entry(val level: LeverLogLevel, val message: String)

    private val lock = ReentrantLock()
    private val entries = mutableListOf<Entry>()

    @Volatile var onLog: (() -> Unit)? = null

    override fun log(level: LeverLogLevel, message: String) {
        lock.withLock { entries.add(Entry(level, message)) }
        onLog?.invoke()
    }

    val all: List<Entry> get() = lock.withLock { entries.toList() }

    fun messages(level: LeverLogLevel): List<String> =
        all.filter { it.level == level }.map { it.message }

    fun count(level: LeverLogLevel, containing: String): Int =
        messages(level).count { it.contains(containing) }

    fun contains(level: LeverLogLevel, needle: String): Boolean = count(level, needle) > 0
}

// MARK: transport

/**
 * Serves scripted responses and records every request. An exhausted script
 * answers like an unreachable network, which is exactly what the floor suite
 * wants by default (spec 0002 §10.1).
 */
internal class ScriptedTransport : LeverTransport {
    private val lock = ReentrantLock()
    private val responses = ArrayDeque<Result<HttpResponse>>()
    private val streams = ArrayDeque<Result<HttpStream>>()
    private val recordedRequests = mutableListOf<HttpRequest>()
    private val recordedStreamRequests = mutableListOf<HttpRequest>()

    @Volatile private var gate: CompletableDeferred<Unit>? = null

    @Volatile var isClosed: Boolean = false
        private set

    val requests: List<HttpRequest> get() = lock.withLock { recordedRequests.toList() }
    val streamRequests: List<HttpRequest> get() = lock.withLock { recordedStreamRequests.toList() }
    val requestCount: Int get() = lock.withLock { recordedRequests.size }

    fun enqueue(response: HttpResponse) {
        lock.withLock { responses.addLast(Result.success(response)) }
    }

    fun enqueue(failure: Exception) {
        lock.withLock { responses.addLast(Result.failure(failure)) }
    }

    fun enqueueStream(stream: HttpStream) {
        lock.withLock { streams.addLast(Result.success(stream)) }
    }

    fun enqueueStream(failure: Exception) {
        lock.withLock { streams.addLast(Result.failure(failure)) }
    }

    /** Holds every subsequent `send` open — the coalescing/cancellation seam. */
    fun pause() {
        gate = CompletableDeferred()
    }

    fun resume() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        lock.withLock { recordedRequests.add(request) }
        gate?.await()
        val next =
            lock.withLock { responses.removeFirstOrNull() }
                ?: throw IOException("no route to host")
        return next.getOrThrow()
    }

    override suspend fun openStream(request: HttpRequest): HttpStream {
        lock.withLock { recordedStreamRequests.add(request) }
        val next = lock.withLock { streams.removeFirstOrNull() }
        // Nothing scripted: park until cancelled rather than reconnect-loop.
        if (next == null) awaitCancellation()
        return next.getOrThrow()
    }

    override fun close() {
        isClosed = true
    }
}

/** A stream whose bytes the test pushes by hand. */
internal class StreamScript(
    status: Int? = 200,
    contentType: String? = "text/event-stream",
    retryAfter: String? = null,
) {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    /** How many times the runtime released this round's response. */
    @Volatile var closes: Int = 0
        private set

    val stream: HttpStream =
        HttpStream(
            status = status,
            headers =
                HttpHeaders(
                    buildList {
                        contentType?.let { add("Content-Type" to it) }
                        retryAfter?.let { add("Retry-After" to it) }
                    }
                ),
            chunks = chunks.consumeAsFlow(),
            onClose = { closes++ },
        )

    fun send(text: String) {
        check(chunks.trySend(text.toByteArray()).isSuccess) { "stream buffer overflow" }
    }

    fun send(bytes: ByteArray) {
        check(chunks.trySend(bytes).isSuccess) { "stream buffer overflow" }
    }

    fun version(version: Int) {
        send("event: version\ndata: {\"version\":$version}\n\n")
    }

    fun heartbeat() {
        send(": hb\n\n")
    }

    /** EOF: the collector completes, which sends the runtime into backoff. */
    fun close() {
        chunks.close()
    }

    fun fail(cause: Exception = IOException("connection lost")) {
        chunks.close(cause)
    }
}

internal fun jsonResponse(body: String, status: Int = 200, etag: String? = null): HttpResponse =
    HttpResponse(
        status = status,
        headers =
            HttpHeaders(
                buildList {
                    add("Content-Type" to "application/json")
                    etag?.let { add("ETag" to it) }
                }
            ),
        body = body.toByteArray(),
    )

internal fun statusResponse(status: Int, etag: String? = null, body: String = ""): HttpResponse =
    HttpResponse(
        status = status,
        headers = HttpHeaders(buildList { etag?.let { add("ETag" to it) } }),
        body = body.toByteArray(),
    )

/** The transport handed back something that is not an HTTP response. */
internal val nonHttpResponse: HttpResponse get() = HttpResponse(status = null)

// MARK: payloads

/** The spec 0001 §5.1 envelope around a resolve payload. */
internal fun resolveBody(version: Int, values: Map<String, String> = emptyMap()): String {
    val entries = values.keys.sorted().joinToString(",") { "\"$it\":${values.getValue(it)}" }
    return """{"ok":true,"message":"Resolved ${values.size} parameters",""" +
        """"data":{"version":$version,"values":{$entries}},"error":null}"""
}

internal fun envelope(data: String?, ok: Boolean = true, errorCode: String? = null): String {
    val error = errorCode?.let { """{"code":"$it"}""" } ?: "null"
    return """{"ok":$ok,"message":"m","data":${data ?: "null"},"error":$error}"""
}

internal fun boolValue(value: Boolean) = """{"type":"boolean","value":$value}"""

internal fun stringValue(value: String) = """{"type":"string","value":"$value"}"""

internal fun numberValue(value: String) = """{"type":"number","value":$value}"""

internal fun jsonWireValue(value: String) = """{"type":"json","value":$value}"""

internal fun wireValue(type: String, json: String): WireValue =
    WireValue(type, leverJson.decodeFromString<JsonElement>(json))

internal fun wireBool(value: Boolean) = wireValue("boolean", value.toString())

internal fun wireString(value: String) = wireValue("string", "\"$value\"")

internal fun wireNumber(value: String) = wireValue("number", value)

// MARK: lifecycle

internal class ManualLifecycleSource(initial: LifecyclePhase = LifecyclePhase.FOREGROUND) :
    LifecycleSource {
    private val phase = MutableStateFlow(initial)

    @Volatile var subscriptions: Int = 0
        private set

    @Volatile var unsubscriptions: Int = 0
        private set

    @Volatile var detaches: Int = 0
        private set

    /**
     * A `StateFlow` is the right shape for the seam: it hands the current phase
     * to every new collector and collapses repeats, exactly like the live
     * `ProcessLifecycleOwner` source (spec 0003 §5).
     */
    override fun phases(): Flow<LifecyclePhase> = flow {
        subscriptions++
        try {
            phase.collect { emit(it) }
        } finally {
            unsubscriptions++
        }
    }

    override fun detach() {
        detaches++
    }

    fun send(next: LifecyclePhase) {
        phase.value = next
    }
}

// MARK: harness

/** One client with every seam replaced, over a scratch directory. */
internal class TestHarness(
    scheduler: TestCoroutineScheduler,
    startingAt: Long = 1_755_100_000,
    phase: LifecyclePhase = LifecyclePhase.FOREGROUND,
    /**
     * Tests that need real threads (the commit-gate and close-linearization
     * ones) hand in a real dispatcher instead of the virtual-time one.
     */
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
    private val onShutdown: () -> Unit = {},
) {
    val transport = ScriptedTransport()
    val lifecycle = ManualLifecycleSource(phase)
    val sink = RecordingLogSink()
    val directory: File = File.createTempFile("lever-tests", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Volatile var now: Long = startingAt

    @Volatile var jitterFraction: Double = 1.0

    @Volatile var runtimeShutdowns: Int = 0
        private set

    fun advanceWallClock(seconds: Long) {
        now += seconds
    }

    val environment: LeverEnvironment
        get() =
            LeverEnvironment(
                makeTransport = { transport },
                now = { now },
                lifecycle = { _ -> lifecycle },
                jitter = { ceiling -> ceiling * jitterFraction },
                runtimeThread = {
                    RuntimeThread(dispatcher) {
                        runtimeShutdowns++
                        onShutdown()
                    }
                },
            )

    fun configuration(
        clientKey: String = "pk_test",
        context: LeverContext = LeverContext(LeverPlatform("android"), "1.0.0"),
        minimumFetchInterval: Duration = 12.hours,
        automaticUpdates: Boolean = true,
        autoActivateOnNudge: Boolean = true,
        cacheNamespace: String? = null,
    ) = LeverConfiguration(
        baseUrl = "https://lever.example",
        clientKey = clientKey,
        context = context,
        minimumFetchInterval = minimumFetchInterval,
        automaticUpdates = automaticUpdates,
        autoActivateOnNudge = autoActivateOnNudge,
        cacheDirectory = directory,
        cacheNamespace = cacheNamespace,
        logSink = sink,
    )

    fun client(configuration: LeverConfiguration = configuration()): LeverClient =
        LeverClient(configuration, environment) { directory }

    /** The cache files a client with this configuration reads and writes. */
    fun cacheStore(clientKey: String = "pk_test", namespace: String? = null): CacheStore {
        val validated =
            validate(configuration(clientKey = clientKey, cacheNamespace = namespace)) { directory }
        return CacheStore(validated.cacheDirectory, validated.cacheKeyHash, sink)
    }

    /** Writes a snapshot where a client with this configuration will find it. */
    fun seedCache(
        version: Int,
        values: Map<String, WireValue>,
        etag: String? = null,
        fetchedAt: Long = now,
        clientKey: String = "pk_test",
        namespace: String? = null,
    ): CacheStore {
        val store = cacheStore(clientKey, namespace)
        store.save(CachedSnapshot(version, etag, values, fetchedAt, fetchedAt))
        return store
    }

    fun cleanup() {
        directory.deleteRecursively()
    }
}

// MARK: scheduling helpers

/**
 * Runs everything queued at the current instant, background collectors
 * included. `TestScope.advanceUntilIdle()` only considers foreground work, so
 * it silently skips the `updates` collectors and the runtime's own scope.
 */
internal fun TestScope.settle() {
    testScheduler.runCurrent()
}

/** Moves virtual time and lets everything that came due run. */
internal fun TestScope.advance(duration: Duration) {
    testScheduler.advanceTimeBy(duration)
    testScheduler.runCurrent()
}

/** Moves both clocks together, the way real time does. */
internal fun TestScope.advance(harness: TestHarness, duration: Duration) {
    harness.advanceWallClock(duration.inWholeSeconds)
    advance(duration)
}
