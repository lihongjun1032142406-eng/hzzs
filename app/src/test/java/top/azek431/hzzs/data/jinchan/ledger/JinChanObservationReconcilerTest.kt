package top.azek431.hzzs.data.jinchan.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.perception.BenchObservation
import top.azek431.hzzs.data.jinchan.perception.BenchObservationStatus
import top.azek431.hzzs.data.jinchan.perception.BenchSlotObservation
import top.azek431.hzzs.data.jinchan.perception.BenchSlotState
import top.azek431.hzzs.data.jinchan.perception.BoardCellObservation
import top.azek431.hzzs.data.jinchan.perception.BoardOccupancyObservation
import top.azek431.hzzs.data.jinchan.perception.BoardObservationStatus
import top.azek431.hzzs.data.jinchan.perception.BoardSnapshotDecision
import top.azek431.hzzs.data.jinchan.perception.ResolvedHero
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanObservationReconcilerTest {
    @Test
    fun initializesThenReportsNoChangeWithoutMutation() {
        val fixture = Fixture()
        assertEquals(ObservationReconcileStatus.INIT, fixture.apply(1, emptySet(), emptyMap()).status)
        assertEquals(ObservationReconcileStatus.NO_CHANGE, fixture.apply(2, emptySet(), emptyMap()).status)
        assertEquals(0L, fixture.ledger.currentRevision())
    }

    @Test
    fun uniqueBoardToBoardPreservesUidAndDoesNotCreate() {
        val fixture = Fixture()
        val uid = fixture.create("garen", UnitLocation.Board(1, 1))
        fixture.apply(1, setOf(1 to 1), emptyMap())
        val result = fixture.apply(2, setOf(2 to 3), emptyMap())
        fixture.assertMove(result, uid, UnitLocation.Board(2, 3))
    }

    @Test
    fun uniqueBenchToBoardPreservesUid() {
        val fixture = Fixture()
        val uid = fixture.create("garen", UnitLocation.Bench(3))
        fixture.apply(1, emptySet(), mapOf(3 to "garen"))
        val result = fixture.apply(2, setOf(2 to 4), emptyMap())
        fixture.assertMove(result, uid, UnitLocation.Board(2, 4))
    }

    @Test
    fun uniqueBoardToBenchPreservesUidAndValidatesIdentity() {
        val fixture = Fixture()
        val uid = fixture.create("garen", UnitLocation.Board(2, 4))
        fixture.apply(1, setOf(2 to 4), emptyMap())
        fixture.assertMove(fixture.apply(2, emptySet(), mapOf(3 to "garen")), uid, UnitLocation.Bench(3))

        val mismatch = Fixture()
        mismatch.create("garen", UnitLocation.Board(1, 1))
        mismatch.apply(1, setOf(1 to 1), emptyMap())
        mismatch.assertRejected(mismatch.apply(2, emptySet(), mapOf(0 to "ahri")), "DESTINATION_HERO_CONFLICT")
    }

    @Test
    fun uniqueBenchToBenchPreservesUid() {
        val fixture = Fixture()
        val uid = fixture.create("garen", UnitLocation.Bench(1))
        fixture.apply(1, emptySet(), mapOf(1 to "garen"))
        fixture.assertMove(fixture.apply(2, emptySet(), mapOf(7 to "garen")), uid, UnitLocation.Bench(7))
    }

    @Test
    fun ambiguousMultiDeltaAndUnknownSourceFailWithoutRevision() {
        val ambiguous = Fixture()
        ambiguous.apply(1, setOf(1 to 1, 1 to 2), emptyMap())
        ambiguous.assertRejected(ambiguous.apply(2, setOf(2 to 1, 2 to 2), emptyMap()), "AMBIGUOUS_OR_UNSUPPORTED_DELTA")

        val missing = Fixture()
        missing.apply(1, setOf(1 to 1), emptyMap())
        missing.assertRejected(missing.apply(2, setOf(2 to 1), emptyMap()), "SOURCE_UID_NOT_UNIQUE")
    }

    @Test
    fun destinationConflictFailsWithoutRevision() {
        val fixture = Fixture()
        fixture.create("garen", UnitLocation.Board(1, 1))
        fixture.create("ahri", UnitLocation.Board(2, 2))
        fixture.apply(1, setOf(1 to 1), emptyMap())
        fixture.assertRejected(fixture.apply(2, setOf(2 to 2), emptyMap()), "DESTINATION_UID_CONFLICT")
    }

    @Test
    fun partialUnknownAndInvalidBenchFailClosed() {
        listOf(
            bench(1, emptyMap(), BenchObservationStatus.PARTIAL),
            bench(1, emptyMap(), BenchObservationStatus.UNAVAILABLE),
            bench(1, emptyMap(), BenchObservationStatus.INVALID),
            bench(1, emptyMap()).copy(slots = bench(1, emptyMap()).slots.mapIndexed { index, slot ->
                if (index == 4) slot.copy(state = BenchSlotState.UNKNOWN) else slot
            }),
        ).forEach { observation ->
            val ledger = JinChanUnitLedger()
            val result = JinChanObservationReconciler().reconcile(null, observation, ledger)
            assertEquals(ObservationReconcileStatus.INPUT_UNAVAILABLE, result.status)
            assertEquals(0L, ledger.currentRevision())
        }
    }

    @Test
    fun holdNullCellAndMalformedDuplicatesFailClosed() {
        val good = board(1, emptySet())
        val inputs = listOf(
            good.copy(decision = BoardSnapshotDecision.HOLD),
            good.copy(cells = good.cells.mapIndexed { index, cell -> if (index == 0) cell.copy(occupied = null) else cell }),
            good.copy(cells = good.cells.toMutableList().also { it[1] = it[0] }),
        )
        inputs.forEach { observation ->
            val ledger = JinChanUnitLedger()
            val result = JinChanObservationReconciler().reconcile(observation, null, ledger)
            assertEquals(ObservationReconcileStatus.INPUT_UNAVAILABLE, result.status)
            assertEquals(0L, ledger.currentRevision())
        }
        val duplicateBench = bench(1, emptyMap()).let { it.copy(slots = it.slots.toMutableList().also { slots -> slots[1] = slots[0] }) }
        assertEquals(
            ObservationReconcileStatus.INPUT_UNAVAILABLE,
            JinChanObservationReconciler().reconcile(null, duplicateBench, JinChanUnitLedger()).status,
        )
    }

    @Test
    fun frameMismatchAndStaleOrDuplicateFailClosed() {
        val fixture = Fixture()
        fixture.create("garen", UnitLocation.Bench(0))
        fixture.apply(1, emptySet(), mapOf(0 to "garen"))
        val before = fixture.ledger.currentRevision()
        val mismatch = fixture.reconciler.reconcile(board(2, setOf(1 to 1)), bench(3, emptyMap()), fixture.ledger)
        assertEquals("FRAME_SEQUENCE_MISMATCH", mismatch.reason)
        assertEquals(before, fixture.ledger.currentRevision())

        val duplicate = fixture.reconciler.reconcile(board(2, setOf(1 to 1)), null, fixture.ledger)
        assertEquals("BOARD_STALE_OR_DUPLICATE", duplicate.reason)
        assertEquals(before, fixture.ledger.currentRevision())
        val stale = fixture.reconciler.reconcile(null, bench(1, emptyMap()), fixture.ledger)
        assertEquals("BENCH_STALE_OR_DUPLICATE", stale.reason)
        assertEquals(before, fixture.ledger.currentRevision())
    }

    @Test
    fun unresolvedTrustedDeltaAdvancesBaselineAndIsNotReplayed() {
        val fixture = Fixture()
        fixture.apply(1, setOf(1 to 1, 1 to 2), emptyMap())
        fixture.assertRejected(fixture.apply(2, setOf(2 to 1, 2 to 2), emptyMap()), "AMBIGUOUS_OR_UNSUPPORTED_DELTA")
        assertEquals(ObservationReconcileStatus.NO_CHANGE, fixture.apply(3, setOf(2 to 1, 2 to 2), emptyMap()).status)
    }

    @Test
    fun reconcilerGraphHasNoFramePixelAndroidOrActionReferences() {
        val forbidden = setOf(IntArray::class.java, CapturedFrame::class.java, JinChanCanonicalFrame::class.java)
        val classes = listOf(JinChanObservationReconciler::class.java, ObservationReconcileResult::class.java)
        classes.forEach { type -> assertFalse(type.declaredFields.any { it.type in forbidden }) }
        val names = classes.flatMap { it.declaredFields.map { field -> field.type.name } }
        assertFalse(names.any { name -> listOf("Bitmap", "Mat", "CapturedFrame", "Context", "Activity", "Action", "Gesture").any { name.contains(it, true) } })
    }

    private class Fixture {
        val ledger = JinChanUnitLedger()
        val reconciler = JinChanObservationReconciler()
        fun create(hero: String, location: UnitLocation) = requireNotNull(ledger.create(hero, 1, location).uid)
        fun apply(seq: Long, occupied: Set<Pair<Int, Int>>, heroes: Map<Int, String>) =
            reconciler.reconcile(board(seq, occupied), bench(seq, heroes), ledger)
        fun assertMove(result: ObservationReconcileResult, uid: Long, destination: UnitLocation) {
            assertEquals(ObservationReconcileStatus.RECONCILED_MOVE, result.status)
            assertEquals(uid, result.uid)
            assertEquals(1, ledger.snapshot().units.size)
            assertEquals(destination, ledger.snapshot().activeUnits.single().location)
        }
        fun assertRejected(result: ObservationReconcileResult, reason: String) {
            assertEquals(ObservationReconcileStatus.RECONCILE_REQUIRED, result.status)
            assertEquals(reason, result.reason)
            assertEquals(result.ledgerRevision, ledger.currentRevision())
        }
    }

    companion object {
        private fun board(seq: Long, occupied: Set<Pair<Int, Int>>): BoardOccupancyObservation {
            val cells = (1..4).flatMap { row -> (1..7).map { col ->
                BoardCellObservation(row, col, "R${row}C$col", (row to col) in occupied, if ((row to col) in occupied) .99 else .01)
            } }
            return BoardOccupancyObservation(BoardObservationStatus.AVAILABLE, seq, BoardSnapshotDecision.SET, "TEST", occupiedCount = occupied.size, cells = cells)
        }

        private fun bench(seq: Long, heroes: Map<Int, String>, status: BenchObservationStatus = BenchObservationStatus.COMPLETE) =
            BenchObservation(status, seq, (0..8).map { slot ->
                heroes[slot]?.let { BenchSlotObservation(slot, BenchSlotState.HERO, it, ResolvedHero(it, it), 1) }
                    ?: BenchSlotObservation(slot, BenchSlotState.EMPTY)
            })
    }
}
