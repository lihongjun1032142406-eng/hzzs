#!/usr/bin/env python3
"""Single-module architecture, Android safety, MCP and JinChanAI Clean Base invariants.

Clean Base 阶段说明：
- 本分支已清退 HZZS 原游戏视觉算法（算法包 / 内置识别 / Tracker / 算法市场 / 原生视觉引擎）。
- 原 `native:*` / `algorithm:*` / `host-native:*` 检查随被测代码一起移除。
- 新增 `cleanbase:*` 检查，确保清退结果不再回退。
"""
from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ERRORS: list[str] = []
CHECKS: list[str] = []
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def check(value: bool, name: str, detail: str) -> None:
    (CHECKS if value else ERRORS).append(name if value else f"{name}: {detail}")


def read(path: str) -> str:
    target = ROOT / path
    check(target.exists(), f"file:{path}", "missing")
    return target.read_text(encoding="utf-8") if target.exists() else ""


# ── Architecture ─────────────────────────────────────────────────────────────
settings = read("settings.gradle.kts")
modules = re.findall(r'include\("(:[^"]+)"\)', settings)
check(modules == [":app"], "architecture:single-app-module", f"declared modules: {modules}")
for legacy in ("core", "domain", "data", "feature", "service", "native"):
    legacy_root = ROOT / legacy
    legacy_files = list(legacy_root.rglob("*")) if legacy_root.exists() else []
    check(
        not any(path.is_file() for path in legacy_files),
        f"architecture:no-root-{legacy}",
        "legacy source module remains",
    )

# ── Gradle ───────────────────────────────────────────────────────────────────
app_build = read("app/build.gradle.kts")
for token in ("minSdk = 24", "compileSdk = 37"):
    check(token in app_build, f"gradle:{token}", "expected build configuration missing")
check(
    "project(\"" not in app_build,
    "gradle:no-project-dependencies",
    "single module still depends on project modules",
)
check(
    "androidTestImplementation(platform(libs.compose.bom))" in app_build,
    "gradle:android-test-compose-bom",
    "Compose Android-test dependencies require the BOM",
)
# Clean Base：不再声明原生构建，构建链不再需要 NDK/CMake。
for token in ("externalNativeBuild", "ndkVersion", "abiFilters"):
    check(
        token not in app_build,
        f"cleanbase:gradle-no-{token}",
        f"{token} must be gone from app/build.gradle.kts",
    )

# ── Compose hygiene ──────────────────────────────────────────────────────────
for source in (ROOT / "app/src/main/java").rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    relative = source.relative_to(ROOT)
    if "LazyColumn(" in text:
        check(
            "import androidx.compose.foundation.lazy.LazyColumn" in text,
            f"compose:{relative}:lazy-import",
            "missing import",
        )
    check(
        "androidx.hilt.navigation.compose.hiltViewModel" not in text,
        f"compose:{relative}:hilt-import",
        "deprecated import",
    )
    check(
        "top.azek431.hzzs.feature.about.R" not in text,
        f"resources:{relative}:single-R",
        "old module R reference",
    )

# ── Manifest ─────────────────────────────────────────────────────────────────
manifest_root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
application = manifest_root.find("application")
check(application is not None, "manifest:application", "missing application")
if application is not None:
    check(
        application.attrib.get(ANDROID_NS + "usesCleartextTraffic") == "false",
        "manifest:https-only",
        "cleartext enabled",
    )
    exported = []
    for tag in ("activity", "service", "receiver", "provider"):
        for node in application.findall(tag):
            if node.attrib.get(ANDROID_NS + "exported") == "true":
                exported.append(node.attrib.get(ANDROID_NS + "name", ""))
    allowed_exported = {".MainActivity", "rikka.shizuku.ShizukuProvider"}
    check(
        set(exported) <= allowed_exported and ".MainActivity" in exported,
        "manifest:minimal-exported-surface",
        f"exported={exported}",
    )
manifest_text = read("app/src/main/AndroidManifest.xml")
for token in (
    "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "FOREGROUND_SERVICE_SPECIAL_USE",
    'foregroundServiceType="specialUse"',
    "PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
    "McpForegroundService",
    "BIND_ACCESSIBILITY_SERVICE",
):
    check(token in manifest_text, f"manifest:{token}", "missing")

# ── Config model ─────────────────────────────────────────────────────────────
models = read("app/src/main/java/top/azek431/hzzs/core/model/AppModels.kt")
for token in ("ASK_EVERY_TIME", "FULL_ACCESS", "OverlayStyle"):
    check(token in models, f"model:{token}", "configuration model missing")
for token in ("JINCHAN_CLEAN_BASE = true", "ACTION_ENABLED = false", "OVERLAY_DEFAULT_ENABLED = false"):
    check(token in models, f"cleanbase:model:{token}", "clean-base safety constant missing")
check(
    "AppConfig.OVERLAY_DEFAULT_ENABLED" in models,
    "cleanbase:overlay-default-off",
    "OverlayConfig default must follow OVERLAY_DEFAULT_ENABLED",
)

# ── Settings repository ──────────────────────────────────────────────────────
settings_repo = read("app/src/main/java/top/azek431/hzzs/core/preferences/SettingsRepository.kt")
for token in (
    "preview.value = null",
    "disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION",
    "MAX_CONFIG_BYTES",
):
    check(token in settings_repo, f"settings:{token}", "safety invariant missing")
check(
    "restrictPackages" in settings_repo
    and "AutomationConfig.SUGGESTED_PACKAGES" in settings_repo
    and (
        "candidate.automation.restrictPackages || base.automation.restrictPackages" in settings_repo
        or "restrictPackages = candidate.automation.restrictPackages || base.automation.restrictPackages"
        in settings_repo
    ),
    "settings:allowed-packages-restrict",
    "safety invariant missing",
)

# ── Settings UI ──────────────────────────────────────────────────────────────
settings_ui = read("app/src/main/java/top/azek431/hzzs/feature/settings/SettingsScreen.kt")
settings_ui_dir = ROOT / "app/src/main/java/top/azek431/hzzs/feature/settings"
settings_ui_all = settings_ui
if settings_ui_dir.is_dir():
    for path in sorted(settings_ui_dir.rglob("*.kt")):
        settings_ui_all += "\n" + path.read_text(encoding="utf-8")
for token in (
    "DisposableEffect",
    "onLeaveComposition",
    "SettingsExitCoordinator",
    "请等待 ${remaining}s",
    "settings_save_and_apply",
    "settings_unsaved_title",
    "fun save(",
    "fun discard(",
    "repository.preview",
):
    check(token in settings_ui_all, f"settings-ui:{token}", "settings draft-save/risk UI missing")
check(
    "discardSilently" not in settings_ui_all,
    "settings-ui:no-silent-draft-discard",
    "settings dispose must not silently discard drafts",
)
check(
    "clearPreviewSilently" not in settings_ui_all,
    "settings-ui:no-legacy-clear-preview",
    "settings must not use clearPreviewSilently",
)

# ── Onboarding ───────────────────────────────────────────────────────────────
onboarding = read("app/src/main/java/top/azek431/hzzs/feature/onboarding/OnboardingScreen.kt")
for token in ("onboardingPageMetas", "acceptedDisclaimerVersion", "enabled = false", "onboarding_risk_wait"):
    check(token in onboarding, f"onboarding:{token}", "first-run invariant missing")
check(
    "请等待 ${remaining}s" not in onboarding,
    "onboarding:no-hardcoded-wait",
    "onboarding risk wait must use stringResource",
)

# ── MCP transport + safety ───────────────────────────────────────────────────
mcp_dir = ROOT / "app/src/main/java/top/azek431/hzzs/mcp"
mcp = ""
if mcp_dir.is_dir():
    for path in sorted(mcp_dir.rglob("*.kt")):
        mcp += "\n" + path.read_text(encoding="utf-8")
else:
    mcp = read("app/src/main/java/top/azek431/hzzs/mcp/McpService.kt")
for token in (
    "InetAddress.getLoopbackAddress()",
    "127.0.0.1",
    "Authorization",
    "ASK_EVERY_TIME",
    "requestApproval",
    "FULL_ACCESS",
    "navigate",
    "MAX_BODY_BYTES",
    "isAllowedLoopbackOrigin",
    "isAllowedMcpOrigin",
    "MessageDigest.isEqual",
    "settings.snapshot().mcp",
    "list_debug_frames",
    "clear_debug_frames",
    "Mcp-Session-Id",
    "notifications/initialized",
    "TRUSTED_SESSION",
    "rejectPendingApproval",
    "MAX_CONCURRENT_CONNECTIONS",
    "additionalProperties",
    "requireAuth",
    "authToken",
    "generateMcpAuthToken",
    "bindLocalhostOnly",
    "listLanIpv4Addresses",
    "McpToolPolicy",
    "toolPolicies",
    "get_mcp_status",
    "set_mcp_tool_policy",
):
    check(token in mcp, f"mcp:{token}", "MCP control/safety invariant missing")
check(
    "bindLocalhostOnly" in mcp and "127.0.0.1" in mcp and "0.0.0.0" in mcp,
    "mcp:loopback-default",
    "MCP must default to loopback and expose gated 0.0.0.0 LAN path",
)
check(
    "config.bindLocalhostOnly" in mcp and "listLanIpv4Addresses" in mcp,
    "mcp:lan-bind-gated",
    "LAN bind must read config.bindLocalhostOnly and list LAN IPs",
)
check(
    'put("additionalProperties", true)' not in mcp
    and "put('additionalProperties', true)" not in mcp,
    "mcp:no-open-tool-schema",
    "tool inputSchema must not use additionalProperties:true",
)

# ── Capture ──────────────────────────────────────────────────────────────────
capture = read("app/src/main/java/top/azek431/hzzs/service/capture/CaptureSources.kt")
check(
    "import android.os.Process\n" not in capture,
    "capture:android-process-alias",
    "android.os.Process shadows java.lang.Process used by ProcessBuilder",
)
check(
    "private suspend fun waitForExit(process: java.lang.Process" in capture
    and "private fun java.lang.Process.destroyCompat()" in capture,
    "capture:java-process-contract",
    "root command helpers must use java.lang.Process explicitly",
)
auto = (
    capture.split("class AutoFrameSource", 1)[1].split("class MediaProjectionFrameSource", 1)[0]
    if "class AutoFrameSource" in capture
    else ""
)
check("root" not in auto.lower() and "shizuku" not in auto.lower(), "capture:auto-low-permission", "AUTO escalates")
for token in (
    "MAX_FRAME_DIMENSION = 4_096",
    "MAX_FRAME_PIXELS = 8_388_608L",
    "process.errorStream",
    "ACCESSIBILITY_CALLBACK_TIMEOUT_MS",
    "createOrResizeDisplay",
    "waitForExit(process, timeoutMs)",
    "destroyCompat()",
):
    check(token in capture, f"capture:{token}", "capture bound missing")
check(
    "ActivityResultContracts.StartActivityForResult()" in capture
    and "startActivityForResult" not in capture
    and "override fun onActivityResult" not in capture,
    "capture:activity-result-api",
    "screen-capture consent must use the Activity Result API",
)

frame_capture = read("app/src/main/java/top/azek431/hzzs/service/capture/FrameCapture.kt")
frame_test = read("app/src/test/java/top/azek431/hzzs/service/capture/FrameSequenceTest.kt")
check(
    "private val clockNanos: () -> Long" in frame_capture
    and "FrameSequencer(clockNanos =" in frame_test,
    "capture:jvm-safe-frame-clock",
    "local JVM tests must not call Android SystemClock directly",
)

theme_test = read("app/src/test/java/top/azek431/hzzs/core/theme/ThemePackageTest.kt")
check(
    'sanitized.has("script")' in theme_test
    and 'reencoded.contains("script")' not in theme_test,
    "theme:json-key-sanitization-test",
    "theme security tests must inspect JSON keys rather than substrings",
)

for context_file in (
    "app/src/main/java/top/azek431/hzzs/core/preferences/SettingsRepository.kt",
    "app/src/main/java/top/azek431/hzzs/core/update/UpdateModels.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/CaptureCapabilities.kt",
    "app/src/main/java/top/azek431/hzzs/service/capture/CaptureSources.kt",
    "app/src/main/java/top/azek431/hzzs/service/overlay/OverlayController.kt",
):
    context_text = read(context_file)
    check(
        "@param:ApplicationContext" in context_text
        and "\n    @ApplicationContext" not in context_text,
        f"kotlin:application-context-target:{context_file}",
        "Hilt qualifier must use an explicit Kotlin parameter target",
    )

# ── Clean Base: removed algorithm artifacts must stay removed ────────────────
removed_paths = (
    "app/src/main/cpp",
    "app/src/main/assets/algorithms",
    "algorithm-packs",
    "app/src/main/java/top/azek431/hzzs/core/algorithm",
    "app/src/main/java/top/azek431/hzzs/domain/vision",
    "app/src/main/java/top/azek431/hzzs/nativevision",
    "app/src/main/java/top/azek431/hzzs/feature/settings/screens/AlgorithmSettingsScreen.kt",
    "app/src/main/java/top/azek431/hzzs/feature/settings/screens/AlgorithmPipelineScreen.kt",
    "app/src/main/java/top/azek431/hzzs/feature/settings/screens/DetectionSettingsScreen.kt",
    "app/src/main/java/top/azek431/hzzs/feature/settings/components/AlgorithmComponents.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/executor/AlgorithmExecutor.kt",
    "app/src/main/java/top/azek431/hzzs/data/vision/MultiObjectTracker.kt",
    "app/src/main/java/top/azek431/hzzs/data/vision/NativeVisionEngine.kt",
    "app/src/main/java/top/azek431/hzzs/data/vision/NativeBenchmarkRunner.kt",
    "app/src/main/java/top/azek431/hzzs/data/vision/DefaultActiveAlgorithmProvider.kt",
    "app/src/main/java/top/azek431/hzzs/domain/automation/TriggerDistanceAutoTuner.kt",
    "tools/algorithm",
    "tools/vision",
    "tools/vision_v2",
    "tools/vision_v3",
    ".github/workflows/algorithm-release.yml",
)
for relative in removed_paths:
    check(not (ROOT / relative).exists(), f"cleanbase:absent:{relative}", "algorithm artifact returned")

# 生产源码不得再出现算法层的包引用或旧算法工具名。
algorithm_tool_names = (
    "list_algorithms",
    "get_active_algorithm",
    "get_algorithm_pipeline",
    "set_active_algorithm",
    "refresh_algorithm_catalog",
    "download_algorithm",
    "upgrade_algorithms",
    "set_scene",
    "set_obstacle_enabled",
    "set_threshold",
)
check(
    not any(name in mcp for name in algorithm_tool_names),
    "cleanbase:mcp-no-algorithm-tools",
    "algorithm MCP tools must be gone",
)
algorithm_packages = ("top.azek431.hzzs.core.algorithm", "top.azek431.hzzs.domain.vision", "top.azek431.hzzs.nativevision")
for source in (ROOT / "app/src/main").rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    relative = source.relative_to(ROOT)
    for package in algorithm_packages:
        check(package not in text, f"cleanbase:no-import:{relative}:{package}", "algorithm package reference remains")

runtime = read("app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt")
check(
    "MultiObjectTracker" not in runtime and "VisionEngine" not in runtime,
    "cleanbase:runtime-no-engine",
    "runtime must stay capture-only",
)
check(
    "cancelPendingActions" in runtime,
    "cleanbase:runtime-cancel-actions",
    "runtime must keep the MCP cancel_actions surface",
)

# 保留基础设施：截图 / 无障碍 / 手势 / Shizuku / MCP transport / 日志。
for relative in (
    "app/src/main/java/top/azek431/hzzs/service/capture/FrameCapture.kt",
    "app/src/main/java/top/azek431/hzzs/service/capture/CaptureSources.kt",
    "app/src/main/java/top/azek431/hzzs/service/automation/HzzsAccessibilityService.kt",
    "app/src/main/java/top/azek431/hzzs/service/automation/GestureDispatcherFactory.kt",
    "app/src/main/java/top/azek431/hzzs/service/automation/ShellGestureDispatchers.kt",
    "app/src/main/java/top/azek431/hzzs/service/automation/ForegroundWindowProbe.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/CaptureCapabilities.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/GestureCapabilities.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/ShizukuHealthCheck.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/SystemCapabilityAccess.kt",
    "app/src/main/java/top/azek431/hzzs/service/vision/VisionAnalysisForegroundService.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/McpService.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/McpProtocol.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/McpHttp.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/McpEventBus.kt",
    "app/src/main/java/top/azek431/hzzs/mcp/McpSessionManager.kt",
    "app/src/main/java/top/azek431/hzzs/core/logging/AppLog.kt",
    "app/src/main/java/top/azek431/hzzs/core/logging/DiagnosticsExporter.kt",
):
    check((ROOT / relative).exists(), f"cleanbase:keep:{relative}", "infrastructure file missing")

gitignore = read(".gitignore")
check(
    ".hzzs-test-results/" in gitignore,
    "gitignore:test-results",
    "generated test results must remain outside Git",
)

result = {"status": "PASS" if not ERRORS else "FAIL", "checks": len(CHECKS), "errors": ERRORS}
print(json.dumps(result, ensure_ascii=False, indent=2))
if ERRORS:
    raise SystemExit(1)
