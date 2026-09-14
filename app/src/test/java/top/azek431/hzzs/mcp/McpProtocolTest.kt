package top.azek431.hzzs.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 协议 / 会话 / HTTP 辅助契约测试（纯 JVM）。
 * 不启动真实 ServerSocket，覆盖握手、通知、会话与鉴权辅助。
 */
class McpProtocolTest {
    @Test
    fun loopbackOriginAllowedAndForeignRejected() {
        assertTrue(isAllowedLoopbackOrigin(null))
        assertTrue(isAllowedLoopbackOrigin(""))
        assertTrue(isAllowedLoopbackOrigin("null"))
        assertTrue(isAllowedLoopbackOrigin("http://127.0.0.1:1234"))
        assertTrue(isAllowedLoopbackOrigin("http://localhost"))
        assertTrue(isAllowedLoopbackOrigin("https://[::1]"))
        assertFalse(isAllowedLoopbackOrigin("http://evil.example"))
        assertFalse(isAllowedLoopbackOrigin("http://192.168.1.1"))
        assertFalse(isAllowedLoopbackOrigin("file://localhost"))
    }

    @Test
    fun normalizeMcpPathCollapsesSlash() {
        assertEquals("/mcp", normalizeMcpPath("/mcp"))
        assertEquals("/mcp", normalizeMcpPath("/mcp/"))
        assertEquals("/mcp", normalizeMcpPath("//mcp//"))
        assertEquals("/health", normalizeMcpPath("/health/"))
    }

    @Test
    fun bearerConstantTimeMatch() {
        val token = "aabbccddeeff00112233445566778899"
        assertTrue(constantTimeBearerMatches("Bearer $token", token))
        assertTrue(constantTimeBearerMatches("bearer $token", token)) // RFC 7235 scheme 大小写不敏感
        assertFalse(constantTimeBearerMatches("Bearer wrong", token))
        assertFalse(constantTimeBearerMatches(token, token)) // 缺少 Bearer 前缀
        assertFalse(constantTimeBearerMatches(null, token))
        assertFalse(constantTimeBearerMatches("Bearer $token", token + "x"))
        assertFalse(constantTimeBearerMatches("Bearer $token", ""))
    }

    @Test
    fun generateMcpAuthTokenIsStableHex() {
        val a = generateMcpAuthToken()
        val b = generateMcpAuthToken()
        assertEquals(48, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
    }

    @Test
    fun sessionCreateInitializeAndExpire() {
        val manager = McpSessionManager()
        val session = manager.createSession(McpProtocolVersions.LATEST, "test-client")
        assertNotNull(manager.get(session.id))
        assertFalse(session.initialized)
        assertTrue(manager.markInitialized(session.id))
        assertTrue(manager.get(session.id)!!.initialized)
        manager.remove(session.id)
        assertEquals(null, manager.get(session.id))
    }

    @Test
    fun connectionBackpressure() {
        val manager = McpSessionManager()
        repeat(McpLimits.MAX_CONCURRENT_CONNECTIONS) {
            assertTrue(manager.tryAcquireConnection())
        }
        assertFalse(manager.tryAcquireConnection())
        manager.releaseConnection()
        assertTrue(manager.tryAcquireConnection())
    }

    @Test
    fun generationClearsSessions() {
        val manager = McpSessionManager()
        val session = manager.createSession(McpProtocolVersions.LATEST, "c")
        manager.bumpGeneration()
        assertEquals(null, manager.get(session.id))
    }

    @Test
    fun toolCatalogHasStrictSchemas() {
        McpToolCatalog.tools.forEach { tool ->
            assertEquals("object", tool.inputSchema.getString("type"))
            assertFalse(
                "tool ${tool.name} must not open additionalProperties",
                tool.inputSchema.optBoolean("additionalProperties", true),
            )
        }
        assertTrue(McpToolCatalog.tools.any { it.name == "navigate" })
        assertTrue(McpToolCatalog.tools.any { it.name == "list_debug_frames" })
        assertTrue(McpToolCatalog.tools.any { it.name == "patch_settings" })
        assertTrue(McpToolCatalog.tools.any { it.name == "get_runtime_snapshot" })
        assertTrue(McpToolCatalog.tools.any { it.name == "set_developer_enabled" })
        assertTrue(McpToolCatalog.tools.any { it.name == "get_mcp_status" })
        assertTrue(McpToolCatalog.tools.any { it.name == "set_mcp_tool_policy" })
        assertTrue(McpToolCatalog.resources.none { it.uri == "app://runtime/snapshot" })
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://mcp/status" })
        assertNotNull(McpToolCatalog.tool("get_status"))
        assertEquals(null, McpToolCatalog.tool("arm_automation"))
        assertEquals(null, McpToolCatalog.tool("download_algorithm"))
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_automation_enabled")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_mcp_enabled")!!.risk)
        assertTrue(
            McpToolCatalog.toolsJson().getJSONObject(0).getString("description")
                .contains("工具名:"),
        )
        // 全量 tools/list 与 resources 复用缓存实例（只读约定）。
        assertTrue(McpToolCatalog.toolsJson() === McpToolCatalog.toolsJson())
        assertTrue(McpToolCatalog.resourcesJson() === McpToolCatalog.resourcesJson())
        val filtered = McpToolPolicySupport.effectiveTools(
            top.azek431.hzzs.core.model.McpConfig(
                toolPolicies = mapOf(
                    "start_analysis" to top.azek431.hzzs.core.model.McpToolPolicy.DISABLED,
                ),
            ),
        )
        assertTrue(filtered.none { it.name == "start_analysis" })
        assertTrue(McpToolCatalog.toolsJson(filtered).length() < McpToolCatalog.tools.size)
    }

    @Test
    fun accessLogRingDropsOldestAndNeverRequiresArgs() {
        McpAccessLog.clear()
        McpAccessLog.setEnabled(true)
        repeat(McpAccessLog.CAPACITY + 5) { i ->
            McpAccessLog.record(
                remote = "127.0.0.1:$i",
                httpMethod = "POST",
                path = "/mcp",
                rpcMethod = "tools/call",
                tool = "get_status",
                httpStatus = 200,
                durationMs = i.toLong(),
            )
        }
        assertEquals(McpAccessLog.CAPACITY, McpAccessLog.size())
        val newest = McpAccessLog.snapshot(limit = 1).single()
        assertEquals(McpAccessLog.CAPACITY + 4L, newest.durationMs)
        val text = McpAccessLog.formatText(limit = 3)
        assertTrue(text.contains("get_status"))
        assertFalse(text.contains("Bearer"))
        McpAccessLog.setEnabled(false)
        McpAccessLog.record(
            remote = "x",
            httpMethod = "POST",
            path = "/mcp",
            httpStatus = 200,
            durationMs = 1,
            note = "should_not_append",
        )
        assertEquals(McpAccessLog.CAPACITY, McpAccessLog.size())
        McpAccessLog.setEnabled(true)
        McpAccessLog.clear()
        assertEquals(0, McpAccessLog.size())
    }

    @Test
    fun toolPolicySupportFiltersDisabledAndApproval() {
        val cfg = top.azek431.hzzs.core.model.McpConfig(
            toolPolicies = mapOf(
                "start_analysis" to top.azek431.hzzs.core.model.McpToolPolicy.DISABLED,
                "set_theme" to top.azek431.hzzs.core.model.McpToolPolicy.ALWAYS_ASK,
            ),
        )
        assertFalse(McpToolPolicySupport.isEnabled(cfg, "start_analysis"))
        assertTrue(McpToolPolicySupport.isEnabled(cfg, "get_status"))
        assertTrue(
            McpToolPolicySupport.effectiveTools(cfg).none { it.name == "start_analysis" },
        )
        assertTrue(
            McpToolPolicySupport.requiresPhoneApproval(
                McpToolRisk.WRITE,
                top.azek431.hzzs.core.model.McpPermissionLevel.FULL_ACCESS,
                top.azek431.hzzs.core.model.McpToolPolicy.ALWAYS_ASK,
            ),
        )
        assertEquals(
            null,
            McpToolPolicySupport.hardRejectReason(
                McpToolRisk.WRITE,
                top.azek431.hzzs.core.model.McpPermissionLevel.FULL_ACCESS,
                top.azek431.hzzs.core.model.McpToolPolicy.DEFAULT,
                hasTrustedSession = true,
            ),
        )
        assertNotNull(
            McpToolPolicySupport.hardRejectReason(
                McpToolRisk.HIGH_RISK,
                top.azek431.hzzs.core.model.McpPermissionLevel.TRUSTED_SESSION,
                top.azek431.hzzs.core.model.McpToolPolicy.DEFAULT,
                hasTrustedSession = true,
            ),
        )
        // ALLOW_WHEN_TRUSTED 不得把 HIGH_RISK 在 TRUSTED 下放行
        assertNotNull(
            McpToolPolicySupport.hardRejectReason(
                McpToolRisk.HIGH_RISK,
                top.azek431.hzzs.core.model.McpPermissionLevel.TRUSTED_SESSION,
                top.azek431.hzzs.core.model.McpToolPolicy.ALLOW_WHEN_TRUSTED,
                hasTrustedSession = true,
            ),
        )
        // ALWAYS_ASK 允许 HIGH_RISK 进入审批路径（hardReject 为 null）
        assertEquals(
            null,
            McpToolPolicySupport.hardRejectReason(
                McpToolRisk.HIGH_RISK,
                top.azek431.hzzs.core.model.McpPermissionLevel.TRUSTED_SESSION,
                top.azek431.hzzs.core.model.McpToolPolicy.ALWAYS_ASK,
                hasTrustedSession = true,
            ),
        )
    }

    @Test
    fun initializeCreatesSessionAndInitializedAccepts() = runBlocking {
        val sessions = McpSessionManager()
        val protocol = McpProtocol(
            sessions = sessions,
            actions = FakeActions(),
            serverVersion = "0.1.0-test",
        )
        val init = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", McpProtocolVersions.LATEST)
                        .put("capabilities", JSONObject())
                        .put(
                            "clientInfo",
                            JSONObject().put("name", "RikkaHub").put("version", "1.0"),
                        ),
                ),
            existingSessionId = null,
            protocolVersionHeader = null,
        )
        val initResp = init as McpProtocol.DispatchResult.JsonResponse
        assertEquals(200, initResp.status)
        assertNotNull(initResp.sessionId)
        val negotiated = initResp.body.getJSONObject("result").getString("protocolVersion")
        assertEquals(McpProtocolVersions.LATEST, negotiated)

        val note = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"),
            existingSessionId = initResp.sessionId,
            protocolVersionHeader = negotiated,
        )
        assertTrue(note is McpProtocol.DispatchResult.Accepted)
        assertTrue(sessions.get(initResp.sessionId)!!.initialized)
    }

    @Test
    fun notificationWithoutIdReturnsAccepted() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject().put("jsonrpc", "2.0").put("method", "notifications/cancelled"),
            existingSessionId = session.id,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        assertTrue(result is McpProtocol.DispatchResult.Accepted)
    }

    @Test
    fun toolsCallAfterHandshakeReturnsContent() = runBlocking {
        val sessions = McpSessionManager()
        // initialize 后会话已自动 markInitialized
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val init = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", McpProtocolVersions.LATEST)
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "OperitAI")),
                ),
            existingSessionId = null,
            protocolVersionHeader = null,
        ) as McpProtocol.DispatchResult.JsonResponse
        val sessionId = init.sessionId!!
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 9)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", "get_status")
                        .put("arguments", JSONObject()),
                ),
            existingSessionId = sessionId,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        val body = (result as McpProtocol.DispatchResult.JsonResponse).body
        assertTrue(body.has("result"))
        assertTrue(body.getJSONObject("result").has("content"))
    }

    @Test
    fun toolsCallWithoutInitializedFlagStillWorksAfterInitializeAutoReady() = runBlocking {
        // 兼容旧测试路径：手动 create 未 mark 的会话仍拒绝 tools/call
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", "get_status")
                        .put("arguments", JSONObject()),
                ),
            existingSessionId = session.id,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        val body = (result as McpProtocol.DispatchResult.JsonResponse).body
        assertTrue(body.has("error"))
        assertEquals(McpErrorCodes.NOT_INITIALIZED, body.getJSONObject("error").getInt("code"))
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 3)
                .put("method", "prompts/list"),
            existingSessionId = session.id,
            protocolVersionHeader = null,
        )
        val err = (result as McpProtocol.DispatchResult.JsonResponse).body.getJSONObject("error")
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, err.getInt("code"))
    }

    @Test
    fun unsupportedProtocolHeaderRejected() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 4)
                .put("method", "tools/list"),
            existingSessionId = session.id,
            protocolVersionHeader = "1.0.0",
        )
        assertTrue(result is McpProtocol.DispatchResult.HttpError)
        assertEquals(400, (result as McpProtocol.DispatchResult.HttpError).status)
    }

    @Test
    fun missingOrStaleSessionStillAllowsToolCallAndList() = runBlocking {
        // 服务重启后会话表清空；旧 Mcp-Session-Id 应降级为无会话，tools/call 仍可走权限层。
        val protocol = McpProtocol(McpSessionManager(), FakeActions(), serverVersion = "t")
        val call = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 5)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject().put("name", "get_status").put("arguments", JSONObject()),
                ),
            existingSessionId = "stale-session-id",
            protocolVersionHeader = null,
        )
        val callBody = (call as McpProtocol.DispatchResult.JsonResponse).body
        assertTrue(callBody.has("result"))
        assertTrue(callBody.getJSONObject("result").has("content"))

        val list = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 6)
                .put("method", "tools/list"),
            existingSessionId = null,
            protocolVersionHeader = null,
        )
        assertTrue(list is McpProtocol.DispatchResult.JsonResponse)
        assertTrue((list as McpProtocol.DispatchResult.JsonResponse).body.has("result"))
    }

    @Test
    fun rikkaHubImportJsonOmitsHeadersWhenAuthOff() {
        val open = McpServerState(running = true, port = 8765, token = "", requireAuth = false)
        val json = open.rikkaHubImportJson()
        assertTrue(json.contains("streamable_http"))
        assertTrue(json.contains("http://127.0.0.1:8765/mcp"))
        assertFalse(json.contains("Authorization"))
        val locked = McpServerState(running = true, port = 8765, token = "abc", requireAuth = true)
        assertTrue(locked.rikkaHubImportJson().contains("Bearer abc"))
    }

    @Test
    fun endpointDisplayModesAndClientDialects() {
        val state = McpServerState(running = true, port = 8765, token = "tok", requireAuth = true)
        assertEquals("127.0.0.1", state.resolveDisplayHost(McpEndpointDisplayMode.LOOPBACK))
        assertEquals("127.0.0.1", state.resolveDisplayHost(McpEndpointDisplayMode.ADB_FORWARD))
        assertEquals("tunnel.example", state.resolveDisplayHost(McpEndpointDisplayMode.CUSTOM, "tunnel.example"))
        assertEquals("127.0.0.1", state.resolveDisplayHost(McpEndpointDisplayMode.CUSTOM, "  "))
        assertEquals(
            "http://tunnel.example:8765/mcp",
            state.endpointUrlFor(McpEndpointDisplayMode.CUSTOM, "tunnel.example"),
        )
        assertEquals("adb forward tcp:8765 tcp:8765", state.adbForwardCommand())
        val claude = state.clientImportJson(
            dialect = McpClientImportDialect.CLAUDE_CODE,
            host = "127.0.0.1",
        )
        assertTrue(claude.contains("\"type\": \"http\""))
        assertTrue(claude.contains("http://127.0.0.1:8765/mcp"))
        assertTrue(claude.contains("Bearer tok"))
        val custom = state.clientImportJson(
            dialect = McpClientImportDialect.RIKKAHUB,
            host = "10.0.0.2",
        )
        assertTrue(custom.contains("streamable_http"))
        assertTrue(custom.contains("http://10.0.0.2:8765/mcp"))
        assertEquals("http://[::1]:8765/mcp", state.endpointUrl("::1"))
    }


    @Test
    fun lanAddressRankPrefersWifiOverCellularAndTailscale() {
        assertTrue(lanAddressRank("192.168.10.95") < lanAddressRank("10.50.131.111"))
        assertTrue(lanAddressRank("192.168.10.95") < lanAddressRank("100.84.182.74"))
        assertTrue(lanAddressRank("10.0.0.2") < lanAddressRank("100.84.182.74"))
        val ordered = listOf("100.84.182.74", "10.50.131.111", "192.168.10.95")
            .sortedWith(compareBy<String> { lanAddressRank(it) }.thenBy { it })
        assertEquals(listOf("192.168.10.95", "10.50.131.111", "100.84.182.74"), ordered)
    }

    @Test
    fun endpointDisplayModesIncludeLan() {
        val state = McpServerState(
            running = true,
            port = 8765,
            bindLocalhostOnly = false,
            lanAddresses = listOf("192.168.1.8", "10.0.0.2"),
        )
        assertEquals(
            "192.168.1.8",
            state.resolveDisplayHost(McpEndpointDisplayMode.LAN),
        )
        assertEquals(
            "10.0.0.2",
            state.resolveDisplayHost(McpEndpointDisplayMode.LAN, preferredLanHost = "10.0.0.2"),
        )
        assertEquals(
            "http://192.168.1.8:8765/mcp",
            state.endpointUrlFor(McpEndpointDisplayMode.LAN),
        )
        assertTrue(isAllowedMcpOrigin(null, allowLanBind = true))
        assertTrue(isAllowedMcpOrigin("http://127.0.0.1", allowLanBind = true))
        assertFalse(isAllowedMcpOrigin("http://evil.example", allowLanBind = true))
    }

    private class FakeActions : McpActionSurface {
        override suspend fun readResource(uri: String): JSONObject =
            JSONObject().put("uri", uri)

        override suspend fun call(
            tool: String,
            arguments: JSONObject,
            session: McpSessionManager.Session?,
        ): JSONObject = JSONObject().put("ok", true).put("tool", tool)
    }
}
