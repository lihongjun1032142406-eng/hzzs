/**
 * 设置分类路由与首页摘要。
 *
 * 职责：定义设置子页路由/标题/图标/分组，以及根据配置生成首页一行摘要。
 * 边界：纯 UI 模型，不读写仓库、不触发预览或权限能力。
 * 标题/说明使用 string 资源 ID；[summary] 为纯函数便于 JVM 单测。
 *
 * Clean Base：算法包 / 识别阈值 / 赛季障碍等 HZZS 原游戏视觉算法分类已清退。
 */
package top.azek431.hzzs.feature.settings.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import top.azek431.hzzs.R
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.displayName

/** 设置首页分组（紧凑 IA）。 */
enum class SettingsGroup(
    @param:StringRes val titleRes: Int,
) {
    DISPLAY(R.string.settings_group_display),
    CAPTURE_VISION(R.string.settings_group_capture_vision),
    SAFETY(R.string.settings_group_safety),
    NETWORK_EXT(R.string.settings_group_network_ext),
    ADVANCED(R.string.settings_group_advanced),
}

/** 设置首页分类枚举：路由与展示元数据（文案资源 ID）。顺序即首页默认顺序。 */
enum class SettingsCategory(
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val group: SettingsGroup,
    /** 参与首页搜索的额外关键字（中文/英文，小写匹配）。 */
    val searchHints: String = "",
) {
    APPEARANCE(
        route = "appearance",
        titleRes = R.string.settings_cat_appearance_title,
        descriptionRes = R.string.settings_cat_appearance_desc,
        icon = Icons.Rounded.ColorLens,
        group = SettingsGroup.DISPLAY,
        searchHints = "主题 暗色 字体 动效 theme",
    ),
    OVERLAY(
        route = "overlay",
        titleRes = R.string.settings_cat_overlay_title,
        descriptionRes = R.string.settings_cat_overlay_desc,
        icon = Icons.Rounded.Layers,
        group = SettingsGroup.DISPLAY,
        searchHints = "hud 悬浮 透明度",
    ),
    CAPTURE(
        route = "capture",
        titleRes = R.string.settings_cat_capture_title,
        descriptionRes = R.string.settings_cat_capture_desc,
        icon = Icons.Rounded.PhotoCamera,
        group = SettingsGroup.CAPTURE_VISION,
        searchHints = "截图 录屏 root shizuku 无障碍 media",
    ),
    AUTOMATION(
        route = "automation",
        titleRes = R.string.settings_cat_automation_title,
        descriptionRes = R.string.settings_cat_automation_desc,
        icon = Icons.Rounded.Security,
        group = SettingsGroup.SAFETY,
        searchHints = "自动 手势 风险 免责 节流 包名",
    ),
    NETWORK(
        route = "network",
        titleRes = R.string.settings_cat_network_title,
        descriptionRes = R.string.settings_cat_network_desc,
        icon = Icons.Rounded.CloudSync,
        group = SettingsGroup.NETWORK_EXT,
        searchHints = "更新 gitee github wifi",
    ),
    MCP(
        route = "mcp",
        titleRes = R.string.settings_cat_mcp_title,
        descriptionRes = R.string.settings_cat_mcp_desc,
        icon = Icons.Rounded.SmartToy,
        group = SettingsGroup.NETWORK_EXT,
        searchHints = "mcp ai token loopback",
    ),
    DEVELOPER(
        route = "developer",
        titleRes = R.string.settings_cat_developer_title,
        descriptionRes = R.string.settings_cat_developer_desc,
        icon = Icons.Rounded.BugReport,
        group = SettingsGroup.ADVANCED,
        searchHints = "调试 日志 诊断",
    ),
}

/**
 * 根据当前配置生成分类卡摘要文案。
 * 动态拼接，保持纯函数以便 JVM 单测；静态壳文案见 strings.xml。
 */
fun SettingsCategory.summary(config: AppConfig): String = when (this) {
    SettingsCategory.APPEARANCE -> {
        val mode = config.theme.mode.displayName()
        val preset = config.theme.preset.displayName()
        "$mode · $preset"
    }
    SettingsCategory.CAPTURE -> config.captureBackend.displayName()
    SettingsCategory.OVERLAY -> {
        val state = if (config.overlay.enabled) "已开启" else "已关闭"
        "$state · ${config.overlay.style.displayName()}"
    }
    SettingsCategory.AUTOMATION -> {
        if (config.automation.enabled) {
            "已开启"
        } else {
            "默认关闭"
        }
    }
    SettingsCategory.NETWORK -> {
        val source = config.update.sourcePreference.displayName()
        val channel = config.update.channel.displayName()
        "$source · 应用$channel"
    }
    SettingsCategory.MCP -> {
        if (config.mcp.enabled) {
            config.mcp.permissionLevel.displayName()
        } else {
            "MCP 关闭"
        }
    }
    SettingsCategory.DEVELOPER -> {
        if (config.developer.enabled) "已开启" else "未开启"
    }
}

/** 是否匹配首页搜索查询（query 已 trim；空串视为全匹配）。 */
fun SettingsCategory.matchesQuery(
    query: String,
    title: String,
    description: String,
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val haystack = buildString {
        append(title)
        append(' ')
        append(description)
        append(' ')
        append(searchHints)
        append(' ')
        append(route)
    }.lowercase()
    return q.lowercase().split(Regex("\\s+")).all { token -> token in haystack }
}

/** 设置模块内嵌导航路由常量。 */
object SettingsRoutes {
    const val HOME = "settings_home"
    /** 开发者运行日志查看器（从开发者页进入）。 */
    const val LOG_VIEWER = "log_viewer"
    /** MCP 访问日志全屏查看器（从 MCP 页进入）。 */
    const val MCP_ACCESS_LOG = "mcp_access_log"
}
