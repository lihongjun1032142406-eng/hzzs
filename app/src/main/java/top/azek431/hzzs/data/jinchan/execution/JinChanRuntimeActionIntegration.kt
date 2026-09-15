package top.azek431.hzzs.data.jinchan.execution

import javax.inject.Inject
import javax.inject.Singleton
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.action.JinChanActionGateResult
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionRejectionReason
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanCoordinateProfile
import top.azek431.hzzs.data.jinchan.action.JinChanGestureBlockedReason
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolver
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolverInput
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation

/** Future producer boundary. Implementations may only return an immutable, explicit calibration snapshot. */
fun interface JinChanCoordinateProfileProvider {
    fun snapshot(): JinChanCoordinateProfile?
}

/** Authoritative source location tied to the exact ownership snapshot evaluated by H6-A. */
data class JinChanActionSourceEvidence(
    val location: UnitLocation,
    val sessionId: JinChanFrameSessionId,
    val evidenceSequence: Long,
    val ownershipRevision: Long,
)

/**
 * Immutable request created only by a future action producer. [requestId] is trace identity while
 * [trackId] is the process-local, monotonic logical-action identity; neither is generated here.
 */
data class JinChanRuntimeActionRequest(
    val requestId: String,
    val intent: JinChanActionIntent,
    val context: JinChanActionContext,
    val trackId: Long,
    val coordinateProfile: JinChanCoordinateProfile?,
    val sourceEvidence: JinChanActionSourceEvidence? = null,
)

enum class JinChanRuntimeInputBlockedReason {
    INVALID_REQUEST_ID,
    INVALID_TRACK_ID,
    SOURCE_EVIDENCE_REQUIRED,
    SOURCE_EVIDENCE_INVALID,
    SOURCE_PROVENANCE_MISMATCH,
}

/** Fields remain null until the stage which authoritatively produces them has succeeded. */
data class JinChanRuntimeActionProvenance(
    val requestId: String?,
    val trackId: Long?,
    val sessionId: JinChanFrameSessionId?,
    val evidenceSequence: Long?,
    val ownershipRevision: Long?,
    val targetPackage: String?,
    val resolverId: String?,
    val profileId: String?,
    val actionId: Long?,
)

sealed interface JinChanRuntimeActionResult {
    val provenance: JinChanRuntimeActionProvenance

    data class InputBlocked(
        val reason: JinChanRuntimeInputBlockedReason,
        override val provenance: JinChanRuntimeActionProvenance,
    ) : JinChanRuntimeActionResult

    data class GateRejected(
        val reason: JinChanActionRejectionReason,
        override val provenance: JinChanRuntimeActionProvenance,
    ) : JinChanRuntimeActionResult

    data class ResolverBlocked(
        val reason: JinChanGestureBlockedReason,
        override val provenance: JinChanRuntimeActionProvenance,
    ) : JinChanRuntimeActionResult

    data class Execution(
        val result: JinChanExecutionResult,
        override val provenance: JinChanRuntimeActionProvenance,
    ) : JinChanRuntimeActionResult
}

/**
 * H6-C3's only runtime action entry point. It performs typed orchestration only:
 * input validation -> frozen H6-A -> frozen H6-B -> frozen H6-C2 (which invokes frozen C1).
 * It owns no producer, calibration, ledger, arbiter, dispatcher, or platform transport.
 */
@Singleton
class JinChanRuntimeActionIntegration @Inject constructor(
    private val coordinator: JinChanExecutionCoordinator,
) {
    suspend fun submit(request: JinChanRuntimeActionRequest): JinChanRuntimeActionResult {
        val initial = request.initialProvenance()
        if (request.requestId.isBlank()) {
            return JinChanRuntimeActionResult.InputBlocked(
                JinChanRuntimeInputBlockedReason.INVALID_REQUEST_ID,
                initial.copy(requestId = null),
            )
        }
        if (request.trackId <= 0L) {
            return JinChanRuntimeActionResult.InputBlocked(
                JinChanRuntimeInputBlockedReason.INVALID_TRACK_ID,
                initial.copy(trackId = null),
            )
        }
        validateSourceEvidence(request)?.let { reason ->
            return JinChanRuntimeActionResult.InputBlocked(reason, initial)
        }

        val gate = JinChanActionSafetyGate.evaluate(request.intent, request.context)
        val approved = when (gate) {
            is JinChanActionGateResult.Rejected -> {
                return JinChanRuntimeActionResult.GateRejected(gate.reason, initial)
            }
            is JinChanActionGateResult.Approved -> gate.action
        }
        val approvedProvenance = initial.copy(
            sessionId = approved.provenance.sessionId,
            evidenceSequence = approved.provenance.evidenceSequence,
            ownershipRevision = approved.provenance.ownershipRevision,
            targetPackage = approved.provenance.targetPackage,
        )

        val resolved = JinChanGestureResolver.resolve(
            JinChanGestureResolverInput(
                action = approved,
                profile = request.coordinateProfile,
                sourceLocation = request.sourceEvidence?.location,
            ),
        )
        val ready = when (resolved) {
            is JinChanGestureResolveResult.Blocked -> {
                return JinChanRuntimeActionResult.ResolverBlocked(resolved.reason, approvedProvenance)
            }
            is JinChanGestureResolveResult.Resolved -> resolved
        }
        val resolvedProvenance = approvedProvenance.copy(
            resolverId = ready.provenance.resolverId,
            profileId = ready.provenance.profileId,
        )

        val execution = coordinator.execute(ready, request.trackId)
        return JinChanRuntimeActionResult.Execution(
            result = execution,
            provenance = resolvedProvenance.copy(actionId = execution.actionIdOrNull()),
        )
    }

    private fun validateSourceEvidence(
        request: JinChanRuntimeActionRequest,
    ): JinChanRuntimeInputBlockedReason? {
        if (request.intent !is JinChanActionIntent.MoveUnit && request.intent !is JinChanActionIntent.SellUnit) {
            return null
        }
        val source = request.sourceEvidence
            ?: return JinChanRuntimeInputBlockedReason.SOURCE_EVIDENCE_REQUIRED
        if (
            source.sessionId.value <= 0L ||
            source.evidenceSequence < 0L ||
            source.ownershipRevision < 0L ||
            source.location == UnitLocation.Unknown ||
            source.location == UnitLocation.None
        ) {
            return JinChanRuntimeInputBlockedReason.SOURCE_EVIDENCE_INVALID
        }
        val context = request.context
        if (
            source.sessionId != context.sessionId ||
            source.sessionId != context.evidenceSessionId ||
            source.evidenceSequence != context.evidenceSequence ||
            source.evidenceSequence != context.ownershipSequence ||
            source.ownershipRevision != context.expectedOwnershipRevision ||
            source.ownershipRevision != context.ownership?.sourceLedgerRevision
        ) {
            return JinChanRuntimeInputBlockedReason.SOURCE_PROVENANCE_MISMATCH
        }
        return null
    }

    private fun JinChanRuntimeActionRequest.initialProvenance() = JinChanRuntimeActionProvenance(
        requestId = requestId,
        trackId = trackId.takeIf { it > 0L },
        sessionId = context.sessionId,
        evidenceSequence = context.evidenceSequence,
        ownershipRevision = null,
        targetPackage = null,
        resolverId = null,
        profileId = null,
        actionId = null,
    )

    private fun JinChanExecutionResult.actionIdOrNull(): Long? = when (this) {
        is JinChanExecutionResult.Completed -> receipt.action.id
        is JinChanExecutionResult.Rejected -> receipt.action.id
        is JinChanExecutionResult.Cancelled -> receipt.action.id
        is JinChanExecutionResult.Expired -> receipt?.action?.id
        is JinChanExecutionResult.Disabled,
        is JinChanExecutionResult.AdapterRejected,
        JinChanExecutionResult.InvalidTrackId,
        JinChanExecutionResult.PackageInvariantRejected,
        -> null
    }
}
