package top.azek431.hzzs.mcp.executor

import javax.inject.Inject
import org.json.JSONObject
import top.azek431.hzzs.data.jinchan.bridge.RikkaBridgeStore
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionValidationResult
import top.azek431.hzzs.data.jinchan.bridge.parseRikkaDecision
import top.azek431.hzzs.data.jinchan.bridge.toJson
import top.azek431.hzzs.mcp.requireString

/** MCP adapter only: accepted actions terminate as typed validation results. */
class RikkaBridgeExecutor @Inject constructor(private val store: RikkaBridgeStore) : ToolExecutor {
    override val toolNames = setOf("submit_rikka_decision_v1")

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject {
        require(tool in toolNames)
        return when (val result = store.validate(parseRikkaDecision(JSONObject(arguments.requireString("decision"))))) {
            is RikkaDecisionValidationResult.ValidatedAction -> JSONObject()
                .put("status", "VALIDATED_ACTION_TERMINATED")
                .put("intent", result.intent::class.simpleName)
                .put("sourceLocation", result.sourceLocation.toJson())
                .put("c3Called", false)
                .put("executed", false)
            is RikkaDecisionValidationResult.Terminated -> JSONObject()
                .put("status", result.kind)
                .put("c3Called", false)
                .put("executed", false)
            is RikkaDecisionValidationResult.Rejected -> JSONObject()
                .put("status", "REJECTED")
                .put("reason", result.reason.name)
                .put("c3Called", false)
                .put("executed", false)
        }
    }
}
