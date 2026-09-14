package top.azek431.hzzs.mcp.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.BuildConfig
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.update.UpdateRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.mcp.McpEventBus
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString
import top.azek431.hzzs.mcp.toJson
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.automation.ShellProcessSupport
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend

/**
 * 系统杂项执行器：导航 / 系统设置 / 权限 / 版本 / 更新 / 日志 / 事件 / 自动操作门闩 / 诊断 / 悬浮窗状态。
 *
 * 工具面较广但多为只读或轻量跳转；诊断聚合（inspect / export_diagnostics / get_automation_gates）涉及多个依赖，
 * 统一放在这里以避免跨执行器耦合。
 */
class SystemExecutor @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
    private val uiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
    private val updateRepository: UpdateRepository,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "navigate",
        "open_system_settings",
        "get_permissions",
        "get_version",
        "check_update",
        "export_diagnostics",
        "inspect",
        "get_settings",
        "get_logs",
        "clear_logs",
        "get_events",
        "get_automation_gates",
    )

    private val processStartedElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "navigate" -> executeNavigate(arguments)
        "open_system_settings" -> executeOpenSystemSettings(arguments)
        "get_permissions" -> permissionsJson()
        "get_version" -> versionJson()
        "check_update" -> checkUpdateJson()
        "export_diagnostics" -> executeExportDiagnostics(arguments)
        "inspect" -> inspectJson(arguments.optString("include").takeIf { it.isNotBlank() })
        "get_settings" -> JSONObject(settings.exportJsonRedacted(settings.current()))
        "get_logs" -> {
            ensureDeveloper()
            logsJson(
                minLevel = arguments.optString("minLevel").takeIf { it.isNotBlank() }
                    ?.let { enumValueOf<AppLogLevel>(it) }
                    ?: AppLogLevel.INFO,
                tag = arguments.optString("tag").takeIf { it.isNotBlank() },
                query = arguments.optString("query").takeIf { it.isNotBlank() },
                limit = arguments.optInt("limit", 100).coerceIn(1, 800),
                newestFirst = arguments.optBoolean("newestFirst", true),
            )
        }
        "clear_logs" -> {
            ensureDeveloper()
            AppLog.clear()
            ok("日志 ring 已清空")
        }
        "get_events" -> {
            val since = arguments.optLong("since", 0L).coerceAtLeast(0L)
            val limit = arguments.optInt("limit", 50).coerceIn(1, McpEventBus.CAPACITY)
            eventsJson(since, limit)
        }
        "get_automation_gates" -> automationGatesJson()
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private fun executeNavigate(arguments: JSONObject): JSONObject {
        val route = arguments.requireString("route")
        val allowedTops = setOf("home", "runtime", "settings", "about")
        val normalized = route.trim().trimStart('/').lowercase()
        val top = when {
            normalized in allowedTops -> normalized
            normalized.startsWith("settings/") ||
                normalized in setOf(
                    "appearance", "overlay", "capture",
                    "automation", "network", "mcp", "developer",
                    "settings_home", "log_viewer",
                    "logs",
                ) -> "settings"
            else -> error(
                "未知页面：$route（可用 home/runtime/settings/about 或 settings/mcp、developer、log_viewer）",
            )
        }
        uiBridge.requestNavigation(route)
        return ok("已请求打开 $route（一级=$top）")
    }

    private fun executeOpenSystemSettings(arguments: JSONObject): JSONObject {
        when (arguments.requireString("target")) {
            "overlay" -> SystemCapabilityAccess.openOverlayPermissionSettings(appContext)
            "accessibility" -> SystemCapabilityAccess.openAccessibilitySettings(appContext)
            "app_details" -> {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> error("未知 target")
        }
        return ok("已请求打开系统设置")
    }

    private fun permissionsJson(): JSONObject = JSONObject().apply {
        put("canDrawOverlays", SystemCapabilityAccess.canDrawOverlays(appContext))
        put("accessibilityConnected", SystemCapabilityAccess.isAccessibilityServiceConnected())
        put("packageName", appContext.packageName)
    }

    private suspend fun versionJson(): JSONObject {
        val pkg = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        return versionJsonImpl(settings.current(), pkg)
    }

    private fun versionJsonImpl(cfg: AppConfig, pkg: android.content.pm.PackageInfo?): JSONObject =
        JSONObject().apply {
            put("versionName", pkg?.versionName ?: BuildConfig.VERSION_NAME)
            put(
                "versionCode",
                if (Build.VERSION.SDK_INT >= 28) {
                    pkg?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
                } else {
                    @Suppress("DEPRECATION")
                    (pkg?.versionCode?.toLong() ?: BuildConfig.VERSION_CODE.toLong())
                },
            )
            put("schema", cfg.schemaVersion)
            put("buildType", if (BuildConfig.DEBUG) "debug" else "release")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("abi", runCatching { Build.SUPPORTED_ABIS.joinToString() }.getOrDefault(""))
        }

    private suspend fun checkUpdateJson(): JSONObject {
        val cfg = settings.current()
        val update = cfg.update
        if (update.wifiOnly && !isOnUnmeteredNetwork()) {
            return JSONObject().put("error", "当前设置要求仅在 Wi‑Fi 下检查更新").put("skipped", true)
        }
        return runCatching {
            val result = updateRepository.check(
                beta = update.channel == UpdateChannel.BETA,
                sourcePreference = update.sourcePreference,
            )
            val installed = installedVersionCode()
            JSONObject()
                .put("hasUpdate", result.manifest.versionCode > installed)
                .put("versionName", result.manifest.versionName)
                .put("versionCode", result.manifest.versionCode)
                .put("source", result.source.name)
                .put("releaseNotes", result.manifest.releaseNotes)
                .put("isIgnored", update.ignoredVersionCode == result.manifest.versionCode)
                .put("installedVersionCode", installed)
        }.getOrElse { error ->
            JSONObject().put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun executeExportDiagnostics(arguments: JSONObject): JSONObject {
        val logLimit = arguments.optInt("logLimit", 200).coerceIn(0, 800)
        val snap = settings.current()
        val mcpState = uiBridge.serverState.value
        val text = DiagnosticsExporter.buildReport(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            config = snap,
            mcp = McpDiagnosticsSnapshot(
                running = mcpState.running,
                port = mcpState.port.takeIf { it > 0 },
                lastError = mcpState.lastError,
            ),
            debugFrameCount = runCatching { debugFrames.list().size }.getOrDefault(0),
            runtime = runtime.status.value,
            appContext = appContext,
            logLimit = logLimit,
        )
        return JSONObject().put("text", text)
    }

    private suspend fun inspectJson(includeCsv: String?): JSONObject {
        val include = includeCsv?.split(',', ' ', ';')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        fun want(name: String) = include.isEmpty() || name in include
        val status = runtime.status.value
        val cfg = settings.current()
        return JSONObject().apply {
            if (want("status")) put("status", status.toJson())
            if (want("cleanbase")) {
                put(
                    "cleanBase",
                    JSONObject().apply {
                        put("enabled", AppConfig.JINCHAN_CLEAN_BASE)
                        put("actionEnabled", AppConfig.ACTION_ENABLED)
                        put("overlayDefaultEnabled", AppConfig.OVERLAY_DEFAULT_ENABLED)
                        put("builtinGameVision", false)
                        put("builtinTracker", false)
                        put("algorithmPackRuntime", false)
                    },
                )
            }
            if (want("gates")) put("automationGates", automationGatesJson())
            if (want("permissions")) put("permissions", permissionsJson())
            if (want("mcp")) {
                val mcp = cfg.mcp
                val server = uiBridge.serverState.value
                put(
                    "mcp",
                    JSONObject().apply {
                        put("enabled", mcp.enabled)
                        put("running", server.running)
                        put("port", if (server.running && server.port > 0) server.port else mcp.port)
                        put("configuredPort", mcp.port)
                        put("permissionLevel", mcp.permissionLevel.name)
                        put("requireAuth", mcp.requireAuth)
                        put("tokenConfigured", mcp.authToken.isNotBlank())
                        put("toolPolicyOverrides", mcp.toolPolicies.size)
                        put("allowDebugFrames", mcp.allowDebugFrames)
                        put("accessLogEnabled", mcp.accessLogEnabled)
                    },
                )
            }
            if (want("version")) {
                val pkg = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                }.getOrNull()
                put("version", versionJsonImpl(cfg, pkg))
            }
        }
    }

    private suspend fun automationGatesJson(): JSONObject {
        val saved = settings.snapshot()
        val status = runtime.status.value
        val a11y = HzzsAccessibilityService.isConnected()
        val auto = saved.automation
        val disclaimerOk = auto.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION
        val gestureRequested = auto.gestureBackend
        val gestureEffective = when {
            status.running -> status.activeGestureBackend
            else -> resolveEffectiveGestureBackend(
                gestureBackend = gestureRequested,
                accessibilityConnected = a11y,
                shizukuReady = runCatching {
                    ShellProcessSupport.isShizukuAuthorized()
                }.getOrDefault(false),
            ).effective
        }
        val needsA11y = gestureEffective == GestureBackend.ACCESSIBILITY ||
            gestureRequested == GestureBackend.ACCESSIBILITY ||
            (gestureRequested == GestureBackend.AUTO &&
                gestureEffective != GestureBackend.SHIZUKU &&
                gestureEffective != GestureBackend.ROOT)
        val fg = if (needsA11y || auto.restrictPackages) {
            HzzsAccessibilityService.foregroundSnapshot(refreshIfStale = true)
        } else {
            null
        }
        val packageBlocked = auto.restrictPackages &&
            (fg == null || fg.packageName !in auto.allowedPackages)
        val blockers = buildList {
            // Clean Base：真实动作总闸恒关，任何自动操作都必须在此被拦下。
            if (!AppConfig.ACTION_ENABLED) add("action_disabled clean_base")
            if (!auto.enabled) add("automation.enabled=false")
            if (!disclaimerOk) {
                add("disclaimerAcceptedVersion=${auto.disclaimerAcceptedVersion}<${AppConfig.DISCLAIMER_VERSION}")
            }
            if (!status.running) add("analysis.not_running")
            if (!a11y && needsA11y) {
                add("accessibility.not_connected")
            }
            if (packageBlocked) {
                add(
                    "package_gate restrict=true pkg=${fg?.packageName ?: "n/a"} " +
                        "allowed=${auto.allowedPackages.sorted().joinToString(",")}" +
                        if (!needsA11y) " (foreground via a11y probe; shell may differ)" else "",
                )
            }
        }
        return JSONObject().apply {
            put("source", "saved")
            put("cleanBase", AppConfig.JINCHAN_CLEAN_BASE)
            put("actionEnabled", AppConfig.ACTION_ENABLED)
            put("automationEnabled", auto.enabled)
            put("gestureBackend", gestureRequested.name)
            put("activeGestureBackend", gestureEffective.name)
            put("disclaimerAcceptedVersion", auto.disclaimerAcceptedVersion)
            put("disclaimerRequired", AppConfig.DISCLAIMER_VERSION)
            put("disclaimerOk", disclaimerOk)
            put("analysisRunning", status.running)
            put("accessibilityConnected", a11y)
            put("restrictPackages", auto.restrictPackages)
            put("allowedPackages", JSONArray(auto.allowedPackages.sorted()))
            put("foregroundPackage", fg?.packageName ?: JSONObject.NULL)
            put(
                "foregroundSource",
                if (needsA11y) "accessibility" else "accessibility_probe_for_package_gate",
            )
            put("maxActionsPerSecond", auto.maxActionsPerSecond)
            put("canDispatchLikely", false)
            put("blockers", JSONArray(blockers))
        }
    }

    private suspend fun logsJson(
        minLevel: AppLogLevel = AppLogLevel.INFO,
        tag: String? = null,
        query: String? = null,
        limit: Int = 100,
        newestFirst: Boolean = true,
    ): JSONObject {
        if (!settings.current().developer.enabled) {
            return JSONObject().put("error", "需要开发者选项").put("entries", JSONArray())
        }
        val entries = AppLog.query(
            minLevel = minLevel,
            tagEquals = tag,
            query = query,
            limit = limit,
            newestFirst = newestFirst,
        )
        return JSONObject().apply {
            put("revision", AppLog.revision())
            put("count", entries.size)
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { e ->
                        put(
                            JSONObject()
                                .put("id", e.id)
                                .put("epochMs", e.epochMs)
                                .put("level", e.level.name)
                                .put("tag", e.tag)
                                .put("message", e.message)
                                .put("throwable", e.throwableMessage ?: JSONObject.NULL),
                        )
                    }
                },
            )
            put("text", AppLog.formatText(minLevel, tag, query, limit, newestFirst))
        }
    }

    private fun eventsJson(since: Long = 0L, limit: Int = 50): JSONObject {
        val snap = McpEventBus.snapshot(since, limit)
        return JSONObject()
            .put("events", JSONArray(snap.events.map { it.toJson() }))
            .put("nextSince", snap.nextSince)
            .put("dropped", snap.dropped)
            .put("oldestSeq", snap.oldestSeq)
            .put("latestSeq", snap.latestSeq)
            .put("buffered", snap.buffered)
    }

    private suspend fun ensureDeveloper() {
        check(settings.current().developer.enabled) {
            "请先开启开发者选项（set_developer_enabled 或关于页连点版本号）"
        }
    }

    private fun installedVersionCode(): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }
}
