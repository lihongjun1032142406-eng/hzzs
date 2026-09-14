package top.azek431.hzzs.data.jinchan.perception

import kotlin.math.abs
import top.azek431.hzzs.data.jinchan.frame.FramePoint
import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame

object JinChanHudPerception {
    val LEVEL_ROI = FrameRect(364.0, 1344.0, 410.0, 1396.0)
    val EXP_ROI = FrameRect(201.0, 1103.0, 323.0, 1144.0)
    val GOLD_ROI = FrameRect(2866.0, 1240.0, 3016.0, 1300.0)
    const val LEVEL_VISIBILITY_EDGE_FLOOR = 2.0
    const val EXP_VISIBILITY_EDGE_FLOOR = 3.0
    const val GOLD_VISIBILITY_EDGE_FLOOR = 2.0
    const val OBSERVED_GOLD_SAFETY_CEILING = 200

    fun observe(frame: JinChanCanonicalFrame, ocr: JinChanOcrReader): JinChanHudObservation =
        JinChanHudObservation(frame.sourceSequence, level(frame, ocr), exp(frame, ocr), gold(frame, ocr))

    fun level(frame: JinChanCanonicalFrame, ocr: JinChanOcrReader, edgeMetric: Double? = edgeMetric(frame, LEVEL_ROI)): LevelObservation {
        if (edgeMetric != null && edgeMetric.isFinite() && edgeMetric < LEVEL_VISIBILITY_EDGE_FLOOR) return LevelObservation(HudObservationStatus.NOT_VISIBLE)
        val result = runCatching { ocr.read(frame, LEVEL_ROI) }.getOrElse { return LevelObservation(HudObservationStatus.UNAVAILABLE, reason = "OCR_ERROR") }
        if (result.status == JinChanOcrStatus.NOT_AVAILABLE) return LevelObservation(HudObservationStatus.UNAVAILABLE)
        if (result.status == JinChanOcrStatus.EMPTY) return LevelObservation(HudObservationStatus.NOT_VISIBLE)
        val text = result.text?.trim()
        val value = text?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        return if (result.status == JinChanOcrStatus.VALID && value != null) LevelObservation(HudObservationStatus.AVAILABLE, value)
        else LevelObservation(HudObservationStatus.INVALID, reason = "LEVEL_INVALID")
    }

    fun exp(frame: JinChanCanonicalFrame, ocr: JinChanOcrReader, edgeMetric: Double? = edgeMetric(frame, EXP_ROI)): ExpObservation {
        if (edgeMetric != null && edgeMetric.isFinite() && edgeMetric < EXP_VISIBILITY_EDGE_FLOOR) return ExpObservation(HudObservationStatus.NOT_VISIBLE)
        val result = runCatching { ocr.read(frame, EXP_ROI) }.getOrElse { return ExpObservation(HudObservationStatus.UNAVAILABLE, reason = "OCR_ERROR") }
        if (result.status == JinChanOcrStatus.NOT_AVAILABLE) return ExpObservation(HudObservationStatus.UNAVAILABLE)
        if (result.status == JinChanOcrStatus.EMPTY) return ExpObservation(HudObservationStatus.NOT_VISIBLE)
        val parts = result.text?.trim()?.split('/')?.map(String::trim)
        val current = parts?.takeIf { it.size == 2 }?.get(0)?.toIntOrNull()
        val max = parts?.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()
        return if (result.status == JinChanOcrStatus.VALID && current != null && max != null && current >= 0 && max > 0 && current <= max) {
            ExpObservation(HudObservationStatus.AVAILABLE, current, max)
        } else ExpObservation(HudObservationStatus.INVALID, reason = "EXP_RANGE_OR_FORMAT_INVALID")
    }

    fun gold(frame: JinChanCanonicalFrame, ocr: JinChanOcrReader, edgeMetric: Double? = edgeMetric(frame, GOLD_ROI), purity: GoldPurity = goldPurity(frame)): GoldObservation {
        if (edgeMetric != null && edgeMetric.isFinite() && edgeMetric < GOLD_VISIBILITY_EDGE_FLOOR) return GoldObservation(HudObservationStatus.NOT_VISIBLE, GoldTrust.UNAVAILABLE)
        val result = runCatching { ocr.read(frame, GOLD_ROI) }.getOrElse { return GoldObservation(HudObservationStatus.UNAVAILABLE, GoldTrust.UNAVAILABLE, reason = "OCR_ERROR") }
        if (result.status == JinChanOcrStatus.NOT_AVAILABLE || result.status == JinChanOcrStatus.EMPTY) return GoldObservation(HudObservationStatus.UNAVAILABLE, GoldTrust.UNAVAILABLE, purity = purity)
        val text = result.text?.trim()
        val raw = text?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        if (result.status != JinChanOcrStatus.VALID || raw == null) return GoldObservation(HudObservationStatus.INVALID, GoldTrust.UNAVAILABLE, purity = purity, reason = "GOLD_INVALID")
        if (raw > OBSERVED_GOLD_SAFETY_CEILING || purity != GoldPurity.CLEAN) return GoldObservation(HudObservationStatus.AVAILABLE, GoldTrust.UNTRUSTED, rawValue = raw, purity = purity, reason = "GOLD_TRUST_BLOCKED")
        return GoldObservation(HudObservationStatus.AVAILABLE, GoldTrust.TRUSTED, value = raw, rawValue = raw, purity = purity)
    }

    fun edgeMetric(frame: JinChanCanonicalFrame, roi: FrameRect): Double? {
        val left = roi.left.toInt(); val top = roi.top.toInt(); val right = roi.right.toInt(); val bottom = roi.bottom.toInt()
        if (right - left < 2 || bottom - top < 2) return null
        var sum = 0L; var pairs = 0L
        for (y in top until bottom) for (x in left until right) {
            val here = luma(frame, x, y) ?: return null
            if (x + 1 < right) { sum += abs(here - (luma(frame, x + 1, y) ?: return null)); pairs++ }
            if (y + 1 < bottom) { sum += abs(here - (luma(frame, x, y + 1) ?: return null)); pairs++ }
        }
        return if (pairs == 0L) null else sum.toDouble() / pairs
    }

    fun goldPurity(frame: JinChanCanonicalFrame): GoldPurity {
        val left = GOLD_ROI.left.toInt(); val top = GOLD_ROI.top.toInt(); val bottom = GOLD_ROI.bottom.toInt()
        val hits = IntArray(30)
        for (x in 0 until 30) for (y in top until bottom) if ((luma(frame, left + x, y) ?: return GoldPurity.UNKNOWN) >= 150) hits[x]++
        val iconHit = hits.sliceArray(0..16).count { it > 0 }
        val gapHit = hits.sliceArray(17..29).count { it > 0 }
        return if (iconHit >= 15 && gapHit >= 10) GoldPurity.SUSPECT else GoldPurity.CLEAN
    }

    internal fun luma(frame: JinChanCanonicalFrame, x: Int, y: Int): Int? {
        val source = frame.canonicalToSource(FramePoint(x + .5, y + .5)) ?: return null
        val sx = source.x.toInt().coerceIn(0, frame.sourceWidth - 1); val sy = source.y.toInt().coerceIn(0, frame.sourceHeight - 1)
        val pixel = frame.pixels.getOrNull(sy * frame.sourceWidth + sx) ?: return null
        val r = pixel ushr 16 and 0xff; val g = pixel ushr 8 and 0xff; val b = pixel and 0xff
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
