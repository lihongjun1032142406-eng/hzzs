package top.azek431.hzzs.data.jinchan.decision

import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId

/** The decision boundary receives exactly one already-joined immutable state snapshot. */
data class JinChanDecisionInput(val state: JinChanJoinedStateSnapshot)

data class JinChanDecisionProvenance(
    val sessionId: JinChanFrameSessionId,
    val evidenceSequence: Long,
    val ownershipRevision: Long,
)

enum class JinChanNoActionReason {
    STRATEGY_UNAVAILABLE,
}

enum class JinChanDecisionBlockedReason {
    PROVENANCE_MISMATCH,
}

/** C4C deliberately has no Action variant: this production skeleton cannot form an action candidate. */
sealed interface JinChanDecisionResult {
    val provenance: JinChanDecisionProvenance

    data class NoAction(
        override val provenance: JinChanDecisionProvenance,
        val reason: JinChanNoActionReason,
    ) : JinChanDecisionResult

    data class Blocked(
        override val provenance: JinChanDecisionProvenance,
        val reason: JinChanDecisionBlockedReason,
    ) : JinChanDecisionResult
}

fun interface JinChanDecisionEngine {
    fun decide(input: JinChanDecisionInput): JinChanDecisionResult
}

/** Fail-closed C4C implementation. A real strategy is intentionally absent. */
object FailClosedJinChanDecisionEngine : JinChanDecisionEngine {
    override fun decide(input: JinChanDecisionInput): JinChanDecisionResult {
        val state = input.state
        val provenance = JinChanDecisionProvenance(
            sessionId = state.sessionId,
            evidenceSequence = state.evidenceSequence,
            ownershipRevision = state.ownershipRevision,
        )
        val context = state.actionContext
        if (
            context.sessionId != provenance.sessionId ||
            context.evidenceSessionId != provenance.sessionId ||
            context.ownershipSessionId != provenance.sessionId ||
            context.evidenceSequence != provenance.evidenceSequence ||
            context.ownershipSequence != provenance.evidenceSequence ||
            context.expectedOwnershipRevision != provenance.ownershipRevision ||
            state.ownershipSnapshot.sourceLedgerRevision != provenance.ownershipRevision
        ) {
            return JinChanDecisionResult.Blocked(
                provenance,
                JinChanDecisionBlockedReason.PROVENANCE_MISMATCH,
            )
        }
        return JinChanDecisionResult.NoAction(provenance, JinChanNoActionReason.STRATEGY_UNAVAILABLE)
    }
}
