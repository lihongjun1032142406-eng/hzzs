package top.azek431.hzzs.data.jinchan.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanBoardOccupancyTest {
    @Test fun frozenGeometryIsStableAndComplete() {
        val cells = JinChanBoardGeometry.anchors
        assertEquals(28, cells.size)
        assertEquals((1..4).flatMap { r -> (1..7).map { c -> "R${r}C$c" } }, cells.map { it.cellId })
        assertEquals(JinChanBoardGeometry.Anchor(1, 1, "R1C1", 1012, 660), cells.first())
        assertEquals(JinChanBoardGeometry.Anchor(4, 7, "R4C7", 2094, 1033), cells.last())
        assertEquals("HEX_V2_FROZEN", JinChanBoardV8Model.GEOMETRY)
        assertEquals(120, JinChanBoardV8Model.featureOrder.size)
        assertEquals(120, JinChanBoardV8Model.scalerMean.size)
        assertEquals(120, JinChanBoardV8Model.scalerScale.size)
        assertEquals(120, JinChanBoardV8Model.coefficients.size)
    }

    @Test fun frozenScorerMatchesReferenceFixtures() {
        assertEquals(0.023535051080138494, JinChanBoardV8Model.probability(DoubleArray(120))!!, 1e-15)
        assertEquals(0.1486329517049704, JinChanBoardV8Model.probability(JinChanBoardV8Model.scalerMean.copyOf())!!, 1e-15)
    }

    @Test fun thresholdBoundaryIsInclusiveAndUnknownIsNotEmpty() {
        assertFalse(JinChanBoardV8Model.occupied(Math.nextDown(.65))!!)
        assertTrue(JinChanBoardV8Model.occupied(.65)!!)
        assertNull(JinChanBoardV8Model.occupied(null))
        assertNull(JinChanBoardV8Model.probability(DoubleArray(119)))
        assertNull(JinChanBoardV8Model.probability(DoubleArray(120) { Double.NaN }))
    }

    @Test fun sceneGatePreservesFrozenBoundaries() {
        assertEquals("EVIDENCE_MISSING", JinChanBoardSceneTruthGate.evaluate(BoardSceneEvidence(null, 0.0, 0.0)).reason)
        assertEquals("COMBAT_LIKE", JinChanBoardSceneTruthGate.evaluate(BoardSceneEvidence(.10, 0.0, 0.0)).reason)
        assertEquals("PANEL_LIKE", JinChanBoardSceneTruthGate.evaluate(BoardSceneEvidence(.09, .25, 0.0)).reason)
        assertEquals("PLANNING_CANDIDATE_LOW_SHOP_SIGNAL", JinChanBoardSceneTruthGate.evaluate(BoardSceneEvidence(.09, .24, .029)).reason)
        assertEquals("PLANNING_CANDIDATE", JinChanBoardSceneTruthGate.evaluate(BoardSceneEvidence(.09, .24, .03)).reason)
    }

    @Test fun snapshotPolicyIsSetHoldClear() {
        assertEquals(BoardSnapshotDecision.CLEAR, JinChanBoardSceneTruthGate.decide(false, true, true))
        assertEquals(BoardSnapshotDecision.HOLD, JinChanBoardSceneTruthGate.decide(true, false, true))
        assertEquals(BoardSnapshotDecision.HOLD, JinChanBoardSceneTruthGate.decide(true, true, false))
        assertEquals(BoardSnapshotDecision.SET, JinChanBoardSceneTruthGate.decide(true, true, true))
    }

    @Test fun guardsPreserveFrozenPolarityAndFailClosedDimensions() {
        val clean = IntArray(3120 * 1440) { 0xff828282.toInt() }
        assertFalse(JinChanBoardOccupancyProducer.bannerDetected(clean, 3120)!!)
        assertFalse(JinChanBoardOccupancyProducer.damagePanelDetected(clean, 3120)!!)
        for (y in 830 until 837) for (x in 850 until 2300) clean[y * 3120 + x] = 0xff000000.toInt()
        for (y in 837 until 845) for (x in 850 until 2300) clean[y * 3120 + x] = 0xffffffff.toInt()
        assertTrue(JinChanBoardOccupancyProducer.bannerDetected(clean, 3120)!!)
        val dark = IntArray(3120 * 1440)
        assertTrue(JinChanBoardOccupancyProducer.damagePanelDetected(dark, 3120)!!)
        assertNull(JinChanBoardOccupancyProducer.bannerDetected(IntArray(1), 1))
        assertNull(JinChanBoardOccupancyProducer.damagePanelDetected(IntArray(1), 1))
    }

    @Test fun producerEmitsAllCellsWithSameFrameSequence() {
        val source = CapturedFrame(42, 100, 3120, 1440, IntArray(3120 * 1440) { 0xff828282.toInt() })
        val canonical = (JinChanFrameBridge.adapt(source) as JinChanFrameBridgeResult.Valid).frame
        val result = JinChanBoardOccupancyProducer.evaluate(canonical, JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT))
        assertEquals(BoardObservationStatus.AVAILABLE, result.status)
        assertEquals(BoardSnapshotDecision.SET, result.decision)
        assertEquals(42, result.frameSeq)
        assertEquals(28, result.cells.size)
        assertTrue(result.cells.all { it.probability != null && it.occupied != null })
    }
}
