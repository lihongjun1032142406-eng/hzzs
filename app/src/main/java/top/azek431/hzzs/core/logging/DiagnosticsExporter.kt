/**
 * 诊断摘要构建：版本 / 机型 / 配置摘要 / 运行态 / 最近日志。
 *
 * 安全：不包含 MCP Bearer、签名密钥、调试帧像素；配置仅摘要字段。
 */
package top.azek431.hzzs.core.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.platform.compat.ShizukuHealthCheck
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** MCP 运行态摘要（不含 token）。 */
data class McpDiagnosticsSnapshot(
    val running: Boolean,
    val port: Int?,
    val lastError: String?,
)

object DiagnosticsExporter {
    /**
     * 设备本地时区 + 真实偏移（如 `+08:00`），避免再把本地时间标成假 `Z`。
     * 每次格式化时取 [TimeZone.getDefault]，跟随系统时区切换。
     */
    private fun localTimeFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    /**
     * 构建可分享的纯文本诊断包。
     *
     * @param versionName 应用 versionName
     * @param versionCode 应用 versionCode
     * @param config 当前已保存（或草稿）配置
     * @param mcp MCP 状态；可为 null
     * @param runtime 视觉运行时状态；可为 null
     * @param debugFrameCount 私有目录调试帧张数
     * @param appContext 可选；用于读系统指针位置 / Shizuku 就绪（JVM 单测可 null）
     * @param logLimit 附带最近日志条数
     */
    fun buildReport(
        versionName: String,
        versionCode: Long,
        config: AppConfig,
        mcp: McpDiagnosticsSnapshot?,
        debugFrameCount: Int,
        runtime: RuntimeStatus? = null,
        appContext: Context? = null,
        logLimit: Int = 200,
    ): String {
        val timeFormat = localTimeFormat()
        return buildString {
            appendLine("HZZS diagnostics")
            appendLine("generatedAt=${timeFormat.format(Date())}")
            appendLine()
            appendLine("== App ==")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("schema=${config.schemaVersion}")
            appendLine()
            appendLine("== Device ==")
            // JVM 单测中 Build 字段可能为 null，全部用默认值兜底。
            appendLine("manufacturer=${Build.MANUFACTURER ?: "unknown"}")
            appendLine("model=${Build.MODEL ?: "unknown"}")
            appendLine("device=${Build.DEVICE ?: "unknown"}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE ?: "unknown"}")
            val abis = runCatching { Build.SUPPORTED_ABIS?.joinToString().orEmpty() }.getOrDefault("")
            appendLine("abi=${abis.ifBlank { "unknown" }}")
            appendLine()
            appendLine("== Config summary ==")
            appendLine("captureBackend=${config.captureBackend.name}")
            appendLine("overlay.enabled=${config.overlay.enabled}")
            appendLine("overlay.style=${config.overlay.style.name}")
            appendLine("automation.enabled=${config.automation.enabled}")
            appendLine(
                "automation.disclaimerAcceptedVersion=${config.automation.disclaimerAcceptedVersion}" +
                    "/${AppConfig.DISCLAIMER_VERSION}",
            )
            appendLine("automation.gestureBackend=${config.automation.gestureBackend.name}")
            appendLine("automation.restrictPackages=${config.automation.restrictPackages}")
            appendLine(
                "automation.allowedPackages=" +
                    config.automation.allowedPackages.sorted().joinToString(",").ifBlank { "-" },
            )
            appendLine("automation.maxActionsPerSecond=${config.automation.maxActionsPerSecond}")
            appendLine("automation.retryLimit=${config.automation.retryLimit}")
            appendLine("automation.autoReviveEnabled=${config.automation.autoReviveEnabled}")
            appendLine("mcp.enabled=${config.mcp.enabled}")
            appendLine("mcp.permission=${config.mcp.permissionLevel.name}")
            appendLine("mcp.requireAuth=${config.mcp.requireAuth}")
            // 只写是否有 token，不写明文。
            appendLine("mcp.authTokenConfigured=${config.mcp.authToken.isNotBlank()}")
            appendLine("mcp.allowDebugFrames=${config.mcp.allowDebugFrames}")
            appendLine("mcp.accessLogEnabled=${config.mcp.accessLogEnabled}")
            appendLine(
                "mcp.accessLogCount=" +
                    runCatching { top.azek431.hzzs.mcp.McpAccessLog.size() }.getOrDefault(0),
            )
            appendLine("mcp.toolPolicyOverrides=${config.mcp.toolPolicies.size}")
            if (config.mcp.toolPolicies.isNotEmpty()) {
                appendLine(
                    "mcp.toolPolicies=" +
                        config.mcp.toolPolicies.entries
                            .sortedBy { it.key }
                            .joinToString(",") { "${it.key}:${it.value.name}" },
                )
            }
            appendLine("developer.enabled=${config.developer.enabled}")
            appendLine(
                "developer.forceCapture=${config.developer.forceCaptureBackend?.name ?: "FOLLOW"}",
            )
            // 新增：Shizuku 健康检查诊断（使用轻量同步检查，避免阻塞）
            val shizukuHealth = runCatching { ShizukuHealthCheck.checkLight() }.getOrNull()
            appendLine("shizuku_health.binder_alive=${shizukuHealth?.binderAlive ?: false}")
            appendLine("shizuku_health.permission_granted=${shizukuHealth?.permissionGranted ?: false}")
            appendLine("shizuku_health.command_checked=${shizukuHealth?.commandChecked ?: false}")
            appendLine("shizuku_health.can_execute=${shizukuHealth?.canExecute ?: false}")
            appendLine("shizuku_health.is_healthy=${shizukuHealth?.isHealthy ?: false}")
            shizukuHealth?.reason?.let { appendLine("shizuku_health.reason=${it}") }

            val captureResolution = resolveEffectiveCaptureBackend(
                captureBackend = config.captureBackend,
                developerEnabled = config.developer.enabled,
                forceCaptureBackend = config.developer.forceCaptureBackend,
            )
            appendLine("capture.requested=${captureResolution.requested.name}")
            appendLine("capture.effective=${captureResolution.effective.name}")
            appendLine(
                "capture.fallbackReason=${captureResolution.fallbackReason?.let(AppLog::redact) ?: "-"}",
            )
            // 手势门控：无障碍连接 / 前台快照 / AUTO 解析结果（与 capture 正交）。
            // 经 FQCN + runCatching，避免 core.logging 硬依赖 service 在 JVM 单测炸。
            val a11yConnected = runCatching {
                top.azek431.hzzs.service.automation.HzzsAccessibilityService.isConnected()
            }.getOrDefault(false)
            val shizukuReady = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val gestureResolution = resolveEffectiveGestureBackend(
                gestureBackend = config.automation.gestureBackend,
                accessibilityConnected = a11yConnected,
                shizukuReady = shizukuReady,
            )
            appendLine("gesture.requested=${gestureResolution.requested.name}")
            appendLine("gesture.effective=${gestureResolution.effective.name}")
            appendLine(
                "gesture.fallbackReason=${gestureResolution.fallbackReason?.let(AppLog::redact) ?: "-"}",
            )
            appendLine("a11y.connected=$a11yConnected")
            appendLine("shizuku.ready=$shizukuReady")
            val fgLine = runCatching {
                val fg = top.azek431.hzzs.service.automation.HzzsAccessibilityService
                    .foregroundSnapshot(refreshIfStale = true)
                if (fg == null) {
                    "foreground.pkg=- cls=- ageMs=-"
                } else {
                    val age = SystemClock.elapsedRealtime() - fg.observedAtMs
                    "foreground.pkg=${fg.packageName.ifBlank { "-" }} " +
                        "cls=${fg.className.ifBlank { "-" }} " +
                        "ageMs=$age"
                }
            }.getOrDefault("foreground.pkg=- cls=- ageMs=- (probe_failed)")
            appendLine(fgLine)
            appendLine("developer.saveDebugFrames=${config.developer.saveDebugFrames}")
            appendLine("developer.showCoordinateGrid=${config.developer.showCoordinateGrid}")
            appendLine(
                "developer.frameRateLimit=${config.developer.frameRateLimit} (field retained; not consumed by completion-driven loop)",
            )
            appendLine("developer.logLevel=${config.developer.logLevel.name}")
            appendLine("developer.logRingCapacity=${config.developer.logRingCapacity}")
            // 系统指针位置不进 AppConfig；只读当前系统/Shizuku 状态便于真机对照。
            if (appContext != null) {
                appendLine(
                    "system." +
                        SystemCapabilityAccess.pointerLocationDiagnosticsLine(appContext),
                )
            } else {
                appendLine("system.pointerLocation=(no context)")
            }
            appendLine("update.channel=${config.update.channel.name}")
            appendLine("update.source=${config.update.sourcePreference.name}")
            appendLine()
            appendLine("== Runtime bits ==")
            appendLine("debugFrameCount=$debugFrameCount")
            if (runtime != null) {
                appendLine("vision.running=${runtime.running}")
                appendLine("vision.captureReady=${runtime.captureReady}")
                appendLine("vision.overlayVisible=${runtime.overlayVisible}")
                appendLine("vision.overlayBlockReason=${runtime.overlayBlockReason?.name ?: "-"}")
                appendLine("vision.activeBackend=${runtime.activeBackend.name}")
                appendLine("vision.activeGestureBackend=${runtime.activeGestureBackend.name}")
                appendLine("vision.fps=${"%.2f".format(runtime.fps)}")
                appendLine("vision.lastError=${runtime.lastError?.let(AppLog::redact) ?: "-"}")
                appendLine("cleanBase=${AppConfig.JINCHAN_CLEAN_BASE}")
                appendLine("actionEnabled=${AppConfig.ACTION_ENABLED}")
                appendLine("overlayDefaultEnabled=${AppConfig.OVERLAY_DEFAULT_ENABLED}")
            } else {
                appendLine("vision.running=unknown")
            }
            if (mcp != null) {
                appendLine("mcp.running=${mcp.running}")
                appendLine("mcp.port=${mcp.port ?: "-"}")
                appendLine("mcp.lastError=${mcp.lastError?.let(AppLog::redact) ?: "-"}")
            } else {
                appendLine("mcp.running=unknown")
            }
            appendLine()
            appendLine("== MCP access log (newest first, max 40) ==")
            val access = runCatching {
                top.azek431.hzzs.mcp.McpAccessLog.formatText(limit = 40, newestFirst = true)
            }.getOrDefault("")
            if (access.isBlank()) {
                appendLine("(none)")
            } else {
                appendLine(access)
            }
            appendLine()
            appendLine("== Recent logs (oldest→newest, max $logLimit) ==")
            val logs = AppLog.snapshot(logLimit)
            if (logs.isEmpty()) {
                appendLine("(empty)")
            } else {
                logs.forEach { entry ->
                    val ts = timeFormat.format(Date(entry.epochMs))
                    append(ts)
                    append(' ')
                    append(entry.level.name)
                    append('/')
                    append(entry.tag)
                    append(": ")
                    append(entry.message)
                    entry.throwableMessage?.let {
                        append(" | ex=")
                        append(it)
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("== Notes ==")
            appendLine("- Bearer tokens and secrets are redacted.")
            appendLine("- Debug frame pixels are not included.")
            appendLine("- Timestamps use the device local timezone with offset (not UTC Z).")
            appendLine("- Overlay DEBUG_HUD / FPS / diagnostics toggles live under Overlay settings.")
        }
    }
}