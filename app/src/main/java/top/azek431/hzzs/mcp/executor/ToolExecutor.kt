package top.azek431.hzzs.mcp.executor

import org.json.JSONObject

/**
 * MCP 工具执行器接口。
 *
 * 每个 [ToolExecutor] 负责一组内聚的工具（按 feature 分组，如运行时控制 / 设置写入 / 调试帧 / 系统）。
 * [McpActionRegistry] 作为门面（facade）负责参数校验 ([top.azek431.hzzs.mcp.McpActionRegistry.validateArguments])
 * 与权限仲裁 ([top.azek431.hzzs.mcp.McpActionRegistry.authorize])，再按工具名分发到对应执行器。
 *
 * 约定：
 * - [execute] 调用前参数已校验、权限已拒绝（READ_ONLY/HIGH_RISK/DISABLED）或已走手机审批。
 * - 执行器**不得**自行读取 [top.azek431.hzzs.core.preferences.SettingsRepository] 做权限判断；权限归 [top.azek431.hzzs.mcp.McpActionRegistry]。
 * - 同一工具名只能属于一个执行器（构造注册表时校验）。
 */
interface ToolExecutor {
    /** 此执行器负责的工具名集合（非空，全局唯一）。 */
    val toolNames: Set<String>

    /**
     * 执行指定工具。
     *
     * @param tool 工具名（保证属于 [toolNames]）
     * @param arguments 已校验的参数
     * @return JSON 结果（协议层会包装进 content[].text）
     */
    suspend fun execute(tool: String, arguments: JSONObject): JSONObject
}
