package top.azek431.hzzs.data.jinchan.perception

import kotlin.math.abs
import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.roi.NormalizedRect

object JinChanShopPerception {
    const val GEOMETRY_VERSION = "M6_1B_MEDIAN_V1"
    const val NAME_OCR_CONFIDENCE = 0.8
    val SLOT_RECTS = listOf(
        NormalizedRect(0.149458, 0.04, 0.162664, 0.415758),
        NormalizedRect(0.312000, 0.04, 0.162664, 0.415758),
        NormalizedRect(0.474664, 0.04, 0.162664, 0.415758),
        NormalizedRect(0.637083, 0.04, 0.162664, 0.415758),
        NormalizedRect(0.800188, 0.04, 0.162664, 0.415758),
    )
    val NAME_BAND = NormalizedRect(0.0, 0.898, 1.0, 0.0786)

    fun observe(
        frame: JinChanCanonicalFrame,
        stableState: JinChanStableState,
        ocr: JinChanOcrReader,
        resolver: JinChanHeroIdentityResolver?,
    ): ShopObservation {
        if (stableState.inGame != true || stableState.uiState != JinChanStableUiState.SHOP_OPEN) {
            return ShopObservation(ShopObservationStatus.UNAVAILABLE, frame.sourceSequence, reason = "SHOP_GATE_CLOSED")
        }
        if (resolver == null) return ShopObservation(ShopObservationStatus.UNAVAILABLE, frame.sourceSequence, reason = "BUILDER_UNAVAILABLE")
        return try {
            val slots = SLOT_RECTS.mapIndexed { index, normalized ->
                val slot = normalized.toCanonical()
                val band = slot.resolveNameBand()
                val result = ocr.read(frame, band)
                if (result.status in setOf(JinChanOcrStatus.PARTIAL, JinChanOcrStatus.ERROR, JinChanOcrStatus.NOT_AVAILABLE)) {
                    return ShopObservation(ShopObservationStatus.UNAVAILABLE, frame.sourceSequence, reason = "SHOP_OCR_${result.status}")
                }
                buildSlot(index + 1, frame, slot, result, resolver)
            }
            if (slots.size != 5) ShopObservation(ShopObservationStatus.UNAVAILABLE, frame.sourceSequence, reason = "SHOP_PARTIAL")
            else ShopObservation(ShopObservationStatus.AVAILABLE, frame.sourceSequence, slots)
        } catch (_: Throwable) {
            ShopObservation(ShopObservationStatus.UNAVAILABLE, frame.sourceSequence, reason = "SHOP_ERROR")
        }
    }

    private fun buildSlot(position: Int, frame: JinChanCanonicalFrame, slot: FrameRect, ocr: JinChanOcrResult, resolver: JinChanHeroIdentityResolver): ShopSlotObservation {
        val normalizedName = normalizeName(ocr.text)
        val usable = ocr.status == JinChanOcrStatus.VALID && normalizedName.isNotEmpty() && (ocr.confidence ?: 0.0) >= NAME_OCR_CONFIDENCE
        if (usable) {
            val hero = resolver.resolveExact(normalizedName)
            return ShopSlotObservation(position, ShopContentType.HERO_CARD, normalizedName, hero, hero != null)
        }
        if (ocr.status == JinChanOcrStatus.VALID && normalizedName.isNotEmpty()) {
            return ShopSlotObservation(position, ShopContentType.UNKNOWN, normalizedName)
        }
        val metrics = visualMetrics(frame, slot) ?: return ShopSlotObservation(position, ShopContentType.UNKNOWN)
        val nonHero = metrics.meanLuma < 45.0 && metrics.madLuma < 14.0 && metrics.edgeEvidence < 12.0
        return ShopSlotObservation(position, if (nonHero) ShopContentType.NON_HERO else ShopContentType.UNKNOWN)
    }

    private data class Metrics(val meanLuma: Double, val madLuma: Double, val edgeEvidence: Double)
    private fun visualMetrics(frame: JinChanCanonicalFrame, rect: FrameRect): Metrics? {
        val left = rect.left.toInt(); val top = rect.top.toInt(); val right = rect.right.toInt(); val bottom = rect.bottom.toInt()
        var sum = 0L; var count = 0L
        for (y in top until bottom) for (x in left until right) { sum += JinChanHudPerception.luma(frame, x, y) ?: return null; count++ }
        if (count == 0L) return null
        val mean = sum.toDouble() / count
        var deviations = 0.0; var edge = 0L; var pairs = 0L
        for (y in top until bottom) for (x in left until right) {
            val value = JinChanHudPerception.luma(frame, x, y) ?: return null
            deviations += abs(value - mean)
            if (x + 1 < right) { edge += abs(value - (JinChanHudPerception.luma(frame, x + 1, y) ?: return null)); pairs++ }
            if (y + 1 < bottom) { edge += abs(value - (JinChanHudPerception.luma(frame, x, y + 1) ?: return null)); pairs++ }
        }
        return Metrics(mean, deviations / count, if (pairs == 0L) Double.POSITIVE_INFINITY else edge.toDouble() / pairs)
    }

    fun normalizeName(text: String?): String = text.orEmpty().trim().replace(Regex("\\s+"), "")

    private fun NormalizedRect.toCanonical() = FrameRect(
        x * JinChanFrameBridge.CANONICAL_WIDTH, y * JinChanFrameBridge.CANONICAL_HEIGHT,
        (x + width) * JinChanFrameBridge.CANONICAL_WIDTH, (y + height) * JinChanFrameBridge.CANONICAL_HEIGHT,
    )
    fun nameBand(slot: FrameRect): FrameRect = slot.resolveNameBand()
    private fun FrameRect.resolveNameBand(): FrameRect {
        val height = bottom - top
        return FrameRect(left, top + height * NAME_BAND.y, right, top + height * (NAME_BAND.y + NAME_BAND.height))
    }
}
