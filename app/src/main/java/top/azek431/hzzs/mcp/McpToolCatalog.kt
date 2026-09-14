package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 描述驱动的 MCP 工具/资源目录。
 *
 * 严格 JSON Schema（禁止 additionalProperties:true 的空对象），
 * 便于 RikkaHub / OperitAI / Claude 等客户端正确生成 arguments。
 */

enum class McpToolRisk {
    /** 只读，不经写审批。 */
    READ,

    /** 普通写操作，受权限级门控。 */
    WRITE,

    /** 高风险写：TRUSTED_SESSION 仍拒绝，需 FULL_ACCESS 或每次确认。 */
    HIGH_RISK,
}

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val risk: McpToolRisk,
    val inputSchema: JSONObject,
    val required: List<String> = emptyList(),
)

data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
)

object McpToolCatalog {
    private fun objSchema(
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("additionalProperties", false)
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    private fun emptyObjectSchema(): JSONObject = objSchema()

    private fun stringProp(description: String, enumValues: List<String>? = null): JSONObject =
        JSONObject().put("type", "string").put("description", description).also { o ->
            if (enumValues != null) o.put("enum", JSONArray(enumValues))
        }

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun numberProp(description: String): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    val tools: List<McpToolDescriptor> = listOf(
        McpToolDescriptor(
            name = "get_status",
            description = "读取截图运行时状态（是否运行、后端、FPS、截图就绪等）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "inspect",
            description = "一键诊断聚合：status + cleanBase + automationGates + permissions + mcp + version；" +
                "include 可选子集（逗号分隔）裁剪输出，减少传输",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "include",
                    stringProp(
                        "逗号分隔子集：status,cleanbase,gates,permissions,mcp,version；" +
                            "默认全部",
                    ),
                ),
            ),
        ),
        McpToolDescriptor(
            name = "get_runtime_snapshot",
            description = "读取截图运行时聚合快照（状态、自动操作门控）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "get_settings",
            description = "读取完整应用设置 JSON",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "preview_settings",
            description = "临时预览设置；离开设置页可恢复。权限型字段会按安全策略收敛",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "config",
                    stringProp("完整 AppConfig JSON 字符串"),
                ),
                required = listOf("config"),
            ),
            required = listOf("config"),
        ),
        McpToolDescriptor(
            name = "save_settings",
            description = "永久保存设置。权限型字段会按安全策略收敛，不能静默开启自动操作或自提 MCP 权限",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "config",
                    stringProp("完整 AppConfig JSON 字符串"),
                ),
                required = listOf("config"),
            ),
            required = listOf("config"),
        ),
        McpToolDescriptor(
            name = "patch_settings",
            description = "白名单局部改设置（主题/悬浮窗/截图与手势后端等）；比整包 JSON 更安全。" +
                "支持 patches（点分路径→值）与 operations（[{path,value,op}]，op: set/add/remove/toggle）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "patches",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "description",
                                "点分路径→值；未知键由服务端白名单拒绝",
                            )
                            .put("additionalProperties", false),
                    )
                    .put(
                        "operations",
                        JSONObject()
                            .put("type", "array")
                            .put("description", "批量操作：[{path,value?,op}]；op: set/add/remove/toggle")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", false)
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("path", stringProp("点分路径"))
                                            .put("value", JSONObject().put("description", "set/add/remove 必填；toggle 省略"))
                                            .put(
                                                "op",
                                                stringProp("操作类型", listOf("set", "add", "remove", "toggle")),
                                            ),
                                    )
                                    .put("required", JSONArray(listOf("path", "op"))),
                            ),
                    )
                    .put("persist", boolProp("true=永久保存，false=仅预览（默认 true）")),
            ),
            required = emptyList(),
        ),
        McpToolDescriptor(
            name = "reset_preview",
            description = "清除临时预览并恢复已保存配置",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "start_analysis",
            description = "请求启动屏幕分析（仍受截图权限与运行时门控）",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "stop_analysis",
            description = "停止屏幕分析",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "navigate",
            description = "打开应用内页面。一级：home/runtime/settings/about；" +
                "设置子页：settings/mcp、settings/developer、appearance、log_viewer 等",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "route",
                    stringProp(
                        "路由：home/runtime/settings/about，或 settings/<子页>、mcp、developer、log_viewer 等",
                    ),
                ),
                required = listOf("route"),
            ),
            required = listOf("route"),
        ),
        McpToolDescriptor(
            name = "cancel_actions",
            description = "取消在飞自动操作手势队列（不停止分析）",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "restart_analysis",
            description = "停止再启动分析（切换截图后端/算法后常用）",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "set_overlay_visible",
            description = "临时显示或隐藏悬浮窗（预览级，不永久保存）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put("enabled", boolProp("是否显示悬浮窗")),
            ),
        ),
        McpToolDescriptor(
            name = "set_capture_backend",
            description = "切换截图后端（AUTO/MEDIA_PROJECTION/ACCESSIBILITY/SHIZUKU/ROOT）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "backend",
                        stringProp(
                            "截图后端",
                            listOf(
                                "AUTO",
                                "MEDIA_PROJECTION",
                                "ACCESSIBILITY",
                                "SHIZUKU",
                                "ROOT",
                            ),
                        ),
                    )
                    .put("persist", boolProp("是否永久保存（默认 true）")),
                required = listOf("backend"),
            ),
            required = listOf("backend"),
        ),
        McpToolDescriptor(
            name = "set_gesture_backend",
            description = "切换手势注入后端（AUTO/ACCESSIBILITY/SHIZUKU/ROOT，与截图独立）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "backend",
                        stringProp(
                            "手势后端",
                            listOf("AUTO", "ACCESSIBILITY", "SHIZUKU", "ROOT"),
                        ),
                    )
                    .put("persist", boolProp("是否永久保存（默认 true）")),
                required = listOf("backend"),
            ),
            required = listOf("backend"),
        ),
        McpToolDescriptor(
            name = "run_diagnostics",
            description = "运行诊断：状态、配置可读性、Native 是否加载、调试帧数量",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "list_debug_frames",
            description = "列出私有目录中的调试帧元数据（需开发者选项 + MCP 允许）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "clear_debug_frames",
            description = "清除私有目录中的调试帧",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "get_version",
            description = "读取应用版本 / 配置 schema / 设备信息（versionName、versionCode、schema、buildType、manufacturer、sdkInt、abi）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "check_update",
            description = "检查应用更新（包装 UpdateRepository.check）；失败返回 error 而非抛异常。严格遵循 update.wifiOnly，不可 force 绕过",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "get_metrics",
            description = "运行时指标：内存（Runtime）+ 帧（fps/processingMs 滑动窗口）+ 进程 uptime",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "get_debug_frame",
            description = "读取指定调试帧的 JPEG（按 maxWidth 等比降采样、quality 重编码），base64 内嵌返回。" +
                "需开发者选项 + allowDebugFrames",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("name", stringProp("调试帧文件名，如 frame_123_1080x2400.jpg"))
                    .put("maxWidth", intProp("降采样最大边长（默认 480，范围 64..1080）"))
                    .put("quality", intProp("JPEG 质量 10-100（默认 70）")),
                required = listOf("name"),
            ),
            required = listOf("name"),
        ),
        McpToolDescriptor(
            name = "capture_debug_frame",
            description = "强制存下一帧到调试帧目录（绕过保存间隔与 saveDebugFrames；仍需开发者总开关）。含屏幕内容落盘，HIGH_RISK",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "set_theme",
            description = "调整主题 mode/preset/dynamicColor/reduceMotion 等",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "mode",
                        stringProp("SYSTEM/LIGHT/DARK/AMOLED", listOf("SYSTEM", "LIGHT", "DARK", "AMOLED")),
                    )
                    .put("preset", stringProp("主题预设枚举名"))
                    .put("dynamicColorEnabled", boolProp("动态取色"))
                    .put("reduceMotion", boolProp("减少动效"))
                    .put("highContrast", boolProp("高对比"))
                    .put("fontScale", numberProp("字体倍率"))
                    .put("animationScale", numberProp("动画倍率"))
                    .put("customSeed", stringProp("自定义种子色 hex"))
                    .put("persist", boolProp("是否永久保存（默认 true）")),
            ),
        ),
        McpToolDescriptor(
            name = "set_overlay",
            description = "调整悬浮窗样式/显示项",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("enabled", boolProp("总开关"))
                    .put(
                        "style",
                        stringProp("MINIMAL/COMPACT/DEBUG_HUD", listOf("MINIMAL", "COMPACT", "DEBUG_HUD")),
                    )
                    .put("theme", stringProp("悬浮窗主题枚举"))
                    .put("showBoxes", boolProp("检测框"))
                    .put("persistBoxes", boolProp("检测框持久绘制（丢检短时保留）"))
                    .put("showText", boolProp("文字"))
                    .put("showFps", boolProp("FPS"))
                    .put("showConfidence", boolProp("置信度"))
                    .put("showDiagnostics", boolProp("诊断"))
                    .put("backgroundAlpha", numberProp("背景透明度 0..1"))
                    .put("scale", numberProp("整体缩放"))
                    .put("persist", boolProp("是否永久保存（默认 true）")),
            ),
        ),
        McpToolDescriptor(
            name = "set_developer_enabled",
            description = "开启或关闭开发者选项（HIGH_RISK）",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject().put("enabled", boolProp("是否启用开发者选项")),
                required = listOf("enabled"),
            ),
            required = listOf("enabled"),
        ),
        McpToolDescriptor(
            name = "set_developer_options",
            description = "调整开发者项日志/诊断/调试帧等（需已解锁）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "logLevel",
                        stringProp(
                            "日志级别",
                            listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR"),
                        ),
                    )
                    .put("saveDebugFrames", boolProp("保存调试帧"))
                    .put("showCoordinateGrid", boolProp("坐标网格"))
                    .put("frameRateLimit", intProp("保留字段"))
                    .put("logRingCapacity", intProp("AppLog ring 容量 [500,3000]"))
                    .put("enableStageTiming", boolProp("阶段耗时细分"))
                    .put("enableMulticolorDiagnostic", boolProp("多点找色诊断"))
                    .put("enableFilterTrace", boolProp("过滤原因追踪"))
                    .put("forceCaptureBackend", stringProp("强制截图后端；空字符串清除"))
                    .put("persist", boolProp("是否永久保存（默认 true）")),
            ),
        ),
        McpToolDescriptor(
            name = "get_automation_gates",
            description = "解释自动操作为何可/不可派发",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "set_automation_enabled",
            description = "开启或关闭自动操作（HIGH_RISK；开启须免责声明）",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("enabled", boolProp("是否启用自动操作"))
                    .put("acceptDisclaimer", boolProp("开启时确认风险并写入免责版本")),
                required = listOf("enabled"),
            ),
            required = listOf("enabled"),
        ),
        McpToolDescriptor(
            name = "get_logs",
            description = "读取内存日志 ring（需开发者选项）",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put(
                        "minLevel",
                        stringProp(
                            "最低级别",
                            listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR"),
                        ),
                    )
                    .put("tag", stringProp("精确 tag"))
                    .put("query", stringProp("子串过滤"))
                    .put("limit", intProp("最大条数，默认 100"))
                    .put("newestFirst", boolProp("新在前，默认 true")),
            ),
        ),
        McpToolDescriptor(
            name = "clear_logs",
            description = "清空内存日志 ring（需开发者选项）",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "export_diagnostics",
            description = "导出脱敏诊断文本",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject().put("logLimit", intProp("附带日志条数，默认 200")),
            ),
        ),
        McpToolDescriptor(
            name = "get_permissions",
            description = "系统悬浮窗 / 无障碍连接状态",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "open_system_settings",
            description = "跳转系统设置页（不静默授权）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "target",
                    stringProp("目标页", listOf("overlay", "accessibility", "app_details")),
                ),
                required = listOf("target"),
            ),
            required = listOf("target"),
        ),
        McpToolDescriptor(
            name = "get_events",
            description = "拉取运行时事件（启停 / 算法切换 / 配置变化 / 错误等），支持 since 增量",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("since", intProp("只返回 seq > since 的事件，默认 0（全部）；溢出返回 dropped 标志"))
                    .put("limit", intProp("条数，默认 50，最大 200")),
            ),
        ),
        // —— 命名配置 Profile ——
        McpToolDescriptor(
            name = "save_profile",
            description = "将当前生效配置保存为命名 profile（filesDir/mcp-profiles/）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("name", stringProp("profile 名，[A-Za-z0-9_-]{1,64}"))
                    .put("description", stringProp("可选描述")),
                required = listOf("name"),
            ),
            required = listOf("name"),
        ),
        McpToolDescriptor(
            name = "load_profile",
            description = "读取 profile → preview 或永久保存",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("name", stringProp("profile 名"))
                    .put("persist", boolProp("true=永久保存，false=仅预览（默认 false）")),
                required = listOf("name"),
            ),
            required = listOf("name"),
        ),
        McpToolDescriptor(
            name = "list_profiles",
            description = "列出已保存的 profile 元数据（名字/描述/时间/大小）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "delete_profile",
            description = "删除指定 profile",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put("name", stringProp("profile 名")),
                required = listOf("name"),
            ),
            required = listOf("name"),
        ),
        // —— MCP 工具管理（自管）——
        McpToolDescriptor(
            name = "get_mcp_status",
            description = "读取 MCP 运行态、全局权限、鉴权开关、工具策略摘要（不含完整 Token）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "list_mcp_tools",
            description = "列出全部 MCP 工具：中文标题、准确工具名、风险、策略、是否启用",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("includeDisabled", boolProp("是否包含已禁用工具（默认 true）")),
            ),
        ),
        McpToolDescriptor(
            name = "get_mcp_access_log",
            description = "读取 MCP 访问日志 ring（method/工具/状态/耗时；不含 Token 与参数体）",
            risk = McpToolRisk.READ,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("limit", intProp("条数，默认 50，最大 200"))
                    .put("newestFirst", boolProp("新在前，默认 true")),
            ),
        ),
        McpToolDescriptor(
            name = "clear_mcp_access_log",
            description = "清空 MCP 访问日志 ring",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "set_mcp_enabled",
            description = "开启或关闭 MCP 本地服务（mcp.enabled）。关闭会使当前连接断开",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject().put("enabled", boolProp("是否启用 MCP 服务")),
                required = listOf("enabled"),
            ),
            required = listOf("enabled"),
        ),
        McpToolDescriptor(
            name = "set_mcp_permission_level",
            description = "设置全局权限级 READ_ONLY/ASK_EVERY_TIME/TRUSTED_SESSION/FULL_ACCESS（防自提权，HIGH_RISK）",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "permissionLevel",
                    stringProp(
                        "权限级",
                        listOf("READ_ONLY", "ASK_EVERY_TIME", "TRUSTED_SESSION", "FULL_ACCESS"),
                    ),
                ),
                required = listOf("permissionLevel"),
            ),
            required = listOf("permissionLevel"),
        ),
        McpToolDescriptor(
            name = "set_mcp_auth",
            description = "设置是否要求 Bearer 鉴权；不返回完整 Token，仅 tokenConfigured 标志",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("requireAuth", boolProp("是否要求 Authorization: Bearer"))
                    .put(
                        "rotateToken",
                        boolProp("true=主动轮换配对令牌（仅 requireAuth 时有意义，默认 false）"),
                    ),
                required = listOf("requireAuth"),
            ),
            required = listOf("requireAuth"),
        ),
        McpToolDescriptor(
            name = "set_mcp_tool_policy",
            description = "设置单个工具策略：DEFAULT/ALWAYS_ASK/ALLOW_WHEN_TRUSTED/DISABLED",
            risk = McpToolRisk.HIGH_RISK,
            inputSchema = objSchema(
                properties = JSONObject()
                    .put("tool", stringProp("准确工具名，如 start_analysis"))
                    .put(
                        "policy",
                        stringProp(
                            "策略",
                            listOf("DEFAULT", "ALWAYS_ASK", "ALLOW_WHEN_TRUSTED", "DISABLED"),
                        ),
                    ),
                required = listOf("tool", "policy"),
            ),
            required = listOf("tool", "policy"),
        ),
    )

    val resources: List<McpResourceDescriptor> = listOf(
        McpResourceDescriptor("app://status", "status", "当前运行状态"),
        McpResourceDescriptor("app://runtime/snapshot", "runtime/snapshot", "运行态 + Clean Base 标记 + 门闩聚合"),
        McpResourceDescriptor("app://settings/schema", "settings/schema", "设置 schema 摘要"),
        McpResourceDescriptor("app://settings/current", "settings/current", "当前完整设置"),
        McpResourceDescriptor("app://runtime/metrics", "runtime/metrics", "运行指标"),
        McpResourceDescriptor("app://debug/frames", "debug/frames", "调试帧元数据"),
        McpResourceDescriptor("app://automation/gates", "automation/gates", "自动操作门闩"),
        McpResourceDescriptor("app://permissions", "permissions", "系统权限状态"),
        McpResourceDescriptor("app://logs/recent", "logs/recent", "最近日志（需开发者）"),
        McpResourceDescriptor("app://mcp/status", "mcp/status", "MCP 服务与工具策略状态"),
        McpResourceDescriptor("app://events", "events", "运行时事件流（启停/配置/错误）"),
    )

    private val byName = tools.associateBy { it.name }

    fun tool(name: String): McpToolDescriptor? = byName[name]

    fun knownToolNames(): Set<String> = byName.keys

    /**
     * 全量 tools / resources JSON 缓存。
     * 调用方只读（协议层写入 HTTP 响应前 toString）；禁止 mutate 返回值。
     */
    @Volatile
    private var fullToolsJsonCache: JSONArray? = null

    @Volatile
    private var resourcesJsonCache: JSONArray? = null

    /**
     * @param include 要暴露的工具集合；默认全量。策略过滤时传入 [McpToolPolicySupport.effectiveTools]。
     */
    fun toolsJson(
        include: Collection<McpToolDescriptor> = tools,
    ): JSONArray {
        if (include === tools || (include is List && include.size == tools.size && include == tools)) {
            fullToolsJsonCache?.let { return it }
            return buildToolsJson(tools).also { fullToolsJsonCache = it }
        }
        return buildToolsJson(include)
    }

    fun resourcesJson(): JSONArray {
        resourcesJsonCache?.let { return it }
        return JSONArray().apply {
            resources.forEach { resource ->
                put(
                    JSONObject()
                        .put("uri", resource.uri)
                        .put("name", resource.name)
                        .put("description", resource.description)
                        .put("mimeType", resource.mimeType),
                )
            }
        }.also { resourcesJsonCache = it }
    }

    private fun buildToolsJson(include: Collection<McpToolDescriptor>): JSONArray =
        JSONArray().apply {
            include.forEach { tool ->
                put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", McpToolLabels.clientDescription(tool))
                        .put("inputSchema", tool.inputSchema),
                )
            }
        }
}
