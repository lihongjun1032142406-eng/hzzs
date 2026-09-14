package top.azek431.hzzs.core.model

/**
 * 枚举 → 用户可见中文文案。
 *
 * 界面与悬浮窗应使用本文件扩展函数，避免直接展示枚举名或英文标识。
 * 仅负责展示字符串，不含业务逻辑。
 *
 * Clean Base：已移除赛季 / 算法 / 玩家基准 / 障碍类别 / 规避动作等
 * HZZS 原游戏视觉算法相关文案映射。
 */

/** 截图后端显示名。 */
fun CaptureBackend.displayName(): String = when (this) {
    CaptureBackend.AUTO -> "自动推荐"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍截图"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}

/** 手势注入后端显示名。 */
fun GestureBackend.displayName(): String = when (this) {
    GestureBackend.AUTO -> "自动推荐"
    GestureBackend.ACCESSIBILITY -> "无障碍手势"
    GestureBackend.SHIZUKU -> "Shizuku input"
    GestureBackend.ROOT -> "Root input"
}

/** 开发者强制截图后端的短标签（FilterChip）。 */
fun CaptureBackend.developerLabel(): String = when (this) {
    CaptureBackend.AUTO -> "自动"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}

/** 应用日志级别显示名。 */
fun AppLogLevel.displayName(): String = when (this) {
    AppLogLevel.VERBOSE -> "详细 (VERBOSE)"
    AppLogLevel.DEBUG -> "调试 (DEBUG)"
    AppLogLevel.INFO -> "信息 (INFO)"
    AppLogLevel.WARN -> "警告 (WARN)"
    AppLogLevel.ERROR -> "错误 (ERROR)"
}

/** MCP 权限级别显示名。 */
fun McpPermissionLevel.displayName(): String = when (this) {
    McpPermissionLevel.READ_ONLY -> "只读"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次确认"
    McpPermissionLevel.TRUSTED_SESSION -> "信任本次会话"
    McpPermissionLevel.FULL_ACCESS -> "完整访问"
}

/** MCP 单工具策略显示名。 */
fun McpToolPolicy.displayName(): String = when (this) {
    McpToolPolicy.DEFAULT -> "跟随全局"
    McpToolPolicy.ALWAYS_ASK -> "始终确认"
    McpToolPolicy.ALLOW_WHEN_TRUSTED -> "信任时放行"
    McpToolPolicy.DISABLED -> "禁用"
}

/** 更新源偏好显示名。 */
fun UpdateSourcePreference.displayName(): String = when (this) {
    UpdateSourcePreference.AUTO -> "自动选择"
    UpdateSourcePreference.PREFER_GITEE -> "优先 Gitee"
    UpdateSourcePreference.PREFER_GITHUB -> "优先 GitHub"
}

/** 应用更新通道显示名。 */
fun UpdateChannel.displayName(): String = when (this) {
    UpdateChannel.STABLE -> "稳定"
    UpdateChannel.BETA -> "测试"
}

/** 应用主题模式显示名。 */
fun AppThemeMode.displayName(): String = when (this) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
    AppThemeMode.AMOLED -> "纯黑"
}

/** 内置调色板显示名。 */
fun ThemePreset.displayName(): String = when (this) {
    ThemePreset.DYNAMIC -> "动态取色"
    ThemePreset.FIRE_ORANGE -> "焰火橙"
    ThemePreset.CORAL -> "珊瑚红"
    ThemePreset.BAMBOO -> "竹影青"
    ThemePreset.OCEAN -> "深海蓝"
    ThemePreset.INDIGO -> "靛青"
    ThemePreset.LAVENDER -> "紫晶夜"
    ThemePreset.BLACK_GOLD -> "黑金"
    ThemePreset.HIGH_CONTRAST -> "高对比"
    ThemePreset.CUSTOM -> "自定义"
}

/** 悬浮窗信息密度显示名。 */
fun OverlayStyle.displayName(): String = when (this) {
    OverlayStyle.MINIMAL -> "极简"
    OverlayStyle.COMPACT -> "紧凑"
    OverlayStyle.DEBUG_HUD -> "调试 HUD"
}

/** 悬浮窗主题显示名。 */
fun OverlayTheme.displayName(): String = when (this) {
    OverlayTheme.FOLLOW_APP -> "跟随应用"
    OverlayTheme.AUTO_CONTRAST -> "自动对比"
    OverlayTheme.DARK_GLASS -> "深色玻璃"
    OverlayTheme.LIGHT_GLASS -> "浅色玻璃"
    OverlayTheme.AMOLED -> "纯黑"
    OverlayTheme.FIRE_ORANGE -> "焰火橙"
    OverlayTheme.BAMBOO -> "竹影青"
    OverlayTheme.NEON_GREEN -> "霓虹绿"
    OverlayTheme.WARNING_ORANGE -> "警示橙"
    OverlayTheme.CUSTOM -> "自定义"
}
