package dev.forcetower.lever

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `json` memoization is per key, not per descriptor name (review 0003 pass 3,
 * P3-F1 and P3-F5).
 *
 * A serializer's `serialName` is not a type identity, so the memo cannot be
 * keyed by it: two keys that share a wire name and a descriptor name must never
 * share a decoded object, or generic erasure lets one key's value escape into
 * the other's call site and throw where reads promise not to.
 */
internal class MemoizationTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private fun TestScope.client(values: Map<String, WireValue>): LeverClient {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        val client = harness.client(harness.configuration(automaticUpdates = false))
        client.stage(Representation(1, values, null, harness.now, null))
        client.activate()
        return client
    }

    @Test
    fun `two list keys over one wire name decode independently`() = runTest {
        val strings = LeverKey.json("tags", emptyList(), ListSerializer(String.serializer()))
        val ints = LeverKey.json("tags", emptyList(), ListSerializer(Int.serializer()))
        // The collision the memo must survive: both are kotlin.collections.ArrayList.
        assertEquals(strings.typeId, ints.typeId)

        val client = client(mapOf("tags" to wireValue("json", """["1","2"]""")))

        assertContentEquals(listOf("1", "2"), client[strings])
        // A shared memo would hand this read the `List<String>` above, and the
        // ClassCastException would land on the caller's first element access.
        assertContentEquals(listOf(1, 2), client[ints])
        assertContentEquals(listOf("1", "2"), client[strings], "the memo still serves its own key")

        // …and each element really is what its key asked for, which is what
        // erasure would let a shared memo entry sail past.
        (client[strings] as List<*>).forEach { assertTrue(it is String, "got ${it?.javaClass}") }
        (client[ints] as List<*>).forEach { assertTrue(it is Int, "got ${it?.javaClass}") }
        client.close()
    }

    @Test
    fun `two map keys over one wire name decode independently`() = runTest {
        val toStrings = LeverKey.json("meta", emptyMap(), MapSerializer(String.serializer(), String.serializer()))
        val toInts = LeverKey.json("meta", emptyMap(), MapSerializer(String.serializer(), Int.serializer()))
        assertEquals(toStrings.typeId, toInts.typeId)

        val client = client(mapOf("meta" to wireValue("json", """{"a":"1"}""")))

        assertEquals(mapOf("a" to "1"), client[toStrings])
        assertEquals(mapOf("a" to 1), client[toInts])
        assertEquals(mapOf("a" to "1"), client[toStrings])
        (client[toStrings] as Map<*, *>).values.forEach {
            assertTrue(it is String, "got ${it?.javaClass}")
        }
        (client[toInts] as Map<*, *>).values.forEach { assertTrue(it is Int, "got ${it?.javaClass}") }
        client.close()
    }

    @Test
    fun `custom serializers that share a descriptor name do not share a memo entry`() = runTest {
        val left = LeverKey.json("badge", Left(""), LeftSerializer)
        val right = LeverKey.json("badge", Right(""), RightSerializer)
        // Deliberately identical, which is all a descriptor name can promise.
        assertEquals(left.typeId, right.typeId)

        val client = client(mapOf("badge" to wireValue("json", "\"gold\"")))

        assertEquals(Left("gold"), client[left])
        assertEquals(Right("gold"), client[right])
        assertEquals(Left("gold"), client[left])
        assertEquals(Right("gold"), client[right])
        client.close()
    }

    @Test
    fun `the same key instance is decoded once and served from the memo after that`() = runTest {
        val decodes = AtomicInteger()
        val key = LeverKey.json("badge", Left(""), CountingSerializer(decodes))
        val client = client(mapOf("badge" to wireValue("json", "\"gold\"")))

        repeat(5) { assertEquals(Left("gold"), client[key]) }
        assertEquals(1, decodes.get(), "a memoized key must not re-run its serializer")
        client.close()
    }

    /**
     * P3-F5: two readers missing the memo at once must not both run a consumer's
     * serializer. One decodes while the rest wait for its result.
     */
    @Test
    fun `concurrent readers of one key run the serializer exactly once`() = runTest {
        val decodes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key =
            LeverKey.json("badge", Left(""), CountingSerializer(decodes, entered, release))
        val client = client(mapOf("badge" to wireValue("json", "\"gold\"")))

        val results = Collections.synchronizedList(mutableListOf<Left>())
        val readers = (1..8).map { Thread { results.add(client[key]) } }
        readers.forEach { it.start() }

        assertTrue(entered.await(10, TimeUnit.SECONDS), "no reader ever started decoding")
        // Every other reader is now waiting on the one that got there first.
        release.countDown()
        readers.forEach { it.join(10_000) }

        assertEquals(8, results.size)
        assertEquals(1, decodes.get(), "the serializer ran more than once")
        assertTrue(results.all { it == Left("gold") })
        client.close()
    }

    @Test
    fun `a decode that races an activation is not installed into the new snapshot`() = runTest {
        val decodes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = LeverKey.json("badge", Left(""), CountingSerializer(decodes, entered, release))

        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        val client = harness.client(harness.configuration(automaticUpdates = false))
        client.stage(
            Representation(1, mapOf("badge" to wireValue("json", "\"gold\"")), null, harness.now, null)
        )
        client.activate()

        val reader = Thread { client[key] }
        reader.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        // A new representation lands while the old one is still being decoded.
        client.stage(
            Representation(2, mapOf("badge" to wireValue("json", "\"silver\"")), null, harness.now, null)
        )
        assertTrue(client.activate())

        release.countDown()
        reader.join(10_000)

        assertEquals(Left("silver"), client[key], "a stale decode was installed")
        client.close()
    }

    // MARK: fixtures

    private data class Left(val value: String)

    private data class Right(val value: String)

    /** Two serializers, one descriptor name — legal, and indistinguishable by it. */
    private object LeftSerializer : KSerializer<Left> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Badge", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Left = Left(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Left) {
            encoder.encodeString(value.value)
        }
    }

    private object RightSerializer : KSerializer<Right> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Badge", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Right = Right(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Right) {
            encoder.encodeString(value.value)
        }
    }

    /** Consumer code the SDK cannot see inside: counted, and optionally blocking. */
    private class CountingSerializer(
        private val decodes: AtomicInteger,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : KSerializer<Left> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Badge", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Left {
            decodes.incrementAndGet()
            val value = decoder.decodeString()
            entered?.countDown()
            release?.await(10, TimeUnit.SECONDS)
            return Left(value)
        }

        override fun serialize(encoder: Encoder, value: Left) {
            encoder.encodeString(value.value)
        }
    }
}
