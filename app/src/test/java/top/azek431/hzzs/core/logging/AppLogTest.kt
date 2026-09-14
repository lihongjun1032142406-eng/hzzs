package top.azek431.hzzs.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.DeveloperConfig
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.preferences.ConfigJson

class AppLogTest {
    @Before
    fun setUp() {
        AppLog.clear()
        AppLog.configure(enabled = true, level = AppLogLevel.VERBOSE)
    }

    @Test
    fun redactMasksBearerAndTokenKeyValues() {
        val masked = AppLog.redact("Authorization: Bearer abcdef.ghij-klmn and token=supersecret")
        assertFalse(masked.contains("abcdef"))
        assertFalse(masked.contains("supersecret"))
        assertTrue(masked.contains("Bearer <redacted>"))
        assertTrue(masked.contains("<redacted>"))
    }

    @Test
    fun ringBufferRespectsCapacityAndOrder() {
        AppLog.configure(enabled = true, level = AppLogLevel.INFO)
        repeat(5) { index -> AppLog.i("test", "msg-$index") }
        val snap = AppLog.snapshot()
        assertEquals(5, snap.size)
        assertEquals("msg-0", snap.first().message)
        assertEquals("msg-4", snap.last().message)
    }

    @Test
    fun debugSuppressedWhenDeveloperDisabled() {
        AppLog.configure(enabled = false, level = AppLogLevel.VERBOSE)
        AppLog.d("test", "hidden-debug")
        AppLog.i("test", "visible-info")
        val messages = AppLog.snapshot().map { it.message }
        assertFalse(messages.contains("hidden-debug"))
        assertTrue(messages.contains("visible-info"))
    }

    @Test
    fun minLevelFiltersLowerSeverities() {
        AppLog.configure(enabled = true, level = AppLogLevel.WARN)
        AppLog.i("test", "info-drop")
        AppLog.w("test", "warn-keep")
        val messages = AppLog.snapshot().map { it.message }
        assertFalse(messages.contains("info-drop"))
        assertTrue(messages.contains("warn-keep"))
    }

    @Test
    fun queryFiltersByTagAndTextAndSupportsNewestFirst() {
        AppLog.configure(enabled = true, level = AppLogLevel.VERBOSE)
        AppLog.i("vision", "frame ok")
        AppLog.e("vision", "capture failed for demo")
        AppLog.w("vision", "capture slow")
        val onlyAlgo = AppLog.query(tagEquals = "vision")
        assertEquals(1, onlyAlgo.size)
        assertTrue(onlyAlgo.single().message.contains("activate failed"))
        val search = AppLog.query(query = "capture")
        assertEquals(1, search.size)
        val newest = AppLog.query(newestFirst = true)
        assertTrue(
            newest.first().message.contains("capture slow") ||
                newest.first().message.contains("activate") ||
                newest.first().message.contains("frame"),
        )
        assertEquals(AppLog.size().toLong().coerceAtLeast(1L) > 0, AppLog.revision() > 0)
        assertTrue(AppLog.knownTags().contains("vision"))
        assertTrue(AppLog.formatText(tagEquals = "vision").contains("capture failed"))
    }

    @Test
    fun clearBumpsRevision() {
        AppLog.i("app", "before-clear")
        val before = AppLog.revision()
        AppLog.clear()
        assertEquals(0, AppLog.size())
        assertTrue(AppLog.revision() > before)
    }

    @Test
    fun entriesHaveStableMonotonicIds() {
        AppLog.i("a", "one")
        AppLog.i("b", "two")
        val snap = AppLog.snapshot()
        assertEquals(2, snap.size)
        assertTrue(snap[0].id > 0)
        assertTrue(snap[1].id > snap[0].id)
        assertNotEquals(snap[0].id, snap[1].id)
    }

    @Test
    fun levelCountsAndTagCounts() {
        AppLog.i("vision", "i1")
        AppLog.i("vision", "i2")
        AppLog.w("algo", "w1")
        AppLog.e("algo", "e1")
        val counts = AppLog.levelCounts()
        assertEquals(2, counts.info)
        assertEquals(1, counts.warn)
        assertEquals(1, counts.error)
        assertEquals(4, counts.total)
        val tags = AppLog.tagCounts()
        // 同计数按字典序；algo / vision 各 2 条，algo 在前。
        assertEquals("algo", tags.first().first)
        assertEquals(2, tags.first().second)
        assertTrue(tags.any { it.first == "vision" && it.second == 2 })
    }
}

class DiagnosticsExporterTest {
    @Test
    fun reportOmitsBearerAndIncludesSummary() {
        AppLog.clear()
        AppLog.configure(enabled = true, level = AppLogLevel.INFO)
        AppLog.i("vision", "Authorization: Bearer should-not-appear")
        val report = DiagnosticsExporter.buildReport(
            versionName = "0.1.0-test",
            versionCode = 1L,
            config = AppConfig(developer = DeveloperConfig(enabled = true, logLevel = AppLogLevel.DEBUG)),
            mcp = McpDiagnosticsSnapshot(running = true, port = 8765, lastError = null),
            debugFrameCount = 3,
            runtime = RuntimeStatus(
                running = true,
                overlayVisible = false,
                overlayBlockReason = OverlayBlockReason.PERMISSION,
            ),
            logLimit = 50,
        )
        assertTrue(report.contains("versionName=0.1.0-test"))
        assertTrue(report.contains("mcp.port=8765"))
        assertTrue(report.contains("debugFrameCount=3"))
        assertTrue(report.contains("developer.logLevel=DEBUG"))
        assertTrue(report.contains("id=builtin.hzzs.base"))
        assertTrue(report.contains("version=0.1.0"))
        assertTrue(report.contains("generation=3"))
        assertTrue(report.contains("vision.overlayBlockReason=PERMISSION"))
        assertTrue(report.contains("capture.requested="))
        assertTrue(report.contains("capture.effective="))
        assertTrue(report.contains("capture.fallbackReason="))
        assertTrue(report.contains("gesture.requested="))
        assertTrue(report.contains("gesture.effective="))
        assertTrue(report.contains("a11y.connected="))
        assertTrue(report.contains("shizuku.ready="))
        assertTrue(report.contains("foreground.pkg="))
        assertTrue(report.contains("automation.disclaimerAcceptedVersion="))
        // 本地时区 + 偏移；不得再出现假 UTC 的 `...Z` 样式（无偏移）。
        assertTrue(report.contains("generatedAt="))
        assertTrue(
            // SimpleDateFormat XXX 在真实 UTC 时区会合法输出 Z；非 UTC 时输出 ±HH:MM。
            Regex("""generatedAt=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2})""")
                .containsMatchIn(report),
        )
        assertTrue(report.contains("Timestamps use the device local timezone with offset"))
        assertFalse(report.contains("should-not-appear"))
        assertFalse(report.contains("Bearer should"))
        assertTrue(report.contains("Bearer <redacted>") || report.contains("<redacted>"))
    }
}

class DeveloperConfigJsonTest {
    @Test
    fun configJsonRoundTripKeepsLogLevelAndDeveloperFields() {
        val original = AppConfig(
            developer = DeveloperConfig(
                enabled = true,
                saveDebugFrames = true,
                showCoordinateGrid = true,
                frameRateLimit = 45,
                logLevel = AppLogLevel.DEBUG,
            ),
        )
        val decoded = ConfigJson.decode(ConfigJson.encode(original))
        assertEquals(true, decoded.developer.enabled)
        assertEquals(true, decoded.developer.saveDebugFrames)
        assertEquals(true, decoded.developer.showCoordinateGrid)
        assertEquals(45, decoded.developer.frameRateLimit)
        assertEquals(AppLogLevel.DEBUG, decoded.developer.logLevel)
    }

    @Test
    fun legacyDeveloperWithoutLogLevelDefaultsToInfo() {
        val legacy = """
            {
              "schemaVersion": 6,
              "developer": {
                "enabled": true,
                "saveDebugFrames": false,
                "showCoordinateGrid": true,
                "frameRateLimit": 60,
                "nativeBenchmarkIterations": 200
              }
            }
        """.trimIndent()
        val decoded = ConfigJson.decode(legacy)
        assertEquals(true, decoded.developer.enabled)
        assertEquals(AppLogLevel.INFO, decoded.developer.logLevel)
        assertEquals(true, decoded.developer.showCoordinateGrid)
    }
}
