package top.azek431.hzzs.data.jinchan.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JinChanStructuredBenchAdapterTest {
    private val resolver = JinChanHeroIdentityResolver { name ->
        if (name == "金蝉") ResolvedHero("jinchan", "金蝉") else null
    }

    @Test
    fun preservesZeroBasedOrderMissingUnknownAndExplicitEmpty() {
        val result = adapt(
            count = 4,
            slot(2, StructuredBenchContentType.HERO, "金蝉"),
            slot(0, StructuredBenchContentType.EMPTY),
        )

        assertEquals(BenchObservationStatus.PARTIAL, result.status)
        assertEquals(listOf(0, 1, 2, 3), result.slots.map { it.slotIndex })
        assertEquals(BenchSlotState.EMPTY, result.slots[0].state)
        assertEquals(BenchSlotState.UNKNOWN, result.slots[1].state)
        assertEquals(BenchSlotState.HERO, result.slots[2].state)
        assertEquals("jinchan", result.slots[2].hero?.canonicalId)
        assertEquals(BenchSlotState.UNKNOWN, result.slots[3].state)
        assertNotEquals(BenchSlotState.EMPTY, result.slots[1].state)
    }

    @Test
    fun unmatchedIdentityRemainsHeroWithoutGuessing() {
        val result = adapt(count = 1, slot(0, StructuredBenchContentType.HERO, "不存在"))

        assertEquals(BenchObservationStatus.PARTIAL, result.status)
        assertEquals(BenchSlotState.HERO, result.slots.single().state)
        assertEquals("不存在", result.slots.single().identity)
        assertNull(result.slots.single().hero)
    }

    @Test
    fun acceptsOnlySupportedStarsAndMarksInvalidStarPartial() {
        val accepted = (1..3).map { star ->
            adapt(count = 1, slot(0, StructuredBenchContentType.HERO, "金蝉", star)).slots.single().starLevel
        }
        assertEquals(listOf(1, 2, 3), accepted)

        val invalid = adapt(count = 1, slot(0, StructuredBenchContentType.HERO, "金蝉", 4))
        assertEquals(BenchObservationStatus.PARTIAL, invalid.status)
        assertNull(invalid.slots.single().starLevel)
        assertEquals("STRUCTURED_INPUT_PARTIAL", invalid.reason)

        val nonNumeric = adapt(count = 1, slot(0, StructuredBenchContentType.HERO, "金蝉", "二"))
        assertEquals(BenchObservationStatus.PARTIAL, nonNumeric.status)
        assertNull(nonNumeric.slots.single().starLevel)
    }

    @Test
    fun duplicateOutOfRangeAndMalformedSlotsFailClosed() {
        val duplicate = adapt(count = 2, slot(0, StructuredBenchContentType.EMPTY), slot(0, StructuredBenchContentType.EMPTY))
        assertEquals(BenchObservationStatus.INVALID, duplicate.status)
        assertEquals("DUPLICATE_SLOT_INDEX", duplicate.reason)
        assertEquals(listOf(BenchSlotState.UNKNOWN, BenchSlotState.UNKNOWN), duplicate.slots.map { it.state })

        val outOfRange = adapt(count = 1, slot(1, StructuredBenchContentType.EMPTY))
        assertEquals(BenchObservationStatus.INVALID, outOfRange.status)
        assertEquals("SLOT_INDEX_OUT_OF_RANGE", outOfRange.reason)

        val malformed = JinChanStructuredBenchAdapter.adapt(
            9,
            StructuredBenchEvidence(true, 1, listOf(null)),
            resolver,
        )
        assertEquals(BenchObservationStatus.INVALID, malformed.status)
        assertEquals("MALFORMED_SLOT", malformed.reason)
    }

    private fun adapt(count: Int, vararg slots: StructuredBenchSlotEvidence) =
        JinChanStructuredBenchAdapter.adapt(9, StructuredBenchEvidence(true, count, slots.toList()), resolver)

    private fun slot(index: Int, type: StructuredBenchContentType, identity: String? = null, star: Any? = null) =
        StructuredBenchSlotEvidence(index, type, identity, star)
}
