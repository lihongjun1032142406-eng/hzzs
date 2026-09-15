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
    override val toolNames = setOf("submit_rikka_decision_v1")

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject {
        require(tool in toolNames)
        val paired = store.validatePaired(parseRikkaDecision(JSONObject(arguments.requireString("decision"))))
        val result = (paired.result as? RikkaDecisionValidationResult.Rejected)
            ?.let { rikkaRejectedResult(it.reason) }
            ?: dryRunValidator.validate(paired, coordinateProfile = null)
        return result.toJson()
            .put("c3Called", false)
            .put("coordinatorCalled", false)
    }
}
