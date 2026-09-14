package top.azek431.hzzs.data.jinchan.perception

import org.junit.Assert.*
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanShopPerceptionTest {
    private fun frame(color: Int = 0xff000000.toInt()) = (JinChanFrameBridge.adapt(CapturedFrame(9, 10, 3_120, 1_440, IntArray(3_120 * 1_440) { color })) as JinChanFrameBridgeResult.Valid).frame
    private val open = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN)
    private val resolver = JinChanHeroIdentityResolver { if (it == "金蝉") ResolvedHero("jinchan", "金蝉") else null }

    @Test fun frozenFiveSlotsAndNameBand() {
        assertEquals("M6_1B_MEDIAN_V1", JinChanShopPerception.GEOMETRY_VERSION)
        assertEquals(5, JinChanShopPerception.SLOT_RECTS.size)
        assertEquals(0.149458, JinChanShopPerception.SLOT_RECTS[0].x, 0.0)
        assertEquals(0.898, JinChanShopPerception.NAME_BAND.y, 0.0)
        assertEquals(0.9766, JinChanShopPerception.NAME_BAND.y + JinChanShopPerception.NAME_BAND.height, 0.0000001)
    }

    @Test fun g1ExactIdentityPreservesPositionsAndUnmatched() {
        var call = 0
        val result = JinChanShopPerception.observe(frame(), open, JinChanOcrReader { _, _ ->
            call++; JinChanOcrResult(JinChanOcrStatus.VALID, if (call == 1) " 金 蝉 " else "未知英雄", .9)
        }, resolver)
        assertEquals(ShopObservationStatus.AVAILABLE, result.status)
        assertEquals((1..5).toList(), result.slots.map { it.position })
        assertTrue(result.slots.first().matched)
        assertFalse(result.slots[1].matched)
        assertEquals(ShopContentType.HERO_CARD, result.slots[1].contentType)
    }

    @Test fun g2AndG3NeverInventEmpty() {
        val emptyOcr = JinChanOcrReader { _, _ -> JinChanOcrResult(JinChanOcrStatus.EMPTY) }
        val dark = JinChanShopPerception.observe(frame(), open, emptyOcr, resolver)
        assertTrue(dark.slots.all { it.contentType == ShopContentType.NON_HERO })
        val bright = JinChanShopPerception.observe(frame(0xffffffff.toInt()), open, emptyOcr, resolver)
        assertTrue(bright.slots.all { it.contentType == ShopContentType.UNKNOWN })
        assertFalse((dark.slots + bright.slots).any { it.contentType == ShopContentType.EMPTY })
    }

    @Test fun gateAndFailuresAreUnavailable() {
        var calls = 0
        val reader = JinChanOcrReader { _, _ -> calls++; JinChanOcrResult(JinChanOcrStatus.ERROR) }
        assertEquals(ShopObservationStatus.UNAVAILABLE, JinChanShopPerception.observe(frame(), JinChanStableState(false, JinChanStableUiState.SHOP_OPEN), reader, resolver).status)
        assertEquals(0, calls)
        assertEquals(ShopObservationStatus.UNAVAILABLE, JinChanShopPerception.observe(frame(), open, reader, resolver).status)
        assertEquals(ShopObservationStatus.UNAVAILABLE, JinChanShopPerception.observe(frame(), open, JinChanOcrReader { _, _ -> JinChanOcrResult(JinChanOcrStatus.PARTIAL) }, resolver).status)
        assertEquals(ShopObservationStatus.UNAVAILABLE, JinChanShopPerception.observe(frame(), open, JinChanOcrReader { _, _ -> JinChanOcrResult(JinChanOcrStatus.EMPTY) }, null).status)
    }
}
