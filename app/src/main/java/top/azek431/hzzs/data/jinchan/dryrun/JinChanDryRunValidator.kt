package top.azek431.hzzs.data.jinchan.dryrun

import javax.inject.Inject
import org.json.JSONObject
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.data.jinchan.action.JinChanActionGateResult
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanCoordinateProfile
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolver
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolverInput
import top.azek431.hzzs.data.jinchan.bridge.PairedRikkaValidation
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionRejection
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionValidationResult
import top.azek431.hzzs.data.jinchan.bridge.RikkaDryRunDiagnostics
import top.azek431.hzzs.data.jinchan.bridge.toJson
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation

enum class JinChanDryRunTerminalReason {
    NO_PAIRED_OBSERVATION, PROVENANCE_REJECTED, UID_REJECTED, INVALID_ACTION,
    RIKKA_NO_ACTION, RIKKA_BLOCKED, GATE_REJECTED, RESOLVER_PROFILE_UNAVAILABLE,
    RESOLVER_BLOCKED, WOULD_RESOLVE,
}

data class JinChanActionSourceEvidence(
    val sessionId: Long,
    val evidenceSequence: Long,
    val ownershipRevision: Long,
    val uid: Long,
    val sourceLocation: UnitLocation,
)

data class JinChanDryRunResult(
    val schemaVersion: Int = 1,
    val sessionId: Long? = null,
    val evidenceSequence: Long? = null,
    val ownershipRevision: Long? = null,
    val captureTimestamp: Long? = null,
    val decisionKind: String,
    val intentType: String? = null,
    val uid: Long? = null,
    val sourceLocation: UnitLocation? = null,
    val targetLocation: UnitLocation? = null,
    val gateStatus: String = "NOT_EVALUATED",
    val gateReason: String? = null,
    val resolverStatus: String = "NOT_EVALUATED",
    val resolverReason: String? = null,
    val profileId: String? = null,
    val terminalReason: JinChanDryRunTerminalReason,
    val realActionReachable: Boolean = false,
    val actionExecuted: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("sessionId", sessionId ?: JSONObject.NULL)
        .put("evidenceSequence", evidenceSequence ?: JSONObject.NULL)
        .put("ownershipRevision", ownershipRevision ?: JSONObject.NULL)
        .put("captureTimestamp", captureTimestamp ?: JSONObject.NULL)
        .put("decisionKind", decisionKind)
        .put("intentType", intentType ?: JSONObject.NULL)
        .put("uid", uid ?: JSONObject.NULL)
        .put("sourceLocation", sourceLocation?.toJson() ?: JSONObject.NULL)
        .put("targetLocation", targetLocation?.toJson() ?: JSONObject.NULL)
        .put("gateStatus", gateStatus)
        .put("gateReason", gateReason ?: JSONObject.NULL)
        .put("resolverStatus", resolverStatus)
        .put("resolverReason", resolverReason ?: JSONObject.NULL)
        .put("profileId", profileId ?: JSONObject.NULL)
        .put("terminalReason", terminalReason.name)
        .put("realActionReachable", false)
        .put("actionExecuted", 0)
        .put("hardStop", true)
}

/** C5's terminal boundary: pure H6-A/H6-B validation, with no coordinator or transport dependency. */
class JinChanDryRunValidator @Inject constructor() {
    fun validate(
        pairedValidation: PairedRikkaValidation,
        coordinateProfile: JinChanCoordinateProfile? = null,
    ): JinChanDryRunResult {
        val validation = pairedValidation.result
        val paired = pairedValidation.pairedEvidence
        require(validation !is RikkaDecisionValidationResult.Rejected) { "C4D rejection must not enter the C5 boundary" }
        checkNotNull(paired) { "validated decision must retain its exact paired evidence" }
        val joined = paired.joinedState
        val base = Base(
            joined.sessionId.value,
            joined.evidenceSequence,
            joined.ownershipRevision,
            joined.shadowState.timing.captureTimestamp,
        )
        if (validation is RikkaDecisionValidationResult.Terminated) {
            val noAction = validation.kind == "NO_ACTION"
            return finish(
                JinChanDryRunResult(
                    sessionId = base.sessionId, evidenceSequence = base.sequence,
                    ownershipRevision = base.revision, captureTimestamp = base.captureTimestamp,
                    decisionKind = if (noAction) "NoAction" else "Blocked",
                    terminalReason = if (noAction) JinChanDryRunTerminalReason.RIKKA_NO_ACTION else JinChanDryRunTerminalReason.RIKKA_BLOCKED,
                ),
            )
        }
        validation as RikkaDecisionValidationResult.ValidatedAction
        val intent = validation.intent
        val uid = when (intent) {
            is JinChanActionIntent.MoveUnit -> intent.uid
            is JinChanActionIntent.SellUnit -> intent.uid
            else -> return finish(result(base, intent, terminal = JinChanDryRunTerminalReason.INVALID_ACTION))
        }
        val source = JinChanActionSourceEvidence(base.sessionId, base.sequence, base.revision, uid, validation.sourceLocation)
        val exactUnit = joined.ownershipSnapshot.activeUnits.singleOrNull { it.uid == uid }
        if (
            validation.provenance.sessionId != source.sessionId ||
            validation.provenance.evidenceSequence != source.evidenceSequence ||
            validation.provenance.ownershipRevision != source.ownershipRevision ||
            exactUnit?.location != source.sourceLocation || joined.actionContext.ownership !== joined.ownershipSnapshot
        ) return finish(result(base, intent, uid, source.sourceLocation, terminal = JinChanDryRunTerminalReason.PROVENANCE_REJECTED))

        return when (val gate = JinChanActionSafetyGate.evaluate(intent, joined.actionContext)) {
            is JinChanActionGateResult.Rejected -> finish(
                result(base, intent, uid, source.sourceLocation, "REJECTED", gate.reason.name, terminal = JinChanDryRunTerminalReason.GATE_REJECTED),
            )
            is JinChanActionGateResult.Approved -> when (
                val resolved = JinChanGestureResolver.resolve(JinChanGestureResolverInput(gate.action, coordinateProfile, source.sourceLocation))
            ) {
                is JinChanGestureResolveResult.Blocked -> {
                    val unavailable = resolved.reason.name == "PROFILE_UNAVAILABLE"
                    finish(result(base, intent, uid, source.sourceLocation, "WOULD_PASS", resolverStatus = "BLOCKED", resolverReason = resolved.reason.name, terminal = if (unavailable) JinChanDryRunTerminalReason.RESOLVER_PROFILE_UNAVAILABLE else JinChanDryRunTerminalReason.RESOLVER_BLOCKED))
                }
                is JinChanGestureResolveResult.Resolved -> finish(result(base, intent, uid, source.sourceLocation, "WOULD_PASS", resolverStatus = "WOULD_RESOLVE", profileId = resolved.provenance.profileId, terminal = JinChanDryRunTerminalReason.WOULD_RESOLVE))
            }
        }
    }

    private fun result(base: Base, intent: JinChanActionIntent, uid: Long? = null, source: UnitLocation? = null, gateStatus: String = "NOT_EVALUATED", gateReason: String? = null, resolverStatus: String = "NOT_EVALUATED", resolverReason: String? = null, profileId: String? = null, terminal: JinChanDryRunTerminalReason) = JinChanDryRunResult(
        sessionId = base.sessionId, evidenceSequence = base.sequence, ownershipRevision = base.revision,
        captureTimestamp = base.captureTimestamp, decisionKind = "Action", intentType = intent::class.simpleName,
        uid = uid, sourceLocation = source, targetLocation = (intent as? JinChanActionIntent.MoveUnit)?.destination,
        gateStatus = gateStatus, gateReason = gateReason, resolverStatus = resolverStatus,
        resolverReason = resolverReason, profileId = profileId, terminalReason = terminal,
    )

    private fun finish(result: JinChanDryRunResult): JinChanDryRunResult {
        val event = when (result.terminalReason) {
            JinChanDryRunTerminalReason.RIKKA_NO_ACTION -> "RIKKA_NO_ACTION"
            JinChanDryRunTerminalReason.RIKKA_BLOCKED -> "RIKKA_BLOCKED"
            JinChanDryRunTerminalReason.GATE_REJECTED -> "GATE_REJECTED"
            JinChanDryRunTerminalReason.RESOLVER_PROFILE_UNAVAILABLE -> "PROFILE_UNAVAILABLE"
            JinChanDryRunTerminalReason.RESOLVER_BLOCKED -> "RESOLVER_BLOCKED"
            JinChanDryRunTerminalReason.WOULD_RESOLVE -> "WOULD_RESOLVE"
            else -> result.terminalReason.name
        }
        RikkaDryRunDiagnostics.event(event)
        if (result.gateStatus == "WOULD_PASS") RikkaDryRunDiagnostics.event("GATE_WOULD_PASS")
        RikkaDryRunDiagnostics.event("DRY_RUN_HARD_STOP")
        AppLog.i("jinchan-c5", "event=$event terminal=${result.terminalReason.name} sessionId=${result.sessionId} evidenceSequence=${result.evidenceSequence} ownershipRevision=${result.ownershipRevision} uid=${result.uid} gate=${result.gateStatus} resolver=${result.resolverStatus} realActionReachable=false actionExecuted=0 event2=DRY_RUN_HARD_STOP")
        return result
    }

    private data class Base(val sessionId: Long, val sequence: Long, val revision: Long, val captureTimestamp: Long)
}

/** C4D rejection response mapper. It deliberately does not enter [JinChanDryRunValidator]. */
fun rikkaRejectedResult(reason: RikkaDecisionRejection) = JinChanDryRunResult(
    decisionKind = "Rejected",
    terminalReason = when (reason) {
        RikkaDecisionRejection.NO_PAIRED_OBSERVATION -> JinChanDryRunTerminalReason.NO_PAIRED_OBSERVATION
        RikkaDecisionRejection.PROVENANCE_MISMATCH -> JinChanDryRunTerminalReason.PROVENANCE_REJECTED
        RikkaDecisionRejection.UID_NOT_UNIQUE_ACTIVE -> JinChanDryRunTerminalReason.UID_REJECTED
        RikkaDecisionRejection.INVALID_ACTION -> JinChanDryRunTerminalReason.INVALID_ACTION
    },
)
