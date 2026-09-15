package top.azek431.hzzs.data.jinchan.decision

import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState

/** Explicit values captured by one runtime iteration; no value may be repaired from later state. */
data class JinChanSameEvidenceFacts(
    val shadowState: JinChanShadowState,
    val ownershipSnapshot: JinChanOwnershipSnapshot?,
    val actionContext: JinChanActionContext,
)

/** Immutable facts seen by one future decision. It contains no decision or execution data. */
data class JinChanJoinedStateSnapshot internal constructor(
    val sessionId: JinChanFrameSessionId,
    val evidenceSequence: Long,
    val ownershipRevision: Long,
    val shadowState: JinChanShadowState,
    val ownershipSnapshot: JinChanOwnershipSnapshot,
    val actionContext: JinChanActionContext,
)

enum class JinChanJoinBlockedReason {
    OWNERSHIP_MISSING,
    SESSION_MISMATCH,
    EVIDENCE_SEQUENCE_MISMATCH,
    OWNERSHIP_REVISION_MISMATCH,
}

sealed interface JinChanJoinResult {
    data class Assembled(val snapshot: JinChanJoinedStateSnapshot) : JinChanJoinResult
    data class Blocked(val reason: JinChanJoinBlockedReason) : JinChanJoinResult
}

/** Pure same-evidence join. It only examines the values supplied in [facts]. */
object JinChanSameEvidenceAssembler {
    fun assemble(facts: JinChanSameEvidenceFacts): JinChanJoinResult {
        val shadow = facts.shadowState
        val context = facts.actionContext
        val ownership = facts.ownershipSnapshot
            ?: return JinChanJoinResult.Blocked(JinChanJoinBlockedReason.OWNERSHIP_MISSING)

        if (
            shadow.sessionId != context.sessionId ||
            context.evidenceSessionId != shadow.sessionId ||
            context.ownershipSessionId != shadow.sessionId
        ) {
            return JinChanJoinResult.Blocked(JinChanJoinBlockedReason.SESSION_MISMATCH)
        }
        if (
            shadow.frameSeq != context.evidenceSequence ||
            context.ownershipSequence != shadow.frameSeq
        ) {
            return JinChanJoinResult.Blocked(JinChanJoinBlockedReason.EVIDENCE_SEQUENCE_MISMATCH)
        }
        if (
            context.ownership !== ownership ||
            context.expectedOwnershipRevision != ownership.sourceLedgerRevision
        ) {
            return JinChanJoinResult.Blocked(JinChanJoinBlockedReason.OWNERSHIP_REVISION_MISMATCH)
        }

        return JinChanJoinResult.Assembled(
            JinChanJoinedStateSnapshot(
                sessionId = shadow.sessionId,
                evidenceSequence = shadow.frameSeq,
                ownershipRevision = ownership.sourceLedgerRevision,
                shadowState = shadow,
                ownershipSnapshot = ownership,
                actionContext = context,
            ),
        )
    }
}
