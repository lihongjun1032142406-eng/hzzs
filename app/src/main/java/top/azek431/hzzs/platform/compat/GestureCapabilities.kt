package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手势后端在本机是否“支持”（版本门闩，不含运行时授权）。
 * 与截图不同：手势无障碍不依赖 API 30。
 */
fun GestureBackend.isSupportedOnThisDevice(): Boolean = when (this) {
    GestureBackend.AUTO,
    GestureBackend.ACCESSIBILITY,
    GestureBackend.SHIZUKU,
    GestureBackend.ROOT,
    -> true
}

/**
 * 有效手势后端解析结果。
 *
 * [requested] 为配置意图；[effective] 为实际注入路径。
 */
data class GestureBackendResolution(
    val requested: GestureBackend,
    val effective: GestureBackend,
    val fallbackReason: String? = null,
) {
    // AUTO 正常解析到首选 ACCESSIBILITY 不属于 fallback；
    // 只有明确给出 fallbackReason 时才表示发生了降级/替代路径。
    val fellBack: Boolean get() = fallbackReason != null
}

/**
 * 解析运行时真正使用的手势注入后端。
 *
 * 规则：
 * 1. 显式 ACCESSIBILITY / SHIZUKU / ROOT：原样作为 effective（运行时再 fail-closed）；
 * 2. AUTO：无障碍已连接 → ACCESSIBILITY；否则 Shizuku 已授权就绪 → SHIZUKU；
 *    两者皆不可用仍解析为 ACCESSIBILITY（dispatch 时 fail-closed「未连接」）；
 * 3. **永不**将 AUTO 解析为 ROOT，**永不**为 AUTO 执行 su 探测。
 *
 * [accessibilityConnected] / [shizukuReady] 由调用方注入，便于 JVM 单测。
 */
fun resolveEffectiveGestureBackend(
    gestureBackend: GestureBackend,
    accessibilityConnected: Boolean,
    shizukuReady: Boolean,
    isSupported: (GestureBackend) -> Boolean = GestureBackend::isSupportedOnThisDevice,
): GestureBackendResolution {
    val requested = if (isSupported(gestureBackend)) {
        gestureBackend
    } else {
        GestureBackend.ACCESSIBILITY
    }
    return when (requested) {
        GestureBackend.ACCESSIBILITY,
        GestureBackend.SHIZUKU,
        GestureBackend.ROOT,
        -> GestureBackendResolution(requested = gestureBackend, effective = requested)

        GestureBackend.AUTO -> when {
            accessibilityConnected -> GestureBackendResolution(
                requested = GestureBackend.AUTO,
                effective = GestureBackend.ACCESSIBILITY,
            )
            shizukuReady -> GestureBackendResolution(
                requested = GestureBackend.AUTO,
                effective = GestureBackend.SHIZUKU,
                fallbackReason = "无障碍未连接，改用已授权的 Shizuku input",
            )
            else -> GestureBackendResolution(
                requested = GestureBackend.AUTO,
                effective = GestureBackend.ACCESSIBILITY,
                fallbackReason = "无障碍未连接且 Shizuku 未就绪；将尝试无障碍并在未连接时 fail-closed",
            )
        }
    }
}

/**
 * 单一手势后端的能力快照：支持度、就绪、是否推荐，以及设置页展示文案。
 */
data class GestureCapability(
    val backend: GestureBackend,
    val supported: Boolean,
    val ready: Boolean,
    val recommended: Boolean,
    val title: String,
    val summary: String,
    /** UI 风险提示：低 / 中 / 高 */
    val riskLevel: String,
)

/**
 * 手势后端能力解析中心。
 *
 * 安全不变量：
 * - AUTO 推荐且永不宣称 Root 可用；
 * - 探测 Shizuku 仅用于展示与 AUTO 解析，AUTO 路径不 requestPermission；
 * - Root 的 ready 固定 false，避免误导「已可用」。
 */
@Singleton
class GestureCapabilityResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun all(): List<GestureCapability> {
        val a11y = HzzsAccessibilityService.isConnected()
        val shizukuHealth = ShizukuHealthCheck.checkLight()
        val shizukuReady = shizukuHealth.isHealthy
        return listOf(
            GestureCapability(
                backend = GestureBackend.AUTO,
                supported = true,
                ready = a11y || shizukuReady,
                recommended = true,
                title = "自动推荐",
                summary = "优先无障碍手势；无障碍未连接且 Shizuku 已授权时改用 input。" +
                    "永不自动尝试 Root，也不会在自动路径弹出 Shizuku 授权。",
                riskLevel = "低",
            ),
            GestureCapability(
                backend = GestureBackend.ACCESSIBILITY,
                supported = true,
                ready = a11y,
                recommended = true,
                title = "无障碍手势",
                summary = if (a11y) {
                    "服务已连接。通过系统 dispatchGesture 注入，带回执。"
                } else {
                    "需在系统设置中开启本应用的无障碍服务。"
                },
                riskLevel = "中",
            ),
            GestureCapability(
                backend = GestureBackend.SHIZUKU,
                supported = true,
                ready = shizukuReady,
                recommended = false,
                title = "Shizuku input",
                summary = when {
                    !hasPackage("moe.shizuku.privileged.api") ->
                        "需安装并启动 Shizuku。将通过 shell 执行 input tap/swipe；完成语义弱于无障碍回执。"
                    !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                        "已安装 Shizuku，但服务未运行。"
                    runCatching { Shizuku.checkSelfPermission() }.getOrDefault(
                        PackageManager.PERMISSION_DENIED,
                    ) != PackageManager.PERMISSION_GRANTED ->
                        "Shizuku 已运行，请先授予本应用权限（可在截图页选 Shizuku 触发授权，或于 Shizuku 应用中管理）。"
                    else ->
                        "Shizuku 已授权就绪（命令能力将在手势启动前验证）。通过 input 注入点击/滑动；前台包由 dumpsys 探测。"
                },
                riskLevel = "中高",
            ),
            GestureCapability(
                backend = GestureBackend.ROOT,
                supported = true,
                ready = false,
                recommended = false,
                title = "Root input",
                summary = "最高权限实验后端：su 执行 input。兼容与误触风险最高；应用不会代为提权。",
                riskLevel = "高",
            ),
        )
    }

    /** 只读：安装 + binder + 已授权。不 requestPermission。 */
    fun isShizukuReady(): Boolean = runCatching {
        hasPackage("moe.shizuku.privileged.api") &&
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isAccessibilityConnected(): Boolean = HzzsAccessibilityService.isConnected()

    private fun hasPackage(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}
