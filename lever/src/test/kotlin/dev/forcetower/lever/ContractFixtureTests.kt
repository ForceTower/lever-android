package dev.forcetower.lever

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays the HTTP tapes recorded against the real service (spec 0002 §10.4).
 *
 * The fixtures live in the lever monorepo, pinned by SHA in
 * `.contract-fixtures-sha`; CI checks that revision out and points
 * `LEVER_CONTRACT_FIXTURES` at it, and the build passes the path through as a
 * system property. Locally a sibling `lever` checkout is used if one is there.
 * Same tapes, three languages — this is what keeps the SDKs from each agreeing
 * with a slightly different server.
 */
internal class ContractFixtureTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    @Test
    fun `the pinned fixture checkout is where ci said it would be`() {
        if (System.getenv("LEVER_CONTRACT_FIXTURES") == null) return
        assertTrue(fixtureFiles().size >= 6, "expected at least 6 http fixtures")
    }

    @Test
    fun `every tape replays`() = runTest {
        val files = fixtureFiles()
        if (files.isEmpty()) {
            println("no contract fixtures found at ${fixturesDirectory()?.path ?: "<unset>"} — skipping")
            return@runTest
        }
        files.forEach { replay(it) }
    }

    private suspend fun TestScope.replay(file: File) {
        val fixture = leverJson.decodeFromString<Fixture>(file.readText())
        assertEquals(file.nameWithoutExtension, fixture.name, "fixture name must equal its file")

        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        val context = fixture.steps.first().request.context

        // The client id is an installation identifier the SDK generates, so the
        // tape's value is planted where construction will find it.
        val store = harness.cacheStore()
        store.directory.mkdirs()
        store.identityFile.writeText(
            """{"clientId":"${context.clientId}","schemaVersion":1}"""
        )

        val client =
            harness.client(
                harness.configuration(
                    context =
                        LeverContext(
                            platform = LeverPlatform(context.platform ?: "android"),
                            appVersion = context.appVersion,
                            attributes = context.attributes,
                        ),
                    automaticUpdates = false,
                )
            )
        assertEquals(context.clientId, client.clientId, "${fixture.name}: planted client id")

        val recordedETags = mutableListOf<String?>()

        fixture.steps.forEachIndexed { index, step ->
            val label = "${fixture.name} step ${index + 1}"
            harness.transport.enqueue(
                HttpResponseOfStep(step, leverJson).response()
            )
            recordedETags.add(step.response.etag)

            var changed: Boolean? = null
            var thrown: Throwable? = null
            try {
                changed = client.fetchAndActivate()
            } catch (cause: Throwable) {
                thrown = cause
            }

            // The request the SDK built must be the one the server answered.
            val request = harness.transport.requests[index]
            assertEquals(
                "https://lever.example${step.request.path}?${step.request.query}",
                request.url,
                "$label: request bytes",
            )
            val validator = step.request.ifNoneMatch
            assertEquals(
                validator?.let { recordedETags[it.fromStep - 1] },
                request.header("If-None-Match"),
                "$label: validator",
            )

            when (step.expect.error) {
                null -> {
                    assertEquals(null, thrown?.toString(), "$label: unexpected failure")
                    step.expect.changed?.let { assertEquals(it, changed, "$label: changed") }
                }
                "invalidKey" ->
                    assertTrue(thrown is LeverException.InvalidKey, "$label: expected invalidKey, got $thrown")
                else -> throw AssertionError("$label: unhandled expected error ${step.expect.error}")
            }

            assertEquals(step.expect.activatedVersion, client.activatedVersion, "$label: version")

            step.expect.reads.orEmpty().forEach { read ->
                val actual: Any? =
                    when (read.type) {
                        "boolean" ->
                            client[LeverKey.boolean(read.key, read.default.jsonPrimitive.boolean())]
                        "string" ->
                            client[LeverKey.string(read.key, read.default.jsonPrimitive.content)]
                        "int" -> client[LeverKey.int(read.key, read.default.jsonPrimitive.content.toInt())]
                        "double" ->
                            client[LeverKey.double(read.key, read.default.jsonPrimitive.content.toDouble())]
                        "json" -> client[LeverKey.json(read.key, read.default.asStringMap())]
                        else -> throw AssertionError("$label: unknown read type ${read.type}")
                    }
                val expected: Any? =
                    when (read.type) {
                        "boolean" -> read.expected.jsonPrimitive.boolean()
                        "string" -> read.expected.jsonPrimitive.content
                        "int" -> read.expected.jsonPrimitive.content.toDouble().toInt()
                        "double" -> read.expected.jsonPrimitive.content.toDouble()
                        else -> read.expected.asStringMap()
                    }
                assertEquals(expected, actual, "$label: read ${read.key} as ${read.type}")
            }
        }

        assertEquals(fixture.steps.size, harness.transport.requestCount, "${fixture.name}: requests")
        client.close()
    }

    private fun JsonPrimitive.boolean(): Boolean = checkNotNull(booleanOrNull)

    /**
     * The fixture format's `json` read type: a string-to-string map, so a
     * non-string member is a decode failure that must fall back to the default.
     */
    private fun JsonElement.asStringMap(): Map<String, String> =
        jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    /** A recorded response, replayed verbatim — envelope and all. */
    private class HttpResponseOfStep(
        private val step: Fixture.Step,
        private val json: kotlinx.serialization.json.Json,
    ) {
        fun response(): dev.forcetower.lever.transport.HttpResponse {
            val body = step.response.body
            if (body == null || body is JsonNull) {
                return statusResponse(step.response.status, etag = step.response.etag)
            }
            check(body is JsonObject && body.containsKey("ok")) {
                "${step.response.status}: the tape's body is not a spec 0001 §5.1 envelope — " +
                    "re-record the fixtures and bump .contract-fixtures-sha"
            }
            return jsonResponse(
                json.encodeToString(JsonElement.serializer(), body),
                status = step.response.status,
                etag = step.response.etag,
            )
        }
    }

    private fun fixturesDirectory(): File? {
        val fromEnvironment = System.getenv("LEVER_CONTRACT_FIXTURES")
        val fromProperty = System.getProperty("lever.contractFixtures")
        return listOfNotNull(fromEnvironment, fromProperty).map(::File).firstOrNull { it.isDirectory }
    }

    private fun fixtureFiles(): List<File> =
        fixturesDirectory()?.listFiles { file -> file.extension == "json" }?.sorted().orEmpty()

    // MARK: the fixture format (the SDK's half; `setup` is the server's business)

    @Serializable
    private data class Fixture(val name: String, val steps: List<Step>) {
        @Serializable
        data class Step(val request: Request, val response: Response, val expect: Expect)

        @Serializable
        data class Request(
            val path: String,
            val context: Context,
            val query: String,
            val ifNoneMatch: Validator? = null,
        )

        @Serializable data class Validator(val fromStep: Int)

        @Serializable
        data class Context(
            val platform: String? = null,
            val appVersion: String? = null,
            val clientId: String? = null,
            val attributes: Map<String, String> = emptyMap(),
        )

        @Serializable
        data class Response(val status: Int, val etag: String? = null, val body: JsonElement? = null)

        @Serializable
        data class Expect(
            val activatedVersion: Int? = null,
            val changed: Boolean? = null,
            val error: String? = null,
            val reads: List<Read>? = null,
        )

        @Serializable
        data class Read(
            val key: String,
            val type: String,
            @SerialName("default") val default: JsonElement,
            val expected: JsonElement,
        )
    }
}
