package top.azek431.hzzs.data.jinchan.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.service.capture.CapturedFrame
import top.azek431.hzzs.service.capture.IntFramePool

class JinChanFrameRuntimeTest {
    private val timeout = 100L

    @Test
    fun sessionStartAcceptsFrameAndPreservesIdentity() {
        val runtime = JinChanFrameRuntime()
        val session = runtime.startSession()

        val accepted = runtime.accept(session, frame(7, 1_000), 1_050, timeout)

        assertEquals(JinChanFrameRuntimeStatus.VALID, accepted.status)
        assertEquals(session, accepted.metadata?.sessionId)
        assertEquals(7L, accepted.metadata?.frameId)
        assertEquals(7L, accepted.metadata?.sourceSequence)
        assertEquals(1_000L, accepted.metadata?.timestampElapsedRealtimeNanos)
    }

    @Test
    fun monotonicallyNewerFrameReplacesOlderAndOutOfOrderIsRejected() {
        val runtime = JinChanFrameRuntime()
        val session = runtime.startSession()
        runtime.accept(session, frame(7, 1_000), 1_010, timeout)

        assertEquals(
            JinChanFrameRuntimeStatus.OUT_OF_ORDER,
            runtime.accept(session, frame(6, 1_020), 1_030, timeout).status,
        )
        assertEquals(7L, runtime.latest(session, 1_030, timeout).metadata?.frameId)
        assertEquals(
            JinChanFrameRuntimeStatus.VALID,
            runtime.accept(session, frame(8, 1_040), 1_050, timeout).status,
        )
        assertEquals(8L, runtime.latest(session, 1_050, timeout).metadata?.frameId)
    }

    @Test
    fun staleFrameIsRejectedAndLatestExpiresUsingInjectedTimeout() {
        val runtime = JinChanFrameRuntime()
        val session = runtime.startSession()

        assertEquals(JinChanFrameRuntimeStatus.STALE, runtime.accept(session, frame(1, 1_000), 1_101, timeout).status)
        runtime.accept(session, frame(2, 1_100), 1_100, timeout)
        assertEquals(JinChanFrameRuntimeStatus.STALE, runtime.latest(session, 1_201, timeout).status)
        assertEquals(JinChanFrameRuntimeStatus.EMPTY, runtime.latest(session, 1_201, timeout).status)
    }

    @Test
    fun stopAndResetClearLatestMetadata() {
        val runtime = JinChanFrameRuntime()
        val first = runtime.startSession()
        runtime.accept(first, frame(1, 1_000), 1_000, timeout)

        assertEquals(JinChanFrameRuntimeStatus.EMPTY, runtime.resetSession(first))
        assertEquals(JinChanFrameRuntimeStatus.EMPTY, runtime.latest(first, 1_000, timeout).status)
        runtime.accept(first, frame(2, 1_000), 1_000, timeout)
        assertEquals(JinChanFrameRuntimeStatus.INACTIVE, runtime.stopSession(first))
        assertEquals(JinChanFrameRuntimeStatus.INACTIVE, runtime.latest(first, 1_000, timeout).status)
    }

    @Test
    fun previousSessionCannotPublishOrReadFrames() {
        val runtime = JinChanFrameRuntime()
        val previous = runtime.startSession()
        runtime.accept(previous, frame(1, 1_000), 1_000, timeout)
        val current = runtime.startSession()

        assertTrue(current.value > previous.value)
        assertEquals(JinChanFrameRuntimeStatus.WRONG_SESSION, runtime.accept(previous, frame(2, 1_000), 1_000, timeout).status)
        assertEquals(JinChanFrameRuntimeStatus.WRONG_SESSION, runtime.latest(previous, 1_000, timeout).status)
        assertEquals(JinChanFrameRuntimeStatus.EMPTY, runtime.latest(current, 1_000, timeout).status)
    }

    @Test
    fun latestMetadataCannotRetainAClosedPooledPixelBuffer() {
        val runtime = JinChanFrameRuntime()
        val session = runtime.startSession()
        val pool = IntFramePool(capacity = 2)
        val lease = requireNotNull(pool.tryAcquire(8))
        lease.pixels[0] = 123
        val source = CapturedFrame(1, 1_000, 4, 2, lease.pixels, releaseLease = lease::close)

        runtime.accept(session, source, 1_000, timeout)
        source.close()
        val reused = requireNotNull(pool.tryAcquire(8))
        reused.pixels[0] = 999
        val metadata = runtime.latest(session, 1_000, timeout).metadata

        assertEquals(1L, metadata?.frameId)
        assertFalse(JinChanLatestFrameMetadata::class.java.declaredFields.any { it.type == IntArray::class.java })
        assertNull(JinChanFrameRuntimeResult::class.java.declaredFields.firstOrNull { it.type == IntArray::class.java })
        reused.close()
    }

    private fun frame(sequence: Long, timestamp: Long) = CapturedFrame(
        sequence = sequence,
        elapsedRealtimeNanos = timestamp,
        width = 4,
        height = 2,
        pixels = IntArray(8),
    )
}
