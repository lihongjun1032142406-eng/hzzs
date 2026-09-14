package top.azek431.hzzs.mcp.executor

import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.toJson
import javax.inject.Inject

/**
 * 运行时控制执行器：截图运行时启停 / 状态 / 指标 / 诊断。
 *
 * 纯数据面（[top.azek431.hzzs.data.vision.VisionRuntimeController] 是截图运行时唯一所有者，
 * 帧循环由其协调），不直接持有 SettingsRepository 写设置。
 *
 * Clean Base：不存在识别算法与自动操作，因此
 * - 不返回任何检测结果；
 * - [AppConfig.ACTION_ENABLED] 恒为 false，`cancel_actions` 为空操作。
 */
class RuntimeControlExecutor @Inject constructor(
    private val runtime: VisionRuntimeController,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "start_analysis",
        "stop_analysis",
        "restart_analysis",
        "cancel_actions",
        "get_status",
        "get_runtime_snapshot",
        "get_metrics",
        "run_diagnostics",
    )

    private val processStartedElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "start_analysis" -> {
            runtime.start()
            ok("已请求启动截图运行时")
        }
        "stop_analysis" -> {
            runtime.stop()
            ok("截图运行时已停止")
        }
        "restart_analysis" -> {
            runtime.stop()
            runtime.start()
            ok("已请求重启截图运行时")
        }
        "cancel_actions" -> {
            runtime.cancelPendingActions()
            ok("Clean Base 无真实动作可取消（ACTION_ENABLED=false）")
        }
        "get_status" -> runtime.status.value.toJson()
        "get_runtime_snapshot" -> runtimeSnapshot()
        "get_metrics" -> metricsJson()
        "run_diagnostics" -> JSONObject().apply {
            put("status", runtime.status.value.toJson())
            put("cleanBase", AppConfig.JINCHAN_CLEAN_BASE)
            put("actionEnabled", AppConfig.ACTION_ENABLED)
            put("builtinVision", false)
            put("builtinTracker", false)
            put("algorithmPackRuntime", false)
            put("debugFrameCount", 0)
        }
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private fun metricsJson(): JSONObject {
        val jvmRuntime = Runtime.getRuntime()
        val status = runtime.status.value
        val uptime = android.os.SystemClock.elapsedRealtime() - processStartedElapsedRealtimeMs
        return JSONObject().apply {
            put(
                "memory",
                JSONObject()
                    .put("totalBytes", jvmRuntime.totalMemory())
                    .put("freeBytes", jvmRuntime.freeMemory())
                    .put("usedBytes", jvmRuntime.totalMemory() - jvmRuntime.freeMemory())
                    .put("maxBytes", jvmRuntime.maxMemory()),
            )
            put(
                "frame",
                JSONObject()
                    .put("fps", status.fps.toDouble())
                    .put("last30Fps", JSONArray().put(status.fps.toDouble())),
            )
            put("uptimeMs", uptime.coerceAtLeast(0L))
            put("processStartedElapsedRealtimeMs", processStartedElapsedRealtimeMs)
        }
    }

    private fun runtimeSnapshot(): JSONObject {
        val status = runtime.status.value
        return JSONObject().apply {
            put("status", status.toJson())
            put("cleanBase", AppConfig.JINCHAN_CLEAN_BASE)
            put("actionEnabled", AppConfig.ACTION_ENABLED)
            put("latest", JSONObject.NULL)
            put("captureBackend", status.activeBackend.name)
            put("overlayVisible", status.overlayVisible)
            put("activeGestureBackend", status.activeGestureBackend.name)
        }
    }
}
