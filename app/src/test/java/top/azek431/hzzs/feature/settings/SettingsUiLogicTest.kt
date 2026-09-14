package top.azek431.hzzs.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.ConfigJson
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.matchesQuery
import top.azek431.hzzs.feature.settings.model.summary

class SettingsUiLogicTest {
    @Test
    fun defaultConfigIsCleanBaseSafe() {
        val defaults = AppConfig()
        assertTrue(AppConfig.JINCHAN_CLEAN_BASE)
        assertFalse(AppConfig.ACTION_ENABLED)
        assertFalse(AppConfig.OVERLAY_DEFAULT_ENABLED)
        assertFalse(defaults.overlay.enabled)
        assertFalse(defaults.automation.enabled)
        assertFalse(defaults.mcp.enabled)
        assertEquals(UpdateSourcePreference.AUTO, defaults.update.sourcePreference)
    }

    @Test
    fun configJsonRoundTripKeepsThemeOverlayAndSourcePreference() {
        val original = AppConfig(
            theme = AppConfig().theme.copy(mode = AppThemeMode.AMOLED),
            overlay = AppConfig().overlay.copy(enabled = true),
            update = AppConfig().update.copy(
                sourcePreference = UpdateSourcePreference.PREFER_GITHUB,
            ),
        )
        val decoded = ConfigJson.decode(ConfigJson.encode(original))
        assertEquals(AppThemeMode.AMOLED, decoded.theme.mode)
        assertTrue(decoded.overlay.enabled)
        assertEquals(UpdateSourcePreference.PREFER_GITHUB, decoded.update.sourcePreference)
        assertEquals(AppConfig.CURRENT_SCHEMA, decoded.schemaVersion)
    }

    @Test
    fun legacySchemaWithRemovedFieldsStillDecodes() {
        val legacy = """
            {
              "schemaVersion": 5,
              "selectedScene": "BAMBOO_BOOKSTORE",
              "scenes": [{"sceneId": "SWEET_FACTORY", "enabled": true}],
              "algorithm": {"selectionMode": "MANUAL", "channel": "BETA"},
              "update": { "channel": "STABLE", "autoCheck": true, "wifiOnly": true }
            }
        """.trimIndent()
        val decoded = ConfigJson.decode(legacy)
        assertEquals(UpdateSourcePreference.AUTO, decoded.update.sourcePreference)
        assertFalse(decoded.overlay.enabled)
        assertFalse(decoded.automation.enabled)
    }

    @Test
    fun categorySummariesAreShortAndStable() {
        val config = AppConfig(
            theme = AppConfig().theme.copy(mode = AppThemeMode.AMOLED),
        )
        val appearance = SettingsCategory.APPEARANCE.summary(config)
        val network = SettingsCategory.NETWORK.summary(config)
        assertTrue(appearance.contains("纯黑"))
        assertTrue(network.contains("自动选择") || network.contains("应用"))
        assertTrue(SettingsCategory.AUTOMATION.summary(config).contains("关闭"))
        assertTrue(SettingsCategory.OVERLAY.summary(config).contains("关闭"))
        assertEquals("未开启", SettingsCategory.DEVELOPER.summary(config))
        assertEquals(
            "已开启",
            SettingsCategory.DEVELOPER.summary(
                config.copy(developer = config.developer.copy(enabled = true)),
            ),
        )
    }

    @Test
    fun homeGroupsPutDisplayFirstAndDeveloperLast() {
        val ordered = SettingsCategory.entries.map { it.name }
        assertEquals("APPEARANCE", ordered.first())
        assertEquals("DEVELOPER", ordered.last())
        assertTrue(
            SettingsCategory.entries.indexOf(SettingsCategory.CAPTURE) <
                SettingsCategory.entries.indexOf(SettingsCategory.MCP),
        )
        assertFalse(ordered.contains("ALGORITHM"))
        assertFalse(ordered.contains("DETECTION"))
    }

    @Test
    fun categorySearchMatchesHintsAndTitle() {
        assertTrue(
            SettingsCategory.MCP.matchesQuery(
                query = "token",
                title = "MCP 服务",
                description = "本地 AI",
            ),
        )
        assertTrue(
            SettingsCategory.CAPTURE.matchesQuery(
                query = "截图",
                title = "截图与权限",
                description = "后端",
            ),
        )
        assertFalse(
            SettingsCategory.APPEARANCE.matchesQuery(
                query = "mcp",
                title = "外观与显示",
                description = "主题",
            ),
        )
        assertTrue(
            SettingsCategory.APPEARANCE.matchesQuery(
                query = "  ",
                title = "外观与显示",
                description = "主题",
            ),
        )
    }

    @Test
    fun sourcePreferenceDisplayNames() {
        assertEquals("优先 Gitee", UpdateSourcePreference.PREFER_GITEE.displayName())
        assertEquals("优先 GitHub", UpdateSourcePreference.PREFER_GITHUB.displayName())
        assertEquals("自动选择", UpdateSourcePreference.AUTO.displayName())
    }
}
