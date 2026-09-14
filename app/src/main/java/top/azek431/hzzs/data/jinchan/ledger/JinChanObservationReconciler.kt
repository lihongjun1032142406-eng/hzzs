package top.azek431.hzzs.data.jinchan.ledger

import top.azek431.hzzs.data.jinchan.perception.BenchObservation
import top.azek431.hzzs.data.jinchan.perception.BenchObservationStatus
import top.azek431.hzzs.data.jinchan.perception.BenchSlotState
import top.azek431.hzzs.data.jinchan.perception.BoardOccupancyObservation
import top.azek431.hzzs.data.jinchan.perception.BoardObservationStatus
import top.azek431.hzzs.data.jinchan.perception.BoardSnapshotDecision

enum class ObservationReconcileStatus {
    INIT,
    NO_CHANGE,
    RECONCILED_MOVE,
    RECONCILE_REQUIRED,
    INPUT_UNAVAILABLE,
}

/** Immutable reconciliation diagnostic. It contains locations only, never perception or frame objects. */
data class ObservationReconcileResult(
    val status: ObservationReconcileStatus,
    val reason: String,
    val source: UnitLocation? = null,
    val destination: UnitLocation? = null,
    val uid: Long? = null,
    val ledgerRevision: Long,
)

/**
 * H5-B shadow-only reconciler for trusted structured Board and Bench snapshots.
 *
 * Only normalized, pixel-independent baselines are retained. A ledger is mutated exclusively through [JinChanUnitLedger.move]
 * after one of the four frozen one-source/one-destination patterns has been established uniquely.
 */
class JinChanObservationReconciler {
    private data class BoardBaseline(val frameSeq: Long, val occupied: Set<UnitLocation.Board>)
    private data class BenchCell(val occupied: Boolean, val heroKey: String?)
    private data class BenchBaseline(val frameSeq: Long, val slots: Map<UnitLocation.Bench, BenchCell>)
    private data class Delta<L : UnitLocation>(val removed: Set<L>, val added: Set<L>) {
        val changed: Boolean get() = removed.isNotEmpty() || added.isNotEmpty()
    }

    private var boardBaseline: BoardBaseline? = null
    private var benchBaseline: BenchBaseline? = null

    fun reconcile(
        board: BoardOccupancyObservation?,
        bench: BenchObservation?,
        ledger: JinChanUnitLedger,
    ): ObservationReconcileResult {
        val revision = ledger.currentRevision()
        val normalizedBoard = board?.let { normalizeBoard(it) }
        val normalizedBench = bench?.let { normalizeBench(it) }
        if (board != null && normalizedBoard == null) return result(ObservationReconcileStatus.INPUT_UNAVAILABLE, boardReason(board), revision)
        if (bench != null && normalizedBench == null) return result(ObservationReconcileStatus.INPUT_UNAVAILABLE, benchReason(bench), revision)
        if (board == null && bench == null) return result(ObservationReconcileStatus.INPUT_UNAVAILABLE, "NO_INPUT", revision)

        if (normalizedBoard != null && boardBaseline?.let { normalizedBoard.frameSeq <= it.frameSeq } == true) {
            return result(ObservationReconcileStatus.INPUT_UNAVAILABLE, "BOARD_STALE_OR_DUPLICATE", revision)
        }
        if (normalizedBench != null && benchBaseline?.let { normalizedBench.frameSeq <= it.frameSeq } == true) {
            return result(ObservationReconcileStatus.INPUT_UNAVAILABLE, "BENCH_STALE_OR_DUPLICATE", revision)
        }

        val previousBoard = boardBaseline
        val previousBench = benchBaseline
        val boardDelta = if (normalizedBoard != null && previousBoard != null) {
            Delta(previousBoard.occupied - normalizedBoard.occupied, normalizedBoard.occupied - previousBoard.occupied)
        } else null
        val benchDelta = if (normalizedBench != null && previousBench != null) {
            val oldOccupied = previousBench.slots.filterValues { it.occupied }.keys
            val newOccupied = normalizedBench.slots.filterValues { it.occupied }.keys
            Delta(oldOccupied - newOccupied, newOccupied - oldOccupied)
        } else null

        // Trusted observations always become the next comparison baseline, even if their delta is unresolved.
        if (normalizedBoard != null) boardBaseline = normalizedBoard
        if (normalizedBench != null) benchBaseline = normalizedBench

        if ((normalizedBoard != null && previousBoard == null) || (normalizedBench != null && previousBench == null)) {
            return result(ObservationReconcileStatus.INIT, "TRUSTED_BASELINE_INITIALIZED", revision)
        }
        val boardChanged = boardDelta?.changed == true
        val benchChanged = benchDelta?.changed == true
        if (!boardChanged && !benchChanged) return result(ObservationReconcileStatus.NO_CHANGE, "NO_LOCATION_CHANGE", revision)

        val candidate = when {
            boardDelta != null && boardDelta.removed.size == 1 && boardDelta.added.size == 1 && !benchChanged ->
                boardDelta.removed.single() to boardDelta.added.single()
            benchDelta != null && benchDelta.removed.size == 1 && benchDelta.added.size == 1 && !boardChanged ->
                benchDelta.removed.single() to benchDelta.added.single()
            boardDelta != null && benchDelta != null && boardDelta.removed.isEmpty() && boardDelta.added.size == 1 &&
                benchDelta.removed.size == 1 && benchDelta.added.isEmpty() -> benchDelta.removed.single() to boardDelta.added.single()
            boardDelta != null && benchDelta != null && boardDelta.removed.size == 1 && boardDelta.added.isEmpty() &&
                benchDelta.removed.isEmpty() && benchDelta.added.size == 1 -> boardDelta.removed.single() to benchDelta.added.single()
            else -> null
        } ?: return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "AMBIGUOUS_OR_UNSUPPORTED_DELTA", revision)

        val (source, destination) = candidate
        val crossZone = source::class != destination::class
        if (crossZone && normalizedBoard?.frameSeq != normalizedBench?.frameSeq) {
            return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "FRAME_SEQUENCE_MISMATCH", revision, source, destination)
        }
        val active = ledger.snapshot().activeUnits
        val sources = active.filter { it.location == source }
        if (sources.size != 1) return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "SOURCE_UID_NOT_UNIQUE", revision, source, destination)
        val unit = sources.single()
        if (active.any { it.uid != unit.uid && it.location == destination }) {
            return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "DESTINATION_UID_CONFLICT", revision, source, destination)
        }
        if (destination is UnitLocation.Bench) {
            val observedHero = normalizedBench?.slots?.get(destination)?.heroKey
            if (observedHero != null && unit.heroKey != null && observedHero != unit.heroKey) {
                return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "DESTINATION_HERO_CONFLICT", revision, source, destination)
            }
        }
        val moved = ledger.move(unit.uid, destination)
        if (!moved.applied) return result(ObservationReconcileStatus.RECONCILE_REQUIRED, "LEDGER_MOVE_REJECTED", revision, source, destination)
        return result(ObservationReconcileStatus.RECONCILED_MOVE, "UNIQUE_LOCATION_MOVE", moved.revision, source, destination, unit.uid)
    }

    private fun normalizeBoard(observation: BoardOccupancyObservation): BoardBaseline? {
        if (observation.status != BoardObservationStatus.AVAILABLE || observation.decision != BoardSnapshotDecision.SET) return null
        if (observation.frameSeq < 0 || observation.cells.size != 28) return null
        val coordinates = observation.cells.map { UnitLocation.Board(it.row, it.col) }
        if (coordinates.any { !JinChanUnitLedger.validLocation(it) } || coordinates.distinct().size != 28) return null
        if (observation.cells.any { it.occupied == null }) return null
        val occupied = observation.cells.filter { it.occupied == true }.map { UnitLocation.Board(it.row, it.col) }.toSet()
        if (observation.occupiedCount != occupied.size) return null
        return BoardBaseline(observation.frameSeq, occupied)
    }

    private fun normalizeBench(observation: BenchObservation): BenchBaseline? {
        if (observation.status != BenchObservationStatus.COMPLETE || observation.frameSeq < 0 || observation.slots.size != 9) return null
        val slots = linkedMapOf<UnitLocation.Bench, BenchCell>()
        for (slot in observation.slots) {
            val location = UnitLocation.Bench(slot.slotIndex)
            if (!JinChanUnitLedger.validLocation(location) || slot.state == BenchSlotState.UNKNOWN || slots.containsKey(location)) return null
            slots[location] = BenchCell(slot.state == BenchSlotState.HERO, slot.hero?.canonicalId ?: slot.identity?.trim()?.takeIf(String::isNotEmpty))
        }
        return BenchBaseline(observation.frameSeq, slots)
    }

    private fun boardReason(observation: BoardOccupancyObservation) = when {
        observation.decision == BoardSnapshotDecision.HOLD -> "BOARD_HOLD"
        observation.status == BoardObservationStatus.UNAVAILABLE -> "BOARD_UNAVAILABLE"
        observation.status == BoardObservationStatus.INVALID -> "BOARD_INVALID"
        else -> "BOARD_MALFORMED"
    }

    private fun benchReason(observation: BenchObservation) = when (observation.status) {
        BenchObservationStatus.PARTIAL -> "BENCH_PARTIAL"
        BenchObservationStatus.UNAVAILABLE -> "BENCH_UNAVAILABLE"
        BenchObservationStatus.INVALID -> "BENCH_INVALID"
        BenchObservationStatus.COMPLETE -> "BENCH_MALFORMED"
    }

    private fun result(
        status: ObservationReconcileStatus,
        reason: String,
        revision: Long,
        source: UnitLocation? = null,
        destination: UnitLocation? = null,
        uid: Long? = null,
    ) = ObservationReconcileResult(status, reason, source, destination, uid, revision)
}
