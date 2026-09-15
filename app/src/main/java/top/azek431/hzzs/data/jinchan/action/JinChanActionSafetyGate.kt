package top.azek431.hzzs.data.jinchan.action

import top.azek431.hzzs.data.jinchan.ledger.ObservationReconcileStatus
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.perception.ShopContentType
import top.azek431.hzzs.data.jinchan.perception.ShopObservationStatus

/** Pure, fail-closed semantic validator. It has no action transport dependency or side effect. */
object JinChanActionSafetyGate {
    const val JINCHAN_PACKAGE = "com.tencent.jkchess"

    fun evaluate(intent: JinChanActionIntent, context: JinChanActionContext): JinChanActionGateResult {
        fun reject(reason: JinChanActionRejectionReason) = JinChanActionGateResult.Rejected(reason, intent)

        if (!context.actionEnabled) return reject(JinChanActionRejectionReason.ACTION_DISABLED)
        if (context.targetPackage != JINCHAN_PACKAGE) return reject(JinChanActionRejectionReason.INVALID_TARGET_PACKAGE)
        if (context.sessionId.value <= 0L || context.evidenceSessionId != context.sessionId) {
            return reject(JinChanActionRejectionReason.SESSION_MISMATCH)
        }
        if (!fresh(context.currentSequence, context.evidenceSequence, context.maximumSequenceAge)) {
            return reject(JinChanActionRejectionReason.STALE_EVIDENCE)
        }
        if (context.stableState.inGame != true || context.stableState.uiState in setOf(JinChanStableUiState.OTHER, JinChanStableUiState.UNKNOWN)) {
            return reject(JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE)
        }
        if (context.duplicateOrConflictingIntent) return reject(JinChanActionRejectionReason.DUPLICATE_OR_CONFLICTING_INTENT)

        val reason = when (intent) {
            is JinChanActionIntent.BuyShopSlot -> validateBuy(intent, context)
            is JinChanActionIntent.MoveUnit -> validateMove(intent, context)
            is JinChanActionIntent.SellUnit -> validateSell(intent, context)
            JinChanActionIntent.RefreshShop -> validateShopControl(context, context.uiEvidence.refreshShopAllowed)
            JinChanActionIntent.BuyXp -> validateShopControl(context, context.uiEvidence.buyXpAllowed)
        }
        if (reason != null) return reject(reason)
        return JinChanActionGateResult.Approved(
            ApprovedJinChanAction(
                intent,
                JinChanActionApprovalProvenance(
                    context.sessionId,
                    context.evidenceSequence,
                    context.ownership?.sourceLedgerRevision,
                    JINCHAN_PACKAGE,
                ),
            ),
        )
    }

    private fun validateBuy(intent: JinChanActionIntent.BuyShopSlot, c: JinChanActionContext): JinChanActionRejectionReason? {
        if (intent.slot !in 0..4) return JinChanActionRejectionReason.INVALID_SLOT
        if (c.stableState.uiState != JinChanStableUiState.SHOP_OPEN) return JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE
        val shop = c.shop ?: return JinChanActionRejectionReason.SHOP_EVIDENCE_UNAVAILABLE
        if (shop.status != ShopObservationStatus.AVAILABLE || shop.frameSeq != c.evidenceSequence || shop.slots.size != 5) {
            return JinChanActionRejectionReason.SHOP_EVIDENCE_UNAVAILABLE
        }
        if (!c.shopTrusted) return JinChanActionRejectionReason.SHOP_EVIDENCE_UNTRUSTED
        val slot = shop.slots.singleOrNull { it.position == intent.slot }
            ?: return JinChanActionRejectionReason.SHOP_EVIDENCE_UNAVAILABLE
        return if (slot.contentType == ShopContentType.UNKNOWN) JinChanActionRejectionReason.SHOP_SLOT_UNKNOWN else null
    }

    private fun validateMove(intent: JinChanActionIntent.MoveUnit, c: JinChanActionContext): JinChanActionRejectionReason? {
        if (intent.uid <= 0L) return JinChanActionRejectionReason.INVALID_UID
        if (!legalDestination(intent.destination)) return JinChanActionRejectionReason.INVALID_DESTINATION
        val common = validateOwnership(c)
        if (common != null) return common
        val unit = c.ownership!!.activeUnits.singleOrNull { it.uid == intent.uid }
            ?: return JinChanActionRejectionReason.UNIT_NOT_ACTIVE
        if (unit.location == UnitLocation.Unknown || unit.location == UnitLocation.None) return JinChanActionRejectionReason.UNIT_LOCATION_UNKNOWN
        if (unit.location == intent.destination) return JinChanActionRejectionReason.SAME_DESTINATION
        if ((unit.location is UnitLocation.Board || intent.destination is UnitLocation.Board) && !c.boardTrusted) {
            return JinChanActionRejectionReason.BOARD_EVIDENCE_UNTRUSTED
        }
        if ((unit.location is UnitLocation.Bench || intent.destination is UnitLocation.Bench) && !c.benchTrusted) {
            return JinChanActionRejectionReason.BENCH_EVIDENCE_UNTRUSTED
        }
        if (unresolved(c.reconcileStatus)) return JinChanActionRejectionReason.RECONCILIATION_UNRESOLVED
        return if (c.ownershipAmbiguous) JinChanActionRejectionReason.OWNERSHIP_AMBIGUOUS else null
    }

    private fun validateSell(intent: JinChanActionIntent.SellUnit, c: JinChanActionContext): JinChanActionRejectionReason? {
        if (intent.uid <= 0L) return JinChanActionRejectionReason.INVALID_UID
        if (c.stableState.uiState != JinChanStableUiState.BOARD_OR_COMBAT) return JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE
        if (!c.uiEvidence.sellAllowed) return JinChanActionRejectionReason.ACTION_UI_EVIDENCE_MISSING
        val common = validateOwnership(c)
        if (common != null) return common
        val unit = c.ownership!!.activeUnits.singleOrNull { it.uid == intent.uid }
            ?: return JinChanActionRejectionReason.UNIT_NOT_ACTIVE
        if (unit.heroKey.isNullOrBlank()) return JinChanActionRejectionReason.UNIT_IDENTITY_UNKNOWN
        if (unit.location == UnitLocation.Unknown || unit.location == UnitLocation.None) return JinChanActionRejectionReason.UNIT_LOCATION_UNKNOWN
        if (c.ownershipAmbiguous) return JinChanActionRejectionReason.OWNERSHIP_AMBIGUOUS
        if (unresolved(c.reconcileStatus)) return JinChanActionRejectionReason.RECONCILIATION_UNRESOLVED
        return when (unit.location) {
            is UnitLocation.Board -> if (c.boardTrusted) null else JinChanActionRejectionReason.BOARD_EVIDENCE_UNTRUSTED
            is UnitLocation.Bench -> if (c.benchTrusted) null else JinChanActionRejectionReason.BENCH_EVIDENCE_UNTRUSTED
            else -> JinChanActionRejectionReason.UNIT_LOCATION_UNKNOWN
        }
    }

    private fun validateShopControl(c: JinChanActionContext, explicitlyAllowed: Boolean): JinChanActionRejectionReason? {
        if (c.stableState.uiState != JinChanStableUiState.SHOP_OPEN) return JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE
        return if (explicitlyAllowed) null else JinChanActionRejectionReason.ACTION_UI_EVIDENCE_MISSING
    }

    private fun validateOwnership(c: JinChanActionContext): JinChanActionRejectionReason? {
        val ownership = c.ownership ?: return JinChanActionRejectionReason.OWNERSHIP_EVIDENCE_UNAVAILABLE
        if (c.ownershipSessionId != c.sessionId) return JinChanActionRejectionReason.SESSION_MISMATCH
        val sequence = c.ownershipSequence ?: return JinChanActionRejectionReason.STALE_EVIDENCE
        if (!fresh(c.currentSequence, sequence, c.maximumSequenceAge)) return JinChanActionRejectionReason.STALE_EVIDENCE
        if (c.expectedOwnershipRevision == null || ownership.sourceLedgerRevision != c.expectedOwnershipRevision) {
            return JinChanActionRejectionReason.OWNERSHIP_REVISION_MISMATCH
        }
        return null
    }

    private fun fresh(current: Long, evidence: Long, maximumAge: Long): Boolean =
        current >= 0L && evidence >= 0L && maximumAge >= 0L && evidence <= current && current - evidence <= maximumAge

    private fun legalDestination(location: UnitLocation): Boolean = when (location) {
        is UnitLocation.Board -> location.row in 1..4 && location.col in 1..7
        is UnitLocation.Bench -> location.slot in 0..8
        UnitLocation.Unknown, UnitLocation.None -> false
    }

    private fun unresolved(status: ObservationReconcileStatus?): Boolean =
        status == null || status == ObservationReconcileStatus.RECONCILE_REQUIRED || status == ObservationReconcileStatus.INPUT_UNAVAILABLE
}
