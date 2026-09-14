package top.azek431.hzzs.mcp

import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.GestureBackend

/**
 * MCP 局部设置补丁（白名单路径）。
 *
 * 不走整包 JSON 导入；敏感字段（自动操作开启、MCP 权限/鉴权）由专用工具门控。
 */
object McpSettingsPatch {
    fun apply(base: AppConfig, patches: Map<String, Any?>): AppConfig {
        var cfg = base
        patches.forEach { (path, raw) ->
            cfg = applyOne(cfg, path.trim(), raw)
        }
        return cfg
    }

    fun applyFromJson(base: AppConfig, patchesJson: JSONObject): AppConfig {
        val map = linkedMapOf<String, Any?>()
        patchesJson.keys().forEach { key ->
            map[key] = if (patchesJson.isNull(key)) null else patchesJson.get(key)
        }
        return apply(base, map)
    }

    /** 批量操作类型：set=覆盖，add=向集合/列表追加，remove=从集合/列表移除，toggle=布尔取反。 */
    enum class OpType { SET, ADD, REMOVE, TOGGLE }

    /** 单条批量操作。`value` 在 [OpType.TOGGLE] 时省略。 */
    data class Op(val path: String, val value: Any?, val operation: OpType)

    /**
     * 应用批量操作。
     *
     * - SET：同 [apply]（点分路径覆盖）。
     * - ADD / REMOVE：仅支持已知集合/列表路径（`automation.allowedPackages`）；其它路径拒绝。
     * - TOGGLE：仅支持布尔路径；省略 [Op.value]。
     */
    fun applyOperations(base: AppConfig, operations: List<Op>): AppConfig {
        var cfg = base
        operations.forEach { op ->
            cfg = when (op.operation) {
                OpType.SET -> applyOne(cfg, op.path, op.value)
                OpType.ADD -> applyAdd(cfg, op.path, op.value)
                OpType.REMOVE -> applyRemove(cfg, op.path, op.value)
                OpType.TOGGLE -> applyToggle(cfg, op.path)
            }
        }
        return cfg
    }

    private fun applyAdd(cfg: AppConfig, path: String, raw: Any?): AppConfig {
        require(raw != null) { "add 操作需要 value：$path" }
        return when (path) {
            "automation.allowedPackages" -> {
                val adding = rawToStrings(raw, path)
                cfg.copy(automation = cfg.automation.copy(allowedPackages = cfg.automation.allowedPackages + adding))
            }
            else -> throw IllegalArgumentException("add 仅支持 automation.allowedPackages：$path")
        }
    }

    private fun applyRemove(cfg: AppConfig, path: String, raw: Any?): AppConfig {
        require(raw != null) { "remove 操作需要 value：$path" }
        return when (path) {
            "automation.allowedPackages" -> {
                val removing = rawToStrings(raw, path)
                cfg.copy(automation = cfg.automation.copy(allowedPackages = cfg.automation.allowedPackages - removing))
            }
            else -> throw IllegalArgumentException("remove 仅支持 automation.allowedPackages：$path")
        }
    }

    /** 解析包名列表（字符串集合）。 */
    private fun rawToStrings(raw: Any?, path: String): Set<String> = when (raw) {
        is org.json.JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it)?.trim()?.takeIf { it.isNotBlank() } }.toSet()
        is String -> raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        else -> error("$path 须为字符串数组或逗号分隔字符串")
    }

    private fun applyToggle(cfg: AppConfig, path: String): AppConfig = when (path) {
        "overlay.enabled" -> cfg.copy(overlay = cfg.overlay.copy(enabled = !cfg.overlay.enabled))
        "theme.dynamicColorEnabled" -> cfg.copy(theme = cfg.theme.copy(dynamicColorEnabled = !cfg.theme.dynamicColorEnabled))
        "theme.reduceMotion" -> cfg.copy(theme = cfg.theme.copy(reduceMotion = !cfg.theme.reduceMotion))
        "theme.highContrast" -> cfg.copy(theme = cfg.theme.copy(highContrast = !cfg.theme.highContrast))
        "automation.restrictPackages" -> cfg.copy(automation = cfg.automation.copy(restrictPackages = !cfg.automation.restrictPackages))
        "automation.autoReviveEnabled" -> cfg.copy(automation = cfg.automation.copy(autoReviveEnabled = !cfg.automation.autoReviveEnabled))
        "developer.saveDebugFrames" -> cfg.copy(developer = cfg.developer.copy(saveDebugFrames = !cfg.developer.saveDebugFrames))
        "developer.showCoordinateGrid" -> cfg.copy(developer = cfg.developer.copy(showCoordinateGrid = !cfg.developer.showCoordinateGrid))
        "mcp.accessLogEnabled" -> cfg.copy(mcp = cfg.mcp.copy(accessLogEnabled = !cfg.mcp.accessLogEnabled))
        "mcp.allowDebugFrames" -> cfg.copy(mcp = cfg.mcp.copy(allowDebugFrames = !cfg.mcp.allowDebugFrames))
        else -> throw IllegalArgumentException("toggle 仅支持已知布尔路径：$path")
    }

    private fun applyOne(cfg: AppConfig, path: String, raw: Any?): AppConfig {
        require(path.isNotBlank()) { "补丁路径不能为空" }
        return when (path) {
            "captureBackend" -> cfg.copy(captureBackend = enumValue(raw, path))
            "theme.mode" -> cfg.copy(theme = cfg.theme.copy(mode = enumValue(raw, path)))
            "theme.preset" -> cfg.copy(theme = cfg.theme.copy(preset = enumValue(raw, path)))
            "theme.dynamicColorEnabled" -> cfg.copy(
                theme = cfg.theme.copy(dynamicColorEnabled = bool(raw, path)),
            )
            "theme.fontScale" -> cfg.copy(theme = cfg.theme.copy(fontScale = float(raw, path)))
            "theme.animationScale" -> cfg.copy(
                theme = cfg.theme.copy(animationScale = float(raw, path)),
            )
            "theme.reduceMotion" -> cfg.copy(theme = cfg.theme.copy(reduceMotion = bool(raw, path)))
            "theme.highContrast" -> cfg.copy(theme = cfg.theme.copy(highContrast = bool(raw, path)))
            "theme.customSeed" -> cfg.copy(theme = cfg.theme.copy(customSeed = colorInt(raw, path)))
            "overlay.enabled" -> cfg.copy(overlay = cfg.overlay.copy(enabled = bool(raw, path)))
            "overlay.style" -> cfg.copy(overlay = cfg.overlay.copy(style = enumValue(raw, path)))
            "overlay.theme" -> cfg.copy(overlay = cfg.overlay.copy(theme = enumValue(raw, path)))
            "overlay.backgroundAlpha" -> cfg.copy(
                overlay = cfg.overlay.copy(backgroundAlpha = float(raw, path)),
            )
            "overlay.scale" -> cfg.copy(overlay = cfg.overlay.copy(scale = float(raw, path)))
            "overlay.showBoxes" -> cfg.copy(overlay = cfg.overlay.copy(showBoxes = bool(raw, path)))
            "overlay.persistBoxes" -> cfg.copy(
                overlay = cfg.overlay.copy(persistBoxes = bool(raw, path)),
            )
            "overlay.showText" -> cfg.copy(overlay = cfg.overlay.copy(showText = bool(raw, path)))
            "overlay.showFps" -> cfg.copy(overlay = cfg.overlay.copy(showFps = bool(raw, path)))
            "overlay.showConfidence" -> cfg.copy(
                overlay = cfg.overlay.copy(showConfidence = bool(raw, path)),
            )
            "overlay.showDiagnostics" -> cfg.copy(
                overlay = cfg.overlay.copy(showDiagnostics = bool(raw, path)),
            )
            "overlay.clickThrough" -> cfg.copy(
                overlay = cfg.overlay.copy(clickThrough = bool(raw, path)),
            )
            "overlay.orientation" -> cfg.copy(
                overlay = cfg.overlay.copy(orientation = enumValue(raw, path)),
            )
            "overlay.customColor" -> cfg.copy(
                overlay = cfg.overlay.copy(customColor = colorInt(raw, path)),
            )
            "automation.maxActionsPerSecond" -> cfg.copy(
                automation = cfg.automation.copy(maxActionsPerSecond = int(raw, path)),
            )
            "automation.retryLimit" -> cfg.copy(
                automation = cfg.automation.copy(retryLimit = int(raw, path)),
            )
            "automation.restrictPackages" -> cfg.copy(
                automation = cfg.automation.copy(restrictPackages = bool(raw, path)),
            )
            "automation.allowedPackages" -> {
                val list = rawToStrings(raw, path)
                cfg.copy(automation = cfg.automation.copy(allowedPackages = list.toSet()))
            }
            "automation.autoReviveEnabled" -> cfg.copy(
                automation = cfg.automation.copy(autoReviveEnabled = bool(raw, path)),
            )
            "automation.gestureBackend" -> cfg.copy(
                automation = cfg.automation.copy(gestureBackend = enumValue(raw, path)),
            )
            "developer.logLevel" -> cfg.copy(
                developer = cfg.developer.copy(logLevel = enumValue(raw, path)),
            )
            "developer.saveDebugFrames" -> cfg.copy(
                developer = cfg.developer.copy(saveDebugFrames = bool(raw, path)),
            )
            "developer.showCoordinateGrid" -> cfg.copy(
                developer = cfg.developer.copy(showCoordinateGrid = bool(raw, path)),
            )
            "developer.frameRateLimit" -> cfg.copy(
                developer = cfg.developer.copy(frameRateLimit = int(raw, path)),
            )
            "developer.forceCaptureBackend" -> {
                val backend = if (raw == null || raw == JSONObject.NULL) {
                    null
                } else {
                    enumValue<CaptureBackend>(raw, path)
                }
                cfg.copy(developer = cfg.developer.copy(forceCaptureBackend = backend))
            }
            "developer.logRingCapacity" -> cfg.copy(
                developer = cfg.developer.copy(logRingCapacity = int(raw, path)),
            )
            "mcp.allowDebugFrames" -> cfg.copy(
                mcp = cfg.mcp.copy(allowDebugFrames = bool(raw, path)),
            )
            "mcp.port" -> cfg.copy(mcp = cfg.mcp.copy(port = int(raw, path)))
            "viewport.left" -> cfg.copy(viewport = cfg.viewport.copy(left = float(raw, path)))
            "viewport.top" -> cfg.copy(viewport = cfg.viewport.copy(top = float(raw, path)))
            "viewport.right" -> cfg.copy(viewport = cfg.viewport.copy(right = float(raw, path)))
            "viewport.bottom" -> cfg.copy(viewport = cfg.viewport.copy(bottom = float(raw, path)))
            else -> throw IllegalArgumentException("不支持的补丁路径：$path")
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: Any?, path: String): T {
        if (raw is T) return raw
        val name = (raw as? String)?.trim() ?: error("$path 须为枚举名字符串")
        return runCatching { enumValueOf<T>(name) }.getOrElse { error("$path 非法枚举值：$name") }
    }

    private fun bool(raw: Any?, path: String): Boolean = when (raw) {
        is Boolean -> raw
        is String -> raw.equals("true", true) || raw == "1"
        is Number -> raw.toInt() != 0
        else -> error("$path 须为布尔")
    }

    private fun float(raw: Any?, path: String): Float = when (raw) {
        is Number -> raw.toFloat()
        is String -> raw.toFloatOrNull() ?: error("$path 不是数字")
        else -> error("$path 须为数字")
    }

    private fun int(raw: Any?, path: String): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: error("$path 不是整数")
        else -> error("$path 须为整数")
    }

    private fun colorInt(raw: Any?, path: String): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> {
            val s = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
            s.toLongOrNull(16)?.toInt() ?: error("$path 非法颜色：$raw")
        }
        else -> error("$path 须为颜色 int 或 hex")
    }
}
