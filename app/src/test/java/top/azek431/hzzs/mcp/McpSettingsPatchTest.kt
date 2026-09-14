package top.azek431.hzzs.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.ThemePreset

class McpSettingsPatchTest {
    @Test
    fun appliesThemeAndOverlayPatches() {
        val base = AppConfig()
        val next = McpSettingsPatch.apply(
            base,
            mapOf(
                "theme.mode" to "DARK",
                "theme.preset" to "OCEAN",
                "overlay.style" to "COMPACT",
                "overlay.showFps" to true,
                "overlay.persistBoxes" to false,
            ),
        )
        assertEquals(AppThemeMode.DARK, next.theme.mode)
        assertEquals(ThemePreset.OCEAN, next.theme.preset)
        assertEquals(OverlayStyle.COMPACT, next.overlay.style)
        assertTrue(next.overlay.showFps)
        assertFalse(next.overlay.persistBoxes)
    }


    @Test
    fun appliesBatchOperations() {
        val base = AppConfig().copy(
            automation = AppConfig().automation.copy(
                allowedPackages = setOf("com.a"),
                restrictPackages = false,
            ),
            theme = AppConfig().theme.copy(reduceMotion = false),
        )
        val next = McpSettingsPatch.applyOperations(
            base,
            listOf(
                McpSettingsPatch.Op(
                    "automation.allowedPackages",
                    "com.b",
                    McpSettingsPatch.OpType.ADD,
                ),
                McpSettingsPatch.Op(
                    "automation.allowedPackages",
                    "com.a",
                    McpSettingsPatch.OpType.REMOVE,
                ),
                McpSettingsPatch.Op(
                    "automation.restrictPackages",
                    null,
                    McpSettingsPatch.OpType.TOGGLE,
                ),
                McpSettingsPatch.Op(
                    "theme.reduceMotion",
                    null,
                    McpSettingsPatch.OpType.TOGGLE,
                ),
            ),
        )
        assertTrue(next.automation.allowedPackages.contains("com.b"))
        assertFalse(next.automation.allowedPackages.contains("com.a"))
        assertTrue(next.automation.restrictPackages)
        assertTrue(next.theme.reduceMotion)
    }

    @Test
    fun rejectsUnknownBatchOperation() {
        try {
            McpSettingsPatch.applyOperations(
                AppConfig(),
                listOf(
                    McpSettingsPatch.Op(
                        "automation.allowedPackages",
                        "com.x",
                        McpSettingsPatch.OpType.TOGGLE,
                    ),
                ),
            )
            assertTrue("should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("toggle 仅支持已知布尔路径"))
        }
    }
    @Test
    fun rejectsSensitivePatch() {
        try {
            McpSettingsPatch.apply(AppConfig(), mapOf("automation.enabled" to true))
            assertTrue("should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不支持"))
        }
    }

    @Test
    fun applyFromJsonObject() {
        val patches = JSONObject()
            .put("theme.reduceMotion", true)
            .put("mcp.port", 9001)
        val next = McpSettingsPatch.applyFromJson(AppConfig(), patches)
        assertTrue(next.theme.reduceMotion)
        assertEquals(9001, next.mcp.port)
        assertFalse(next.mcp.requireAuth)
    }

    @Test
    fun appliesAutoRevivePatch() {
        val base = AppConfig()
        assertTrue(base.automation.autoReviveEnabled)
        val off = McpSettingsPatch.apply(
            base,
            mapOf("automation.autoReviveEnabled" to false),
        )
        assertFalse(off.automation.autoReviveEnabled)
        val on = McpSettingsPatch.apply(
            off,
            mapOf("automation.autoReviveEnabled" to true),
        )
        assertTrue(on.automation.autoReviveEnabled)
    }

    @Test
    fun catalogHasNewToolsAndStrictSchemas() {
        val names = McpToolCatalog.tools.map { it.name }.toSet()
        listOf(
            "get_runtime_snapshot",
            "patch_settings",
            "set_theme",
            "set_developer_enabled",
            "get_automation_gates",
            "get_logs",
            "export_diagnostics",
            "get_mcp_status",
            "list_mcp_tools",
            "set_mcp_enabled",
            "set_mcp_permission_level",
            "set_mcp_auth",
            "set_mcp_tool_policy",
        ).forEach { assertTrue("$it missing", names.contains(it)) }
        McpToolCatalog.tools.forEach { tool ->
            assertEquals("object", tool.inputSchema.getString("type"))
            assertFalse(
                "tool ${tool.name} must not open additionalProperties at root",
                tool.inputSchema.optBoolean("additionalProperties", true),
            )
        }
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_developer_enabled")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_automation_enabled")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_mcp_tool_policy")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_mcp_permission_level")!!.risk)
        assertTrue(McpToolCatalog.tools.any { it.name == "inspect" })
        assertTrue(
            McpToolCatalog.tool("inspect")!!.inputSchema
                .getJSONObject("properties")
                .has("include"),
        )
        listOf(
            "get_debug_frame",
            "capture_debug_frame",
            "save_profile",
            "load_profile",
            "list_profiles",
            "delete_profile",
            "get_events",
            "get_version",
            "check_update",
            "get_metrics",
        ).forEach { assertTrue("$it missing", names.contains(it)) }
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("get_debug_frame")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("capture_debug_frame")!!.risk)
        assertTrue(McpToolCatalog.resources.none { it.uri == "app://runtime/snapshot" })
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://mcp/status" })
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://events" })
        // 每个工具都有中文标题（不得回退成纯工具名）
        McpToolCatalog.tools.forEach { tool ->
            val title = McpToolLabels.titleZh(tool.name)
            assertTrue("missing zh label for ${tool.name}", title != tool.name)
            assertTrue(title.isNotBlank())
        }
        assertTrue(
            McpToolLabels.clientDescription(McpToolCatalog.tool("get_status")!!)
                .contains("工具名: get_status"),
        )
    }
}
