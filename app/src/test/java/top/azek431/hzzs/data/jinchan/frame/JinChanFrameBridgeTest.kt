package top.azek431.hzzs.data.jinchan.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanFrameBridgeTest {
    @Test
    fun canonicalLandscapeUsesIdentityMappingAndBorrowsPixels() {
        val source = frame(width = 3_120, height = 1_440, sequence = 42, timestamp = 987_654)
        val canonical = source.adaptValid()

        assertEquals(FramePoint(1_000.0, 700.0), canonical.sourceToCanonical(FramePoint(1_000.0, 700.0)))
        assertSame(source.pixels, canonical.pixels)
        assertEquals(42, canonical.sourceSequence)
        assertEquals(987_654, canonical.sourceElapsedRealtimeNanos)
        assertEquals(3_120, canonical.canonicalWidth)
        assertEquals(1_440, canonical.canonicalHeight)
    }

    @Test
    fun landscapeScalingMapsBothDirections() {
        val canonical = frame(1_560, 720).adaptValid()

        assertEquals(FramePoint(3_120.0, 1_440.0), canonical.sourceToCanonical(FramePoint(1_560.0, 720.0)))
        assertEquals(FramePoint(780.0, 360.0), canonical.canonicalToSource(FramePoint(1_560.0, 720.0)))
    }

    @Test
    fun portraitWithClockwiseRotationMapsAndRoundTrips() {
        val canonical = frame(1_440, 3_120, rotation = 90).adaptValid()
        val sourcePoint = FramePoint(360.0, 780.0)

        val mapped = requireNotNull(canonical.sourceToCanonical(sourcePoint))
        assertEquals(FramePoint(2_340.0, 360.0), mapped)
        assertEquals(sourcePoint, canonical.canonicalToSource(mapped))
    }

    @Test
    fun portraitWithoutRotationAndUnknownRotationFailClosed() {
        assertRejected(frame(1_440, 3_120), JinChanFrameStatus.UNSUPPORTED_ORIENTATION)
        assertRejected(frame(1_440, 3_120, rotation = 45), JinChanFrameStatus.UNSUPPORTED_ROTATION)
    }

    @Test
    fun capturedFrameRejectsInvalidDimensionsBeforeBridgeEntry() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturedFrame(1, 2, 0, 1, IntArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapturedFrame(1, 2, 2, 2, IntArray(3))
        }
    }

    @Test
    fun pointsAndRectanglesOutsideTheirCoordinateSpaceAreRejected() {
        val canonical = frame(1_560, 720).adaptValid()

        assertNull(canonical.sourceToCanonical(FramePoint(-0.1, 0.0)))
        assertNull(canonical.canonicalToSource(FramePoint(3_120.1, 0.0)))
        assertNull(canonical.sourceToCanonical(FrameRect(100.0, 100.0, 99.0, 200.0)))
        assertEquals(
            FrameRect(200.0, 200.0, 400.0, 400.0),
            canonical.sourceToCanonical(FrameRect(100.0, 100.0, 200.0, 200.0)),
        )
    }

    @Test
    fun bridgeDoesNotCloseOrCopyTheCaptureLease() {
        var releases = 0
        val source = frame(4, 2, release = { releases++ })
        val first = source.adaptValid()
        val second = source.adaptValid()

        assertSame(source.pixels, first.pixels)
        assertSame(first.pixels, second.pixels)
        assertEquals(0, releases)
        source.close()
        assertEquals(1, releases)
    }

    private fun CapturedFrame.adaptValid(): JinChanCanonicalFrame {
        val result = JinChanFrameBridge.adapt(this)
        assertTrue(result is JinChanFrameBridgeResult.Valid)
        return (result as JinChanFrameBridgeResult.Valid).frame
    }

    private fun assertRejected(source: CapturedFrame, expected: JinChanFrameStatus) {
        assertEquals(JinChanFrameBridgeResult.Rejected(expected), JinChanFrameBridge.adapt(source))
    }

    private fun frame(
        width: Int,
        height: Int,
        rotation: Int = 0,
        sequence: Long = 1,
        timestamp: Long = 2,
        release: (() -> Unit)? = null,
    ) = CapturedFrame(
        sequence = sequence,
        elapsedRealtimeNanos = timestamp,
        width = width,
        height = height,
        pixels = IntArray(width * height),
        rotationDegrees = rotation,
        releaseLease = release,
    )
}
