package top.azek431.hzzs.data.jinchan.perception

import org.junit.Assert.*
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanHudPerceptionTest {
    private val frame by lazy {
        val source = CapturedFrame(17, 20, 3_120, 1_440, IntArray(3_120 * 1_440))
        (JinChanFrameBridge.adapt(source) as JinChanFrameBridgeResult.Valid).frame
    }

    @Test fun frozenGeometryAndVisibilityThresholds() {
        assertEquals(364.0, JinChanHudPerception.LEVEL_ROI.left, 0.0)
        var calls = 0
        val reader = JinChanOcrReader { _, _ -> calls++; JinChanOcrResult(JinChanOcrStatus.VALID, "7") }
        assertEquals(HudObservationStatus.NOT_VISIBLE, JinChanHudPerception.level(frame, reader, 1.999).status)
        assertEquals(0, calls)
        assertEquals(7, JinChanHudPerception.level(frame, reader, null).value) // metric failure fails open
        assertEquals(HudObservationStatus.NOT_VISIBLE, JinChanHudPerception.exp(frame, reader, 2.999).status)
        assertEquals(HudObservationStatus.NOT_VISIBLE, JinChanHudPerception.gold(frame, reader, 1.999).status)
    }

    @Test fun expRequiresValidRange() {
        fun read(text: String) = JinChanHudPerception.exp(frame, JinChanOcrReader { _, _ -> JinChanOcrResult(JinChanOcrStatus.VALID, text) }, 4.0)
        assertEquals(ExpObservation(HudObservationStatus.AVAILABLE, 3, 10), read("3/10"))
        listOf("-1/10", "11/10", "0/0", "x/10").forEach { assertEquals(HudObservationStatus.INVALID, read(it).status) }
    }

    @Test fun goldPurityCeilingAndTrustFailClosed() {
        fun gold(text: String, purity: GoldPurity) = JinChanHudPerception.gold(frame, JinChanOcrReader { _, _ -> JinChanOcrResult(JinChanOcrStatus.VALID, text) }, 3.0, purity)
        assertEquals(GoldTrust.TRUSTED, gold("200", GoldPurity.CLEAN).trust)
        assertEquals(200, gold("200", GoldPurity.CLEAN).value)
        assertEquals(GoldTrust.UNTRUSTED, gold("201", GoldPurity.CLEAN).trust)
        assertNull(gold("201", GoldPurity.CLEAN).value)
        assertEquals(GoldTrust.UNTRUSTED, gold("20", GoldPurity.SUSPECT).trust)
        assertEquals(HudObservationStatus.INVALID, gold("20x", GoldPurity.CLEAN).status)
    }
}
