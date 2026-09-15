package top.azek431.hzzs.mcp.executor

import javax.inject.Inject
import org.json.JSONObject
import top.azek431.hzzs.data.jinchan.bridge.RikkaBridgeStore
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionValidationResult
import top.azek431.hzzs.data.jinchan.bridge.parseRikkaDecision
import top.azek431.hzzs.data.jinchan.dryrun.JinChanDryRunValidator
import top.azek431.hzzs.data.jinchan.dryrun.rikkaRejectedResult
import top.azek431.hzzs.mcp.requireString

/** MCP adapter only: accepted actions terminate as typed validation results. */
class RikkaBridgeExecutor @Inject constructor(
    private val store: RikkaBridgeStore,
    private val dryRunValidator: JinChanDryRunValidator,
) : ToolExecutor {
    override val toolNames = setOf(SUBMIT_DECISION_V1, GET_OBSERVATION_V1)

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject {
        require(tool in toolNames)
        if (tool == GET_OBSERVATION_V1) return latestObservation()
        val paired = store.validatePaired(parseRikkaDecision(JSONObject(arguments.requireString("decision"))))
        val result = (paired.result as? RikkaDecisionValidationResult.Rejected)
            ?.let { rikkaRejectedResult(it.reason) }
            ?: dryRunValidator.validate(paired, coordinateProfile = null)
        return result.toJson()
            .put("c3Called", false)
            .put("coordinatorCalled", false)
    }

    /**
     * C5 compatibility adapter (read-only).
     *
     * Exposes the exact immutable publication that `app://rikka/observation/v1/latest` already serves,
     * for clients that inject MCP Tools but cannot list/read MCP Resources.
     *
     * No re-assembly, no ledger re-read, no latest/StateFlow/cross-frame repair, no second cache.
     * Absent publication fails closed with a stable reason instead of fabricating an observation.
     */
    private fun latestObservation(): JSONObject {
        val observation = store.latest()?.observation
            ?: return JSONObject()
                .put("available", false)
                .put("observation", JSONObject.NULL)
                .put("reason", NO_OBSERVATION_REASON)
        return JSONObject()
            .put("available", true)
            .put("observation", observation.toJson())
            .put("reason", "OK")
    }

    companion object {
        const val SUBMIT_DECISION_V1 = "submit_rikka_decision_v1"
        const val GET_OBSERVATION_V1 = "get_rikka_observation_v1"
        const val NO_OBSERVATION_REASON = "NO_RIKKA_OBSERVATION"
    }
}
