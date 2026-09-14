package top.azek431.hzzs.data.jinchan.roi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanRoiRegistryTest {
    private val expected = linkedMapOf(
        JinChanRoiId.STAGE to NormalizedRect(0.30, 0.00, 0.40, 0.10),
        JinChanRoiId.LEVEL_EXP to NormalizedRect(0.00, 0.68, 0.16, 0.32),
        JinChanRoiId.GOLD to NormalizedRect(0.86, 0.70, 0.14, 0.30),
        JinChanRoiId.BOARD to NormalizedRect(0.17, 0.10, 0.66, 0.70),
        JinChanRoiId.SHOP to NormalizedRect(0.15, 0.55, 0.68, 0.40),
        JinChanRoiId.PLAYER_LIST to NormalizedRect(0.84, 0.04, 0.16, 0.76),
        JinChanRoiId.PANEL to NormalizedRect(0.68, 0.10, 0.30, 0.72),
        JinChanRoiId.SPECIAL to NormalizedRect(0.18, 0.12, 0.64, 0.72),
        JinChanRoiId.BENCH to NormalizedRect(0.20, 0.78, 0.62, 0.20),
        JinChanRoiId.PLAY_BTN to NormalizedRect(0.82, 0.72, 0.16, 0.26),
    )

    @Test
    fun registryContainsExactlyTheTenFrozenDefinitionsWithoutDuplicates() {
        assertEquals(10, JinChanRoiRegistry.ids.size)
        assertEquals(expected.keys.toList(), JinChanRoiRegistry.ids)
        assertEquals(JinChanRoiRegistry.ids.size, JinChanRoiRegistry.ids.toSet().size)
        expected.forEach { (id, rect) -> assertEquals(rect, JinChanRoiRegistry.getNormalized(id)) }
        assertEquals("JINCHAN_ROI_V1", JinChanRoiRegistry.VERSION)
    }

    @Test
    fun normalizedRectRejectsNonFiniteNegativeOverflowAndZeroSize() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertInvalid { NormalizedRect(invalid, 0.0, 0.1, 0.1) }
            assertInvalid { NormalizedRect(0.0, invalid, 0.1, 0.1) }
            assertInvalid { NormalizedRect(0.0, 0.0, invalid, 0.1) }
            assertInvalid { NormalizedRect(0.0, 0.0, 0.1, invalid) }
        }
        assertInvalid { NormalizedRect(-0.01, 0.0, 0.1, 0.1) }
        assertInvalid { NormalizedRect(0.0, -0.01, 0.1, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.0, -0.1, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.0, 0.1, -0.1) }
        assertInvalid { NormalizedRect(1.01, 0.0, 0.1, 0.1) }
        assertInvalid { NormalizedRect(0.0, 1.01, 0.1, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.0, 1.01, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.0, 0.1, 1.01) }
        assertInvalid { NormalizedRect(0.9, 0.0, 0.11, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.9, 0.1, 0.11) }
        assertInvalid { NormalizedRect(0.0, 0.0, 0.0, 0.1) }
        assertInvalid { NormalizedRect(0.0, 0.0, 0.1, 0.0) }
    }

    @Test
    fun canonicalGeometryUsesTheFrozenH1Dimensions() {
        assertEquals(3_120, JinChanFrameBridge.CANONICAL_WIDTH)
        assertEquals(1_440, JinChanFrameBridge.CANONICAL_HEIGHT)
        assertRect(FrameRect(530.4, 144.0, 2_589.6, 1_152.0), JinChanRoiRegistry.resolveCanonical(JinChanRoiId.BOARD))
        assertRect(FrameRect(468.0, 792.0, 2_589.6, 1_368.0), JinChanRoiRegistry.resolveCanonical(JinChanRoiId.SHOP))
        assertRect(FrameRect(624.0, 1_123.2, 2_558.4, 1_411.2), JinChanRoiRegistry.resolveCanonical(JinChanRoiId.BENCH))
    }

    @Test
    fun sourceResolutionDelegatesToH1ForScalingAndEverySupportedRotation() {
        listOf(
            frame(1_560, 720, 0),
            frame(720, 1_560, 90),
            frame(1_560, 720, 180),
            frame(720, 1_560, 270),
        ).forEach { captured ->
            captured.use {
                val canonical = captured.adaptValid()
                val canonicalRoi = JinChanRoiRegistry.resolveCanonical(JinChanRoiId.BOARD)
                val expectedSource = requireNotNull(canonical.canonicalToSource(canonicalRoi))
                val resolvedSource = requireNotNull(JinChanRoiRegistry.resolveSource(JinChanRoiId.BOARD, canonical))
                assertRect(expectedSource, resolvedSource)
                assertRect(canonicalRoi, requireNotNull(canonical.sourceToCanonical(resolvedSource)))
            }
        }
    }

    @Test
    fun repeatedLookupIsDeterministicAndRegistryStoresNoFrameOrPixels() {
        val first = JinChanRoiRegistry.getNormalized(JinChanRoiId.SHOP)
        repeat(20) {
            assertSame(first, JinChanRoiRegistry.getNormalized(JinChanRoiId.SHOP))
            assertEquals(
                JinChanRoiRegistry.resolveCanonical(JinChanRoiId.SHOP),
                JinChanRoiRegistry.resolveCanonical(JinChanRoiId.SHOP),
            )
        }
        val fieldTypes = JinChanRoiRegistry::class.java.declaredFields.map { it.type }
        assertFalse(fieldTypes.contains(IntArray::class.java))
        assertFalse(fieldTypes.contains(CapturedFrame::class.java))
        assertFalse(fieldTypes.contains(JinChanCanonicalFrame::class.java))
    }

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    private fun assertRect(expected: FrameRect, actual: FrameRect) {
        assertEquals(expected.left, actual.left, 1e-9)
        assertEquals(expected.top, actual.top, 1e-9)
        assertEquals(expected.right, actual.right, 1e-9)
        assertEquals(expected.bottom, actual.bottom, 1e-9)
    }

    private fun CapturedFrame.adaptValid(): JinChanCanonicalFrame {
        val result = JinChanFrameBridge.adapt(this)
        assertTrue(result is JinChanFrameBridgeResult.Valid)
        return (result as JinChanFrameBridgeResult.Valid).frame
    }

    private fun frame(width: Int, height: Int, rotation: Int) = CapturedFrame(
        sequence = 1,
        elapsedRealtimeNanos = 1,
        width = width,
        height = height,
        pixels = IntArray(width * height),
        rotationDegrees = rotation,
    )
}
