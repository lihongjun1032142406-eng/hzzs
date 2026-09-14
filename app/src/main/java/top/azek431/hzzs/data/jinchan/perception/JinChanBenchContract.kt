package top.azek431.hzzs.data.jinchan.perception

/** Durable, pixel-independent state of one zero-based Bench slot. */
enum class BenchSlotState { HERO, EMPTY, UNKNOWN }

enum class BenchObservationStatus { COMPLETE, PARTIAL, UNAVAILABLE, INVALID }

/** Immutable structured Bench value. It never owns a frame, pixels, or an action reference. */
data class BenchSlotObservation(
    val slotIndex: Int,
    val state: BenchSlotState,
    val identity: String? = null,
    val hero: ResolvedHero? = null,
    val starLevel: Int? = null,
)

data class BenchObservation(
    val status: BenchObservationStatus,
    val frameSeq: Long,
    val slots: List<BenchSlotObservation> = emptyList(),
    val reason: String? = null,
)

/** Structured upstream content only; this is not evidence from a raw-frame Bench detector. */
enum class StructuredBenchContentType { HERO, EMPTY, UNKNOWN }

data class StructuredBenchSlotEvidence(
    val slotIndex: Int?,
    val contentType: StructuredBenchContentType?,
    val identity: String? = null,
    val starLevel: Any? = null,
)

data class StructuredBenchEvidence(
    val available: Boolean,
    val slotCount: Int,
    val slots: List<StructuredBenchSlotEvidence?> = emptyList(),
)

/**
 * Pure normalization boundary for already-structured Bench evidence.
 *
 * [slotCount] belongs to the upstream contract so this adapter does not guess Bench geometry.
 */
object JinChanStructuredBenchAdapter {
    fun adapt(
        frameSeq: Long,
        evidence: StructuredBenchEvidence?,
        resolver: JinChanHeroIdentityResolver?,
    ): BenchObservation {
        if (evidence == null) return BenchObservation(BenchObservationStatus.UNAVAILABLE, frameSeq, reason = "STRUCTURED_INPUT_UNAVAILABLE")
        if (evidence.slotCount <= 0) return BenchObservation(BenchObservationStatus.INVALID, frameSeq, reason = "INVALID_SLOT_COUNT")
        val unknownSlots = { List(evidence.slotCount) { unknown(it) } }
        if (!evidence.available) return BenchObservation(BenchObservationStatus.UNAVAILABLE, frameSeq, unknownSlots(), "STRUCTURED_INPUT_UNAVAILABLE")

        val indexed = mutableMapOf<Int, StructuredBenchSlotEvidence>()
        evidence.slots.forEach { slot ->
            if (slot?.slotIndex == null || slot.contentType == null) {
                return BenchObservation(BenchObservationStatus.INVALID, frameSeq, unknownSlots(), "MALFORMED_SLOT")
            }
            val index = slot.slotIndex
            if (index !in 0 until evidence.slotCount) {
                return BenchObservation(BenchObservationStatus.INVALID, frameSeq, unknownSlots(), "SLOT_INDEX_OUT_OF_RANGE")
            }
            if (indexed.put(index, slot) != null) {
                return BenchObservation(BenchObservationStatus.INVALID, frameSeq, unknownSlots(), "DUPLICATE_SLOT_INDEX")
            }
        }

        var partial = indexed.size != evidence.slotCount
        val slots = List(evidence.slotCount) { index ->
            val slot = indexed[index] ?: return@List unknown(index)
            val normalizedStar = (slot.starLevel as? Int)?.takeIf { it in 1..3 }
            val validStar = slot.starLevel == null || normalizedStar != null
            if (!validStar) partial = true
            when (slot.contentType) {
                null -> {
                    partial = true
                    unknown(index)
                }
                StructuredBenchContentType.EMPTY -> BenchSlotObservation(index, BenchSlotState.EMPTY)
                StructuredBenchContentType.UNKNOWN -> {
                    partial = true
                    unknown(index, slot.identity)
                }
                StructuredBenchContentType.HERO -> {
                    val identity = slot.identity?.trim()?.takeIf { it.isNotEmpty() }
                    val hero = identity?.let { resolver?.resolveExact(it) }
                    if (hero == null) partial = true
                    BenchSlotObservation(index, BenchSlotState.HERO, identity, hero, normalizedStar)
                }
            }
        }
        return BenchObservation(
            if (partial) BenchObservationStatus.PARTIAL else BenchObservationStatus.COMPLETE,
            frameSeq,
            slots,
            if (partial) "STRUCTURED_INPUT_PARTIAL" else null,
        )
    }

    private fun unknown(index: Int, identity: String? = null) =
        BenchSlotObservation(index, BenchSlotState.UNKNOWN, identity = identity)
}
