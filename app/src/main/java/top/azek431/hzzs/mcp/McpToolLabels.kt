package top.azek431.hzzs.mcp

import org.json.JSONObject

/**
 * MCP 工具中文短标题（设置页 / 审批弹窗 / tools/list 首行）。
 * 键必须与 [McpToolCatalog] 工具名完全一致。
 */
object McpToolLabels {
    private val titles: Map<String, String> = mapOf(
        "get_status" to "读取运行状态",
        "get_runtime_snapshot" to "读取运行态快照",
        "inspect" to "一键诊断聚合",
        "get_settings" to "读取完整设置",
        "preview_settings" to "临时预览设置",
        "save_settings" to "永久保存设置",
        "patch_settings" to "白名单局部改设置",
        "reset_preview" to "清除临时预览",
        "start_analysis" to "启动截图运行时",
        "stop_analysis" to "停止截图运行时",
        "navigate" to "打开应用内页面",
        "cancel_actions" to "取消在飞手势（Clean Base 空操作）",
        "restart_analysis" to "重启截图运行时",
        "set_overlay_visible" to "临时显示/隐藏悬浮窗",
        "set_capture_backend" to "切换截图后端",
        "set_gesture_backend" to "切换手势后端",
        "get_version" to "读取应用版本与设备信息",
        "check_update" to "检查应用更新",
        "get_metrics" to "读取运行时指标（内存/帧/uptime）",
        "run_diagnostics" to "运行诊断",
        "list_debug_frames" to "列出调试帧元数据",
        "clear_debug_frames" to "清除调试帧",
        "get_debug_frame" to "读取调试帧（base64 JPEG）",
        "capture_debug_frame" to "强制存下一帧调试帧",
        "set_theme" to "调整应用主题",
        "set_overlay" to "调整悬浮窗样式",
        "set_developer_enabled" to "开/关开发者选项",
        "set_developer_options" to "调整开发者选项",
        "get_automation_gates" to "解释自动操作门闩",
        "set_automation_enabled" to "开/关自动操作",
        "get_logs" to "读取内存日志",
        "clear_logs" to "清空内存日志",
        "export_diagnostics" to "导出脱敏诊断",
        "get_permissions" to "读取系统权限状态",
        "open_system_settings" to "打开系统设置页",
        // MCP 自管
        "get_mcp_status" to "读取 MCP 服务状态",
        "list_mcp_tools" to "列出 MCP 工具与策略",
        "save_profile" to "保存命名配置 profile",
        "load_profile" to "读取 profile（预览/保存）",
        "list_profiles" to "列出 profile 元数据",
        "delete_profile" to "删除 profile",
        "get_events" to "拉取运行时事件",
        "get_mcp_access_log" to "读取 MCP 访问日志",
        "clear_mcp_access_log" to "清空 MCP 访问日志",
        "set_mcp_enabled" to "开/关 MCP 服务",
        "set_mcp_permission_level" to "设置 MCP 全局权限级",
        "set_mcp_auth" to "设置 MCP Bearer 鉴权",
        "set_mcp_tool_policy" to "设置单工具策略",
    )

    fun titleZh(toolName: String): String = titles[toolName] ?: toolName

    /** 客户端 / 审批用完整描述：中文标题 + 准确工具名 + 详情。 */
    fun clientDescription(tool: McpToolDescriptor): String =
        "${titleZh(tool.name)}\n工具名: ${tool.name}\n${tool.description}"

    fun approvalLabel(toolName: String): String =
        "${titleZh(toolName)}\n工具名: $toolName"

    /** 审批用一句话摘要，包含关键参数（不记 Token/完整参数体，符合访问日志约束）。 */
    fun summaryZh(toolName: String, arguments: JSONObject): String = when (toolName) {
        "save_settings" -> "AI 请求永久保存应用设置"
        "preview_settings" -> "AI 请求临时预览应用设置"
        "patch_settings" -> "AI 请求局部修改设置"
        "start_analysis" -> "AI 请求启动屏幕分析"
        "stop_analysis" -> "AI 请求停止屏幕分析"
        "restart_analysis" -> "AI 请求重启屏幕分析"
        "cancel_actions" -> "AI 请求取消在飞自动操作"
        "navigate" -> "AI 请求打开应用页面：${arguments.optString("route")}"
        "set_overlay_visible" -> "AI 请求更改悬浮窗显示状态"
        "set_capture_backend" -> "AI 请求切换截图后端：${arguments.optString("backend")}"
        "set_gesture_backend" -> "AI 请求切换手势后端：${arguments.optString("backend")}"
        "clear_debug_frames" -> "AI 请求清除本机调试帧"
        "get_debug_frame" -> "AI 请求读取调试帧图像：${arguments.optString("name")}"
        "capture_debug_frame" -> "AI 请求强制保存下一帧调试截图"
        "save_profile" -> "AI 请求保存命名配置：${arguments.optString("name")}"
        "load_profile" ->
            "AI 请求${if (arguments.optBoolean("persist")) "永久应用" else "预览"}配置 profile：${arguments.optString("name")}"
        "delete_profile" -> "AI 请求删除配置 profile：${arguments.optString("name")}"
        "set_mcp_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "启用" else "关闭"} MCP 服务"
        "set_mcp_permission_level" ->
            "AI 请求修改 MCP 权限级：${arguments.optString("permissionLevel")}"
        "set_mcp_auth" -> "AI 请求修改 MCP Bearer 鉴权"
        "set_mcp_tool_policy" ->
            "AI 请求设置工具策略：${arguments.optString("tool")}=${arguments.optString("policy")}"
        "clear_mcp_access_log" -> "AI 请求清空 MCP 访问日志"
        "set_theme" -> "AI 请求修改主题"
        "set_overlay" -> "AI 请求修改悬浮窗"
        "set_developer_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "开启" else "关闭"}开发者选项"
        "set_developer_options" -> "AI 请求修改开发者选项"
        "set_automation_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "开启" else "关闭"}自动操作"
        "clear_logs" -> "AI 请求清空内存日志"
        "open_system_settings" -> "AI 请求打开系统设置：${arguments.optString("target")}"
        else -> "AI 请求执行：$toolName（${arguments.length()} 个参数）"
    }
}
