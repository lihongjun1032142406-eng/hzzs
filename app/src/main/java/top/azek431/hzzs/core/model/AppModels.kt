package top.azek431.hzzs.core.model

import androidx.annotation.ColorInt

/**
 * 应用级稳定配置模型。
 *
 * 职责：
 * - 定义主题、悬浮窗、截图、场景、自动操作、MCP、开发者、更新、算法等配置结构
 * - 作为 DataStore / 设置草稿 / 运行时快照的共享类型
 *
 * 约定：
 * - 本文件尽量少依赖 Android 运行时（仅 [ColorInt] 注解）
 * - 默认值必须安全：自动操作关、MCP 关、截图 AUTO 不升权
 * - 修改字段时同步：`validated()`、JSON 编解码、设置 UI、MCP schema、单测
 */

/** 应用明暗模式。AMOLED 为真黑背景的深色方案。 */
enum class AppThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * 内置调色板。
 *
 * [CUSTOM] 使用 [ThemeConfig.customSeed]；[DYNAMIC] 走系统动态取色（支持时）。
 */
enum class ThemePreset {
    DYNAMIC,
    FIRE_ORANGE,
    CORAL,
    BAMBOO,
    OCEAN,
    INDIGO,
    LAVENDER,
    BLACK_GOLD,
    HIGH_CONTRAST,
    CUSTOM,
}

/** 悬浮窗信息密度：极简 / 紧凑 / 调试 HUD。 */
enum class OverlayStyle { MINIMAL, COMPACT, DEBUG_HUD }

/** 悬浮窗视觉主题；可与应用主题解耦。 */
enum class OverlayTheme {
    FOLLOW_APP,
    AUTO_CONTRAST,
    DARK_GLASS,
    LIGHT_GLASS,
    AMOLED,
    FIRE_ORANGE,
    BAMBOO,
    NEON_GREEN,
    WARNING_ORANGE,
    CUSTOM,
}

/** 悬浮窗内容排布方向。 */
enum class OverlayOrientation { HORIZONTAL, VERTICAL }

/**
 * 截图后端。
 *
 * 安全不变量：[AUTO] 只选择低权限 MediaProjection，**永不**探测 Root / Shizuku / 无障碍。
 * [SHIZUKU] / [ROOT] / [ACCESSIBILITY] 仅当用户显式选择时启用。
 */
enum class CaptureBackend { AUTO, MEDIA_PROJECTION, ACCESSIBILITY, SHIZUKU, ROOT }

/**
 * 自动操作手势注入后端。
 *
 * 与 [CaptureBackend] 正交：改截图不改手势，反之亦然。
 *
 * 安全不变量：
 * - [AUTO] 优先无障碍；仅当无障碍未连接且 Shizuku **已授权就绪** 时用 Shizuku；
 *   **永不**静默探测或升权到 Root，AUTO 路径不弹 Shizuku 授权。
 * - [SHIZUKU] / [ROOT] 仅用户显式选择时启用。
 */
enum class GestureBackend { AUTO, ACCESSIBILITY, SHIZUKU, ROOT }

/** 应用更新通道。 */
enum class UpdateChannel { STABLE, BETA }

/**
 * 应用/算法下载来源偏好。
 *
 * [AUTO]：默认优先 Gitee，不可达时回退 GitHub。
 */
enum class UpdateSourcePreference { AUTO, PREFER_GITEE, PREFER_GITHUB }

/**
 * MCP 权限级别（从紧到松）。
 *
 * 即使 [FULL_ACCESS] 也不能绕过系统录屏 / 悬浮窗 / 无障碍 / 安装界面。
 */
enum class McpPermissionLevel {
    READ_ONLY,
    ASK_EVERY_TIME,
    TRUSTED_SESSION,
    FULL_ACCESS,
}

/**
 * 单个 MCP **工具** 的策略覆盖（相对全局 [McpPermissionLevel]）。
 *
 * 仅存非 [DEFAULT] 项；未知工具名在 [top.azek431.hzzs.core.preferences.validated] 时丢弃。
 * 外部摄入只能更严（见 [top.azek431.hzzs.core.preferences.hardenedForExternalIngest]）。
 */
enum class McpToolPolicy {
    /** 跟随全局权限级 + 工具固有 [top.azek431.hzzs.mcp.McpToolRisk]。 */
    DEFAULT,

    /**
     * 非只读调用一律手机确认（即使全局为 TRUSTED_SESSION / FULL_ACCESS）。
     * 全局 READ_ONLY 仍整表拒绝写。
     */
    ALWAYS_ASK,

    /**
     * 在 TRUSTED_SESSION / FULL_ACCESS 下普通写可不经审批；
     * HIGH_RISK 仍须 FULL_ACCESS（或全局每次确认时走审批）。
     */
    ALLOW_WHEN_TRUSTED,

    /** 从 tools/list 隐藏，tools/call 拒绝。 */
    DISABLED,
}

/**
 * 应用主题配置。
 *
 * 可在设置中临时预览；保存后写入 DataStore。
 */
data class ThemeConfig(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.FIRE_ORANGE,
    @param:ColorInt val customSeed: Int = 0xFFFF6B2C.toInt(),
    val dynamicColorEnabled: Boolean = true,
    val fontScale: Float = 1f,
    val cornerScale: Float = 1f,
    val spacingScale: Float = 1f,
    val animationScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

/**
 * 悬浮窗配置。
 *
 * 可预览。真正创建/更新窗口由 `OverlayController` 在主线程完成。
 */
data class OverlayConfig(
    /** Clean Base：悬浮窗默认 OFF，需用户在设置页显式开启。 */
    val enabled: Boolean = AppConfig.OVERLAY_DEFAULT_ENABLED,
    /** 产品默认调试 HUD：首装与缺字段回退；用户已保存样式不被迁移改写。 */
    val style: OverlayStyle = OverlayStyle.DEBUG_HUD,
    val theme: OverlayTheme = OverlayTheme.FOLLOW_APP,
    @param:ColorInt val customColor: Int = 0xFF20E89B.toInt(),
    val backgroundAlpha: Float = 0.70f,
    val scale: Float = 1f,
    val strokeWidthDp: Float = 2f,
    val textScale: Float = 1f,
    val orientation: OverlayOrientation = OverlayOrientation.HORIZONTAL,
    val showBoxes: Boolean = true,
    /**
     * 检测框持久绘制：丢检/闪检时短时保留上一帧框（仅 HUD，不参与规划）。
     * 默认开启，减少「框闪一下就空」的观感。
     */
    val persistBoxes: Boolean = true,
    val showText: Boolean = true,
    val showFps: Boolean = false,
    val showConfidence: Boolean = false,
    val showDiagnostics: Boolean = false,
    val clickThrough: Boolean = true,
    val snapToEdge: Boolean = true,
    val lockPosition: Boolean = false,
)

/**
 * 可见游戏区域，全屏归一化坐标。
 *
 * 视觉引擎在视口内裁剪分析；默认全屏。
 */
data class ViewportConfig(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 自动操作配置。
 *
 * 默认关闭。导入/迁移不得静默开启。
 * 生效前还须：免责声明版本、视觉运行中、所选 [gestureBackend] 可用等。
 *
 * 包名：默认**不**限制前台包。仅当 [restrictPackages] 为 true 时，
 * 才要求前台包 ∈ [allowedPackages]（用户可在设置中显式开启）。
 */
data class AutomationConfig(
    val enabled: Boolean = false,
    val disclaimerAcceptedVersion: Int = 0,
    /**
     * 手势注入后端；默认 [GestureBackend.AUTO]（无障碍优先，条件 Shizuku，永不 Root）。
     * 与 [AppConfig.captureBackend] 独立。
     */
    val gestureBackend: GestureBackend = GestureBackend.AUTO,
    /**
     * 是否启用前台包名门控。
     * 默认 false：任意前台包均可（仍须所选手势后端可用 + 其它门控）。
     * 开启后仅 [allowedPackages] 内的包可派发手势；须用户在设置中明确打开。
     */
    val restrictPackages: Boolean = false,
    /**
     * 允许的前台包名集合。
     * 仅在 [restrictPackages]=true 时生效；空集在 validated 时回退 [SUGGESTED_PACKAGES]。
     */
    val allowedPackages: Set<String> = SUGGESTED_PACKAGES,
    val maxActionsPerSecond: Int = 4,
    val retryLimit: Int = 1,
    /**
     * 自动复活（与 [enabled] 障碍自动操作**独立**）。
     *
     * 默认开启。经无障碍节点树按文案匹配「原地复活」「重新冒险」，
     * 取可点击祖先 `boundsInScreen` 中心点击（**不用**多点找色）。
     * 需无障碍服务连接；导入允许保持开启（风险低于障碍连点）。
     */
    val autoReviveEnabled: Boolean = true,
) {
    companion object {
        /**
         * 建议的前台包（快手系小游戏容器）。
         * 仅作默认列表与「填入建议」；**不再**与用户列表强制求交。
         */
        val SUGGESTED_PACKAGES: Set<String> = setOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        )

        /** @deprecated 使用 [SUGGESTED_PACKAGES]；保留别名避免旧测试硬编码断裂。 */
        @Deprecated("Renamed to SUGGESTED_PACKAGES", ReplaceWith("SUGGESTED_PACKAGES"))
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = SUGGESTED_PACKAGES
    }
}

/**
 * MCP 本地服务配置。
 *
 * 默认关闭；启用后默认仅 loopback。
 * [bindLocalhostOnly]=false 时服务绑定 `0.0.0.0`（局域网可达）；须用户在设置页显式确认风险。
 * [requireAuth] 默认 **false**（同机 RikkaHub 免填 Header；局域网也可免鉴权但风险更高）；
 * 开启后使用持久化 [authToken]，**不会**在每次服务启动时轮换，仅用户主动「轮换 Token」时更新。
 * [toolPolicies]：按工具名覆盖审批/禁用；键为 MCP 工具准确名（如 `start_analysis`）。
 * [accessLogEnabled]：是否写入进程内 MCP 访问日志 ring（默认 true；永不记 Token/参数体）。
 * 权限型字段；设置预览阶段不启动服务。
 */
data class McpConfig(
    val enabled: Boolean = false,
    val permissionLevel: McpPermissionLevel = McpPermissionLevel.ASK_EVERY_TIME,
    val port: Int = 8765,
    /**
     * true：只绑 IPv4 `127.0.0.1`（默认）。
     * false：绑 `0.0.0.0`，同网段设备可连；外部导入默认不得静默打开。
     */
    val bindLocalhostOnly: Boolean = true,
    val allowDebugFrames: Boolean = false,
    /**
     * 是否要求 `Authorization: Bearer`。
     * 默认 false：客户端可不填请求头（loopback 或局域网均可，由用户自担风险）。
     */
    val requireAuth: Boolean = false,
    /**
     * 持久化配对令牌（hex）。仅 [requireAuth]=true 时生效；
     * 空串表示尚未生成，服务启动或用户开启鉴权时会补齐并写回配置。
     * 不得写入日志；诊断导出须脱敏。
     */
    val authToken: String = "",
    /**
     * 工具策略覆盖：仅保留非 [McpToolPolicy.DEFAULT] 的条目。
     * 键必须是已知 MCP 工具名；未知键在校验时丢弃。
     */
    val toolPolicies: Map<String, McpToolPolicy> = emptyMap(),
    /**
     * 是否记录 MCP 访问日志（进程内 ring，见 [top.azek431.hzzs.mcp.McpAccessLog]）。
     * 默认 true；关闭后不再追加，已有条目保留直至清空。
     */
    val accessLogEnabled: Boolean = true,
) {
    fun policyFor(toolName: String): McpToolPolicy =
        toolPolicies[toolName] ?: McpToolPolicy.DEFAULT
}

/**
 * 应用日志最低级别（开发者可配置）。
 *
 * 关闭开发者选项时，ring buffer 仍保留 INFO 及以上；DEBUG/VERBOSE 仅在开启后生效。
 */
enum class AppLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * 开发者选项。
 *
 * 默认关闭；关于页连续点击版本号 7 次开启后，设置首页出现「开发者选项」分类。
 * 本页开关可关闭；预览阶段不强制切换截图后端等副作用。
 * [frameRateLimit] 字段保留并校验，但完成驱动取帧下运行时暂不消费。
 */
data class DeveloperConfig(
    val enabled: Boolean = false,
    val forceCaptureBackend: CaptureBackend? = null,
    val saveDebugFrames: Boolean = false,
    val showCoordinateGrid: Boolean = false,
    val frameRateLimit: Int = 60,
    /** 写入 ring buffer / Logcat 的最低级别；关闭开发者时 DEBUG 以下仍被压制。 */
    val logLevel: AppLogLevel = AppLogLevel.INFO,
    /**
     * AppLog ring 容量 [500, 3000]，默认 800；增大占用更多内存，重启丢失。
     * 在开发者选项「调试」分组调节。
     */
    val logRingCapacity: Int = 800,
)

/** 首次引导与免责声明接受状态。 */
data class OnboardingConfig(
    val completed: Boolean = false,
    val acceptedDisclaimerVersion: Int = 0,
)

/**
 * 应用更新策略。
 *
 * 检查/下载是即时任务；[ignoredVersionCode] 用于用户忽略某版本。
 */
data class UpdateConfig(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val autoCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val ignoredVersionCode: Long? = null,
    val sourcePreference: UpdateSourcePreference = UpdateSourcePreference.AUTO,
)

/**
 * 完整应用配置快照。
 *
 * DataStore schema 版本见 [CURRENT_SCHEMA]。
 * 默认赛季只定义在 [DEFAULT_SELECTED_SCENE]；自动操作与 MCP 默认关闭。
 * 文档与代理说明应引用该常量，不要写死赛季中文名。
 */
data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val theme: ThemeConfig = ThemeConfig(),
    val overlay: OverlayConfig = OverlayConfig(),
    val captureBackend: CaptureBackend = CaptureBackend.AUTO,
    val viewport: ViewportConfig = ViewportConfig(),
    val automation: AutomationConfig = AutomationConfig(),
    val mcp: McpConfig = McpConfig(),
    val developer: DeveloperConfig = DeveloperConfig(),
    val onboarding: OnboardingConfig = OnboardingConfig(),
    val update: UpdateConfig = UpdateConfig(),
) {
    companion object {
        /** DataStore 配置 schema 版本；迁移逻辑依赖此常量。 */
        const val CURRENT_SCHEMA = 10

        /**
         * 自动操作免责声明版本。
         * 用户接受版本低于此值时不得 arm。
         */
        const val DISCLAIMER_VERSION = 1

        /**
         * JinChanAI Clean Base 标记。
         *
         * true 表示本分支已清退 HZZS 原游戏视觉算法（算法包 / 内置识别 / Tracker /
         * 算法市场下载 / 原生视觉引擎均不存在），且未加入任何 JinChanAI 识别算法。
         */
        const val JINCHAN_CLEAN_BASE = true

        /**
         * 真实动作总闸（fail-closed）。
         *
         * Clean Base 阶段恒为 false：任何真实手势 / 点击 / 按键派发都必须在此被拒绝。
         * 不得由设置、MCP 或导入配置改写。
         */
        const val ACTION_ENABLED = false

        /** 悬浮窗默认开关；Clean Base 默认 OFF。 */
        const val OVERLAY_DEFAULT_ENABLED = false
    }
}

/**
 * 悬浮窗未能显示的原因（与分析 [RuntimeStatus.lastError] 分离）。
 *
 * [null] 表示未尝试、已隐藏或当前可见；仅在期望显示但失败时写入。
 */
enum class OverlayBlockReason {
    /** 应用内悬浮窗总开关关闭。 */
    DISABLED,
    /** 缺少系统「显示在其他应用上层」权限。 */
    PERMISSION,
    /** WindowManager 添加/更新失败。 */
    ADD_VIEW_FAILED,
}

/**
 * 运行时对外状态（UI / MCP 只读）。
 *
 * 由 [top.azek431.hzzs.data.vision.VisionRuntimeController] 作为唯一所有者更新。
 */
data class RuntimeStatus(
    val running: Boolean = false,
    val captureReady: Boolean = false,
    val overlayVisible: Boolean = false,
    /** 期望显示悬浮窗但失败时的原因；可见或未尝试时为 null。 */
    val overlayBlockReason: OverlayBlockReason? = null,
    val activeBackend: CaptureBackend = CaptureBackend.AUTO,
    /** 解析后的有效手势注入后端（AUTO 展开后）；未运行时默认 AUTO。 */
    val activeGestureBackend: GestureBackend = GestureBackend.AUTO,
    val fps: Float = 0f,
    val lastError: String? = null,
)
