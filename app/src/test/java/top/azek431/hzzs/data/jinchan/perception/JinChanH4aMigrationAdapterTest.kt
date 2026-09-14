package top.azek431.hzzs.data.jinchan.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiId
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiRegistry
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanH4aMigrationAdapterTest {
    @Test
    fun pendingGateDoesNotRunShopProducerAndKeepsSameFrameIdentity() = withFrame { frame, source ->
        var shopCalls = 0
        val adapter = JinChanH4aMigrationAdapter(
            hudProducer = recordingHud(frame),
            shopProducer = JinChanShopProducer { _, _, _ ->
                shopCalls++
                JinChanPerceptionResult.Unknown(source, "unused")
            },
        )

        val result = adapter.perceive(frame, source, levelExpRoi(), goldRoi(), shopRoi())

        assertEquals(0, shopCalls)
        assertEquals(JinChanPerceptionStatus.UNKNOWN, result.shop.status)
        assertEquals(JinChanH4aMigrationAdapter.SHOP_GATE_SOURCE_PENDING, (result.shop as JinChanPerceptionResult.Unknown).reason)
        assertSame(source, result.hud.level.source)
        assertSame(source, result.hud.exp.source)
        assertSame(source, result.hud.gold.source)
        assertSame(source, result.shop.source)
    }

    @Test
    fun closedGateIsNotApplicableAndOpenGateRunsShopExactlyOnce() = withFrame { frame, source ->
        var calls = 0
        val shop = JinChanShopProducer { seenFrame, _, _ ->
            assertSame(frame, seenFrame)
            calls++
            JinChanPerceptionResult.Available(ShopObservation(listOf(JinChanShopSlot(0, JinChanShopSlotStatus.UNKNOWN))), 1.0, source)
        }
        val closed = JinChanH4aMigrationAdapter(recordingHud(frame), JinChanShopGate { _, _ -> JinChanShopGateInput(true, "BOARD") }, shop)
        assertEquals(JinChanPerceptionStatus.NOT_APPLICABLE, closed.perceive(frame, source, levelExpRoi(), goldRoi(), shopRoi()).shop.status)
        assertEquals(0, calls)

        val open = JinChanH4aMigrationAdapter(recordingHud(frame), JinChanShopGate { _, _ -> JinChanShopGateInput(true, JinChanH4aMigrationAdapter.SHOP_OPEN) }, shop)
        val result = open.perceive(frame, source, levelExpRoi(), goldRoi(), shopRoi())
        assertEquals(1, calls)
        assertEquals(JinChanPerceptionStatus.AVAILABLE, result.shop.status)
        val slot = (result.shop as JinChanPerceptionResult.Available).payload.slots.single()
        assertEquals(JinChanShopSlotStatus.UNKNOWN, slot.status)
        assertEquals(null, slot.heroKey)
    }

    @Test
    fun individualProducerErrorsFailClosed() = withFrame { frame, source ->
        val adapter = JinChanH4aMigrationAdapter(
            hudProducer = JinChanHudProducer { _, _, _, _ -> error("hud") },
            shopGate = JinChanShopGate { _, _ -> JinChanShopGateInput(true, JinChanH4aMigrationAdapter.SHOP_OPEN) },
            shopProducer = JinChanShopProducer { _, _, _ -> error("shop") },
        )
        val result = adapter.perceive(frame, source, levelExpRoi(), goldRoi(), shopRoi())
        assertEquals(JinChanPerceptionStatus.INVALID, result.hud.level.status)
        assertEquals(JinChanPerceptionStatus.INVALID, result.hud.exp.status)
        assertEquals(JinChanPerceptionStatus.INVALID, result.hud.gold.status)
        assertEquals(JinChanPerceptionStatus.INVALID, result.shop.status)
    }

    @Test
    fun durableContractContainsNoPixelOrLeaseReferences() {
        val modelClasses = listOf(
            JinChanSourceFrame::class.java,
            LevelObservation::class.java,
            ExpObservation::class.java,
            GoldObservation::class.java,
            ShopObservation::class.java,
            JinChanShopSlot::class.java,
        )
        val forbidden = setOf(IntArray::class.java, CapturedFrame::class.java, JinChanCanonicalFrame::class.java)
        assertFalse(modelClasses.any { model -> model.declaredFields.any { it.type in forbidden } })
    }

    private fun recordingHud(expected: JinChanCanonicalFrame) = JinChanHudProducer { frame, source, _, _ ->
        assertSame(expected, frame)
        JinChanHudResult(
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
        )
    }

    private fun withFrame(block: (JinChanCanonicalFrame, JinChanSourceFrame) -> Unit) {
        val captured = CapturedFrame(41, 42, 3_120, 1_440, IntArray(3_120 * 1_440))
        captured.use {
            val bridged = JinChanFrameBridge.adapt(captured)
            assertTrue(bridged is JinChanFrameBridgeResult.Valid)
            val frame = (bridged as JinChanFrameBridgeResult.Valid).frame
            block(frame, JinChanSourceFrame(JinChanFrameSessionId(7), frame.sourceSequence, frame.sourceElapsedRealtimeNanos))
        }
    }

    private fun levelExpRoi(): FrameRect = JinChanRoiRegistry.resolveCanonical(JinChanRoiId.LEVEL_EXP)
    private fun goldRoi(): FrameRect = JinChanRoiRegistry.resolveCanonical(JinChanRoiId.GOLD)
    private fun shopRoi(): FrameRect = JinChanRoiRegistry.resolveCanonical(JinChanRoiId.SHOP)
}
