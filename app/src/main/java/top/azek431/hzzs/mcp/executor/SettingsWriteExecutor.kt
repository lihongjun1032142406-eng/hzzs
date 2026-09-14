package top.azek431.hzzs.mcp.executor

import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.preferences.hardenedForExternalIngest
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.mcp.McpEventBus
import top.azek431.hzzs.mcp.McpSettingsPatch
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString
import javax.inject.Inject

/**
 * 设置写入执行器：主题 / 悬浮窗 / 截图与手势后端 / 开发者 / 自动操作。
 *
 * Clean Base：赛季 / 识别阈值 / 障碍类别等 HZZS 原游戏视觉算法配置已清退，
 * 且自动操作在 [AppConfig.ACTION_ENABLED] = false 下 fail-closed（无法真正派发动作）。
 *
 * 写操作统一经 [SettingsRepository]（preview 或 save）；[set_gesture_backend] 会同步取消在飞手势
 * （手势后端仅随已保存配置生效，草稿不派发）。
 */
class SettingsWriteExecutor @Inject constructor(
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "preview_settings",
        "save_settings",
        "patch_settings",
        "reset_preview",
        "set_theme",
        "set_overlay",
        "set_overlay_visible",
        "set_capture_backend",
        "set_gesture_backend",
        "set_developer_enabled",
        "set_developer_options",
        "set_automation_enabled",
    )

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "preview_settings" -> {
            settings.preview(ingestMcpConfig(arguments.requireString("config")))
            ok("已临时预览设置（权限型字段已按安全策略收敛）")
        }
        "save_settings" -> {
            settings.save(ingestMcpConfig(arguments.requireString("config")))
            runCatching { McpEventBus.append(McpEventBus.Type.CONFIG_CHANGE, JSONObject().put("source", "save_settings")) }
            ok("设置已保存（权限型字段已按安全策略收敛）")
        }
        "patch_settings" -> executePatchSettings(arguments)
        "reset_preview" -> {
            settings.clearPreview()
            ok("临时预览已恢复")
        }
        "set_theme" -> executeSetTheme(arguments)
        "set_overlay" -> executeSetOverlay(arguments)
        "set_overlay_visible" -> {
            val current = settings.current()
            settings.preview(
                current.copy(
                    overlay = current.overlay.copy(enabled = arguments.optBoolean("enabled", true)),
                ),
            )
            ok("悬浮窗显示状态已临时更新")
        }
        "set_capture_backend" -> executeSetCaptureBackend(arguments)
        "set_gesture_backend" -> executeSetGestureBackend(arguments)
        "set_developer_enabled" -> executeSetDeveloperEnabled(arguments)
        "set_developer_options" -> executeSetDeveloperOptions(arguments)
        "set_automation_enabled" -> executeSetAutomationEnabled(arguments)
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private suspend fun executePatchSettings(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patchesObj = arguments.optJSONObject("patches")
        val opsArr = arguments.optJSONArray("operations")
        val hasPatches = patchesObj != null && patchesObj.length() > 0
        val hasOps = opsArr != null && opsArr.length() > 0
        require(hasPatches || hasOps) { "patch_settings 需要非空 patches 或 operations" }
        var patched = settings.current()
        if (hasPatches) {
            patched = McpSettingsPatch.applyFromJson(patched, patchesObj!!)
        }
        if (hasOps) {
            val list = ArrayList<McpSettingsPatch.Op>(opsArr!!.length())
            for (i in 0 until opsArr.length()) {
                val op = opsArr.getJSONObject(i)
                val path = op.requireString("path")
                val opRaw = op.requireString("op").trim().uppercase(java.util.Locale.ROOT)
                val opType = try {
                    McpSettingsPatch.OpType.valueOf(opRaw)
                } catch (_: IllegalArgumentException) {
                    error("未知 op：$opRaw（支持 set/add/remove/toggle）")
                }
                list.add(
                    McpSettingsPatch.Op(
                        path = path,
                        value = if (op.isNull("value")) null else op.opt("value"),
                        operation = opType,
                    ),
                )
            }
            patched = McpSettingsPatch.applyOperations(patched, list)
        }
        if (persist) settings.save(patched) else settings.preview(patched)
        runCatching {
            McpEventBus.append(
                McpEventBus.Type.CONFIG_CHANGE,
                JSONObject().put("source", "patch_settings").put("persist", persist),
            )
        }
        return ok(if (persist) "局部设置已保存" else "局部设置已预览")
    }

    private suspend fun executeSetTheme(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        arguments.optString("mode").takeIf { it.isNotBlank() }?.let { patches.put("theme.mode", it) }
        arguments.optString("preset").takeIf { it.isNotBlank() }?.let { patches.put("theme.preset", it) }
        if (arguments.has("dynamicColorEnabled")) {
            patches.put("theme.dynamicColorEnabled", arguments.getBoolean("dynamicColorEnabled"))
        }
        if (arguments.has("reduceMotion")) {
            patches.put("theme.reduceMotion", arguments.getBoolean("reduceMotion"))
        }
        if (arguments.has("highContrast")) {
            patches.put("theme.highContrast", arguments.getBoolean("highContrast"))
        }
        if (arguments.has("fontScale")) patches.put("theme.fontScale", arguments.getDouble("fontScale"))
        if (arguments.has("animationScale")) {
            patches.put("theme.animationScale", arguments.getDouble("animationScale"))
        }
        arguments.optString("customSeed").takeIf { it.isNotBlank() }?.let {
            patches.put("theme.customSeed", it)
        }
        require(patches.length() > 0) { "未提供任何主题字段" }
        val patched = McpSettingsPatch.applyFromJson(settings.current(), patches)
        if (persist) settings.save(patched) else settings.preview(patched)
        return ok("主题已更新")
    }

    private suspend fun executeSetOverlay(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        if (arguments.has("enabled")) patches.put("overlay.enabled", arguments.getBoolean("enabled"))
        arguments.optString("style").takeIf { it.isNotBlank() }?.let { patches.put("overlay.style", it) }
        arguments.optString("theme").takeIf { it.isNotBlank() }?.let { patches.put("overlay.theme", it) }
        listOf(
            "showBoxes",
            "persistBoxes",
            "showText",
            "showFps",
            "showConfidence",
            "showDiagnostics",
        ).forEach { key ->
            if (arguments.has(key)) patches.put("overlay.$key", arguments.getBoolean(key))
        }
        if (arguments.has("backgroundAlpha")) {
            patches.put("overlay.backgroundAlpha", arguments.getDouble("backgroundAlpha"))
        }
        if (arguments.has("scale")) patches.put("overlay.scale", arguments.getDouble("scale"))
        require(patches.length() > 0) { "未提供任何悬浮窗字段" }
        val patched = McpSettingsPatch.applyFromJson(settings.current(), patches)
        if (persist) settings.save(patched) else settings.preview(patched)
        return ok("悬浮窗已更新")
    }

    private suspend fun executeSetCaptureBackend(arguments: JSONObject): JSONObject {
        val backend = enumValueOf<CaptureBackend>(arguments.requireString("backend"))
        val persist = arguments.optBoolean("persist", true)
        applyConfig({ it.copy(captureBackend = backend) }, persist)
        runCatching {
            McpEventBus.append(
                McpEventBus.Type.CAPTURE_BACKEND_CHANGE,
                JSONObject().put("backend", backend.name).put("persist", persist),
            )
        }
        return ok("截图后端已设为 ${backend.name}（建议 restart_analysis 生效）")
    }

    private suspend fun executeSetGestureBackend(arguments: JSONObject): JSONObject {
        val backend = enumValueOf<GestureBackend>(arguments.requireString("backend"))
        val persist = arguments.optBoolean("persist", true)
        if (!persist) {
            error(
                "手势后端仅随已保存配置生效：请 set_gesture_backend(persist=true) 或设置页「保存并应用」",
            )
        }
        applyConfig(
            { it.copy(automation = it.automation.copy(gestureBackend = backend)) },
            persist = true,
        )
        runtime.cancelPendingActions()
        return ok("手势后端已保存为 ${backend.name}（运行时立即按 saved 解析）")
    }

    private suspend fun executeSetDeveloperEnabled(arguments: JSONObject): JSONObject {
        val enabled = arguments.getBoolean("enabled")
        val base = settings.current()
        settings.save(base.copy(developer = base.developer.copy(enabled = enabled)))
        AppLog.configure(enabled, base.developer.logLevel, base.developer.logRingCapacity)
        return ok(if (enabled) "开发者选项已开启" else "开发者选项已关闭")
    }

    private suspend fun executeSetDeveloperOptions(arguments: JSONObject): JSONObject {
        val base = settings.current()
        check(base.developer.enabled) { "请先开启开发者选项" }
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        arguments.optString("logLevel").takeIf { it.isNotBlank() }?.let {
            patches.put("developer.logLevel", it)
        }
        if (arguments.has("saveDebugFrames")) {
            patches.put("developer.saveDebugFrames", arguments.getBoolean("saveDebugFrames"))
        }
        if (arguments.has("showCoordinateGrid")) {
            patches.put("developer.showCoordinateGrid", arguments.getBoolean("showCoordinateGrid"))
        }
        if (arguments.has("frameRateLimit")) {
            patches.put("developer.frameRateLimit", arguments.getInt("frameRateLimit"))
        }
        if (arguments.has("logRingCapacity")) {
            patches.put("developer.logRingCapacity", arguments.getInt("logRingCapacity"))
        }
        if (arguments.has("forceCaptureBackend")) {
            val raw = arguments.optString("forceCaptureBackend")
            patches.put(
                "developer.forceCaptureBackend",
                if (raw.isBlank()) JSONObject.NULL else raw,
            )
        }
        require(patches.length() > 0) { "未提供任何开发者字段" }
        val patched = McpSettingsPatch.applyFromJson(base, patches)
        if (persist) {
            settings.save(patched)
            AppLog.configure(
                patched.developer.enabled,
                patched.developer.logLevel,
                patched.developer.logRingCapacity,
            )
        } else {
            settings.preview(patched)
        }
        return ok("开发者选项已更新")
    }

    private suspend fun executeSetAutomationEnabled(arguments: JSONObject): JSONObject {
        val enabled = arguments.getBoolean("enabled")
        val accept = arguments.optBoolean("acceptDisclaimer", false)
        val base = settings.current()
        // Clean Base fail-closed：真实动作总闸恒关，不得通过设置/MCP 打开。
        if (enabled && !AppConfig.ACTION_ENABLED) {
            check(accept) {
                "Clean Base 阶段 ACTION_ENABLED=false：接受免责声明也须再经 Commander 授权"
            }
            settings.save(base.copy(automation = base.automation.copy(enabled = false)))
            return ok("Clean Base：自动操作总开关保持关闭（ACTION_ENABLED=false）")
        }
        var auto = base.automation.copy(enabled = enabled)
        if (enabled && auto.disclaimerAcceptedVersion < AppConfig.DISCLAIMER_VERSION) {
            check(accept) {
                "开启自动操作须 acceptDisclaimer=true（当前免责版本 ${auto.disclaimerAcceptedVersion} < ${AppConfig.DISCLAIMER_VERSION}）"
            }
            auto = auto.copy(disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION)
        }
        settings.save(base.copy(automation = auto))
        return ok(if (enabled) "自动操作已开启" else "自动操作已关闭")
    }

    private suspend fun applyConfig(transform: (AppConfig) -> AppConfig, persist: Boolean) {
        val next = transform(settings.current())
        if (persist) settings.save(next) else settings.preview(next)
    }

    private suspend fun ingestMcpConfig(rawJson: String): AppConfig {
        val baseline = settings.current()
        return settings.importJson(rawJson).hardenedForExternalIngest(baseline)
    }
}
