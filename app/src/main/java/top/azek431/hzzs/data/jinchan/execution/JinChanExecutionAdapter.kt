package top.azek431.hzzs.data.jinchan.execution

import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.domain.automation.AutomationAction

data class JinChanExecutionAdapterInput(
    val resolved: JinChanGestureResolveResult.Resolved,
    val actionId: Long,
    val trackId: Long,
    val createdAtUptimeMs: Long,
    val expiresAtUptimeMs: Long,
)

enum class JinChanExecutionAdapterRejectionReason {
    INVALID_TARGET_PACKAGE,
    INVALID_ACTION_ID,
    INVALID_TRACK_ID,
    INVALID_TIME_WINDOW,
}

sealed interface JinChanExecutionAdapterResult {
    data class Ready(
        val resolved: JinChanGestureResolveResult.Resolved,
        val automationAction: AutomationAction,
    ) : JinChanExecutionAdapterResult

    data class Rejected(
        val reason: JinChanExecutionAdapterRejectionReason,
    ) : JinChanExecutionAdapterResult
}

/** Pure H6-C1 value adapter. It deliberately does not dispatch or inspect Android runtime state. */
object JinChanExecutionAdapter {
    fun adapt(input: JinChanExecutionAdapterInput): JinChanExecutionAdapterResult {
        if (input.resolved.action.provenance.targetPackage != JinChanActionSafetyGate.JINCHAN_PACKAGE) {
            return JinChanExecutionAdapterResult.Rejected(
                JinChanExecutionAdapterRejectionReason.INVALID_TARGET_PACKAGE,
            )
        }
        if (input.actionId <= 0L) {
            return JinChanExecutionAdapterResult.Rejected(
                JinChanExecutionAdapterRejectionReason.INVALID_ACTION_ID,
            )
        }
        if (input.trackId <= 0L) {
            return JinChanExecutionAdapterResult.Rejected(
                JinChanExecutionAdapterRejectionReason.INVALID_TRACK_ID,
            )
        }
        if (input.createdAtUptimeMs > input.expiresAtUptimeMs) {
            return JinChanExecutionAdapterResult.Rejected(
                JinChanExecutionAdapterRejectionReason.INVALID_TIME_WINDOW,
            )
        }

        val automationAction = AutomationAction(
            id = input.actionId,
            trackId = input.trackId,
            gesture = input.resolved.gesture,
            createdAtUptimeMs = input.createdAtUptimeMs,
            expiresAtUptimeMs = input.expiresAtUptimeMs,
            allowedPackages = setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE),
            requiredWindowClassPrefixes = emptySet(),
            retryCount = 0,
        )
        return JinChanExecutionAdapterResult.Ready(
            resolved = input.resolved,
            automationAction = automationAction,
        )
    }
}
