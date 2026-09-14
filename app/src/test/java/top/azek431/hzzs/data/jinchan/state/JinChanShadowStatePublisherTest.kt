package top.azek431.hzzs.data.jinchan.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiId
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiRegistry
import top.azek431.hzzs.data.jinchan.perception.*
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanShadowStatePublisherTest {
    @Test
    fun publishesSameFrameMetadataAndAllRequiredUnknownRois() {
        val publisher = JinChanShadowStatePublisher()
        val session = publisher.startSession()
        val source = frame(123, 10_000, 3_120, 1_440)
        val seenFrames = mutableListOf<JinChanCanonicalFrame>()
        val seenIds = mutableListOf<JinChanRoiId>()
        var clock = 20_000L

        val state = source.use {
            publisher.publishFrame(
                session,
                source,
                10_010,
                100,
                nanoTime = { clock++ },
                roiResolver = { id, canonical ->
                    seenIds += id
                    seenFrames += canonical
                    JinChanRoiRegistry.resolveCanonical(id) to JinChanRoiRegistry.resolveSource(id, canonical)
                },
            )
        }

        requireNotNull(state)
        assertEquals(session, state.sessionId)
        assertEquals(source.sequence, state.frameSeq)
        assertEquals(10_000L, state.timestampElapsedRealtimeNanos)
        assertEquals(JinChanOrientation(3_120, 1_440, 0, 3_120, 1_440), state.orientation)
        assertEquals(listOf(JinChanRoiId.SHOP, JinChanRoiId.GOLD, JinChanRoiId.LEVEL_EXP, JinChanRoiId.BOARD, JinChanRoiId.BENCH), seenIds)
        assertEquals(5, seenFrames.size)
        seenFrames.forEach { assertSame(seenFrames.first(), it) }
        listOf(state.shop, state.gold, state.level, state.exp).forEach {
            assertEquals(ShadowFieldStatus.UNKNOWN, it.status)
            assertTrue(it.canonicalRoi != null)
            assertTrue(it.sourceRoi != null)
        }
        assertSame(state, publisher.state.value)
        assertTrue(state.timing.captureTimestamp == source.elapsedRealtimeNanos)
        listOf(state.timing.bridgeNs, state.timing.roiResolveNs, state.timing.shadowPublishNs, state.timing.totalShadowNs).forEach { assertTrue(it >= 0) }
    }

    @Test
    fun rejectsOutOfOrderStaleWrongAndStoppedSessions() {
        val publisher = JinChanShadowStatePublisher()
        val first = publisher.startSession()
        assertTrue(publisher.publishFrame(first, frame(2, 100), 100, 10) != null)
        assertNull(publisher.publishFrame(first, frame(1, 101), 101, 10))
        assertEquals(2L, publisher.state.value?.frameSeq)
        assertNull(publisher.publishFrame(first, frame(3, 100), 111, 10))

        publisher.stopSession(first)
        assertNull(publisher.state.value)
        assertNull(publisher.publishFrame(first, frame(4, 120), 120, 10))
        val second = publisher.startSession()
        assertTrue(second != first)
        assertNull(publisher.publishFrame(first, frame(5, 130), 130, 10))
        assertNull(publisher.state.value)
        assertTrue(publisher.publishFrame(second, frame(6, 140), 140, 10) != null)
    }

    @Test
    fun invalidBridgeAndRoiFailClosedWithoutFakeObservations() {
        val publisher = JinChanShadowStatePublisher()
        val session = publisher.startSession()
        assertNull(publisher.publishFrame(session, frame(1, 1, 1_440, 3_120), 1, 10))
        assertNull(publisher.state.value)

        val state = publisher.publishFrame(session, frame(2, 2), 2, 10, roiResolver = { id, canonical ->
            val roi = JinChanRoiRegistry.resolveCanonical(id)
            roi to if (id == JinChanRoiId.BOARD) null else canonical.canonicalToSource(roi)
        })
        requireNotNull(state)
        assertEquals(ShadowFieldStatus.INVALID, state.board.status)
        assertEquals("ROI_BOARD_UNMAPPABLE", state.board.reason)
        listOf(state.shop, state.gold, state.level, state.exp).forEach { assertEquals(ShadowFieldStatus.UNKNOWN, it.status) }
        assertEquals(ShadowFieldStatus.UNKNOWN, state.bench.status)
    }

    @Test
    fun publishedGraphContainsNoLeasePixelsOrActionReferences() {
        val forbidden = setOf(IntArray::class.java, CapturedFrame::class.java, JinChanCanonicalFrame::class.java)
        val modelClasses = listOf(JinChanShadowState::class.java, JinChanShadowObservation::class.java, JinChanTypedShadowObservation::class.java, JinChanOrientation::class.java, JinChanShadowTiming::class.java, BoardOccupancyObservation::class.java, BoardCellObservation::class.java)
        modelClasses.forEach { type -> assertFalse(type.declaredFields.any { it.type in forbidden }) }
        val names = modelClasses.flatMap { type -> type.declaredFields.map { it.type.name } }
        assertFalse(names.any { it.contains("Action", ignoreCase = true) || it.contains("Gesture", ignoreCase = true) })
    }

    @Test
    fun hudAndShopSharePublishedFrameAndBoardBenchStayUnknown() {
        val publisher = JinChanShadowStatePublisher()
        val session = publisher.startSession()
        val source = frame(77, 1_000)
        val seenSequences = mutableListOf<Long>()
        val texts = ArrayDeque(listOf("8", "4/12", "20", "金蝉", "金蝉", "金蝉", "金蝉", "金蝉"))
        val state = publisher.publishFrame(
            session, source, 1_000, 10,
            ocrReader = JinChanOcrReader { canonical, _ ->
                seenSequences += canonical.sourceSequence
                JinChanOcrResult(JinChanOcrStatus.VALID, texts.removeFirst(), .9)
            },
            stableState = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN),
            heroResolver = JinChanHeroIdentityResolver { ResolvedHero(it, it) },
        )
        requireNotNull(state)
        assertTrue(seenSequences.all { it == 77L })
        assertEquals(77L, state.shop.value?.frameSeq)
        assertEquals(ShadowFieldStatus.AVAILABLE, state.shop.status)
        assertEquals(ShadowFieldStatus.UNKNOWN, state.board.status)
        assertEquals(ShadowFieldStatus.UNKNOWN, state.bench.status)
    }

    @Test
    fun heldBoardKeepsTrustedCellsButUsesCurrentFrameIdentity() {
        val publisher = JinChanShadowStatePublisher()
        val session = publisher.startSession()
        val trustedPixels = IntArray(3_120 * 1_440) { 0xff828282.toInt() }

        val first = publisher.publishFrame(
            session,
            CapturedFrame(100, 1_000, 3_120, 1_440, trustedPixels.copyOf()),
            1_000,
            100,
            stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT),
        )
        requireNotNull(first)
        val firstBoard = requireNotNull(first.board.value)
        assertEquals(ShadowFieldStatus.AVAILABLE, first.board.status)
        assertEquals(100L, firstBoard.frameSeq)
        assertEquals(BoardSnapshotDecision.SET, firstBoard.decision)
        assertEquals(28, firstBoard.cells.size)

        val second = publisher.publishFrame(
            session,
            CapturedFrame(101, 1_001, 3_120, 1_440, trustedPixels.copyOf()),
            1_001,
            100,
            stableState = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN),
        )
        requireNotNull(second)
        val heldBoard = requireNotNull(second.board.value)
        assertEquals(101L, second.frameSeq)
        assertEquals(ShadowFieldStatus.AVAILABLE, second.board.status)
        assertEquals(101L, heldBoard.frameSeq)
        assertEquals(BoardSnapshotDecision.HOLD, heldBoard.decision)
        assertEquals("SCENE_NOT_ELIGIBLE", heldBoard.reason)
        assertEquals(firstBoard.cells, heldBoard.cells)
        assertEquals(firstBoard.occupiedCount, heldBoard.occupiedCount)
        assertEquals(100L, firstBoard.frameSeq)
        assertEquals(BoardSnapshotDecision.SET, firstBoard.decision)
    }

    private fun frame(sequence: Long, timestamp: Long, width: Int = 3_120, height: Int = 1_440) = CapturedFrame(
        sequence = sequence,
        elapsedRealtimeNanos = timestamp,
        width = width,
        height = height,
        pixels = IntArray(width * height),
    )
}
