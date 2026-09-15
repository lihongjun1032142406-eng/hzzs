package top.azek431.hzzs.data.jinchan.action

import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot
import top.azek431.hzzs.data.jinchan.ledger.ObservationReconcileStatus
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.ShopObservation

/** Pixel-independent actions understood by the H6-A shadow safety boundary. */
sealed interface JinChanActionIntent {
    data class BuyShopSlot(val slot: Int) : JinChanActionIntent
    data class MoveUnit(val uid: Long, val destination: UnitLocation) : JinChanActionIntent
    data class SellUnit(val uid: Long) : JinChanActionIntent
    data object RefreshShop : JinChanActionIntent
    data object BuyXp : JinChanActionIntent
}

/** Caller-supplied permissions for controls which H1-H5 do not perceive. */
data class JinChanActionUiEvidence(
    val sellAllowed: Boolean = false,
    val refreshShopAllowed: Boolean = false,
    val buyXpAllowed: Boolean = false,
)

/**
 * Immutable H6-A input. Sequence ages are evaluated against [currentSequence], never an Android clock.
 * Trust flags are explicit because H6-A must not manufacture trust from an observation's presence.
 */
data class JinChanActionContext(
    val actionEnabled: Boolean = false,
    val targetPackage: String? = null,
    val sessionId: JinChanFrameSessionId,
    val evidenceSessionId: JinChanFrameSessionId,
    val currentSequence: Long,
    val evidenceSequence: Long,
    val maximumSequenceAge: Long,
    val stableState: JinChanStableState,
    val shop: ShopObservation? = null,
    val shopTrusted: Boolean = false,
    val boardTrusted: Boolean = false,
    val benchTrusted: Boolean = false,
    val ownership: JinChanOwnershipSnapshot? = null,
    val ownershipSessionId: JinChanFrameSessionId? = null,
    val ownershipSequence: Long? = null,
    val expectedOwnershipRevision: Long? = null,
    val reconcileStatus: ObservationReconcileStatus? = null,
    val ownershipAmbiguous: Boolean = false,
    val duplicateOrConflictingIntent: Boolean = false,
    val uiEvidence: JinChanActionUiEvidence = JinChanActionUiEvidence(),
)

enum class JinChanActionRejectionReason {
    ACTION_DISABLED,
    INVALID_TARGET_PACKAGE,
    SESSION_MISMATCH,
    STALE_EVIDENCE,
    INCOMPATIBLE_UI_SCENE,
    DUPLICATE_OR_CONFLICTING_INTENT,
    INVALID_SLOT,
    INVALID_UID,
    INVALID_DESTINATION,
    SAME_DESTINATION,
    SHOP_EVIDENCE_UNAVAILABLE,
    SHOP_EVIDENCE_UNTRUSTED,
    SHOP_SLOT_UNKNOWN,
    OWNERSHIP_EVIDENCE_UNAVAILABLE,
    OWNERSHIP_REVISION_MISMATCH,
    UNIT_NOT_ACTIVE,
    UNIT_IDENTITY_UNKNOWN,
    UNIT_LOCATION_UNKNOWN,
    BOARD_EVIDENCE_UNTRUSTED,
    BENCH_EVIDENCE_UNTRUSTED,
    RECONCILIATION_UNRESOLVED,
    OWNERSHIP_AMBIGUOUS,
    ACTION_UI_EVIDENCE_MISSING,
}

data class JinChanActionApprovalProvenance(
    val sessionId: JinChanFrameSessionId,
    val evidenceSequence: Long,
    val ownershipRevision: Long?,
    val targetPackage: String,
)

/** Capability value only. Coordinate resolution and dispatch are deliberately absent. */
data class ApprovedJinChanAction(
    val intent: JinChanActionIntent,
    val provenance: JinChanActionApprovalProvenance,
)

sealed interface JinChanActionGateResult {
    data class Approved(val action: ApprovedJinChanAction) : JinChanActionGateResult
    data class Rejected(
        val reason: JinChanActionRejectionReason,
        val intent: JinChanActionIntent,
    ) : JinChanActionGateResult
}
