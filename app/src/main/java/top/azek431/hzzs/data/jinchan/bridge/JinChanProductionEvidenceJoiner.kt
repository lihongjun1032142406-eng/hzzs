package top.azek431.hzzs.data.jinchan.bridge

import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinResult
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceAssembler
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceFacts
import top.azek431.hzzs.data.jinchan.ledger.JinChanObservationReconciler
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipProjector
import top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger
import top.azek431.hzzs.data.jinchan.ledger.ObservationReconcileStatus
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState

enum class ProductionJoinBlockedReason { RECONCILIATION_UNAVAILABLE_OR_AMBIGUOUS, CONTRACT_BLOCKED }
sealed interface ProductionJoinResult {
    data class Joined(val value: JinChanJoinResult.Assembled) : ProductionJoinResult
    data class Blocked(val reason: ProductionJoinBlockedReason) : ProductionJoinResult
}

/** Session-local production seam that performs reconciliation and exactly one decision-evidence snapshot. */
class JinChanProductionEvidenceJoiner(
    private val ledger: JinChanUnitLedger = JinChanUnitLedger(),
    private val reconciler: JinChanObservationReconciler = JinChanObservationReconciler(),
) {
    fun join(shadow: JinChanShadowState): ProductionJoinResult {
        val reconciliation = reconciler.reconcile(shadow.board.value, shadow.bench.value, ledger)
        if (reconciliation.status !in ACCEPTED) {
            return ProductionJoinResult.Blocked(ProductionJoinBlockedReason.RECONCILIATION_UNAVAILABLE_OR_AMBIGUOUS)
        }
        val ledgerSnapshot = ledger.snapshot()
        val ownership = JinChanOwnershipProjector.project(ledgerSnapshot)
        val context = JinChanActionContext(
            sessionId = shadow.sessionId,
            evidenceSessionId = shadow.sessionId,
            currentSequence = shadow.frameSeq,
            evidenceSequence = shadow.frameSeq,
            maximumSequenceAge = 0,
            stableState = JinChanStableState(null, JinChanStableUiState.UNKNOWN),
            shop = shadow.shop.value,
            shopTrusted = shadow.shop.status.name == "AVAILABLE",
            boardTrusted = shadow.board.status.name == "AVAILABLE",
            benchTrusted = shadow.bench.status.name == "AVAILABLE",
            ownership = ownership,
            ownershipSessionId = shadow.sessionId,
            ownershipSequence = shadow.frameSeq,
            expectedOwnershipRevision = ledgerSnapshot.revision,
            reconcileStatus = reconciliation.status,
        )
        return when (val joined = JinChanSameEvidenceAssembler.assemble(JinChanSameEvidenceFacts(shadow, ownership, context))) {
            is JinChanJoinResult.Assembled -> ProductionJoinResult.Joined(joined)
            is JinChanJoinResult.Blocked -> ProductionJoinResult.Blocked(ProductionJoinBlockedReason.CONTRACT_BLOCKED)
        }
    }

    private companion object {
        val ACCEPTED = setOf(ObservationReconcileStatus.INIT, ObservationReconcileStatus.NO_CHANGE, ObservationReconcileStatus.RECONCILED_MOVE)
    }
}
