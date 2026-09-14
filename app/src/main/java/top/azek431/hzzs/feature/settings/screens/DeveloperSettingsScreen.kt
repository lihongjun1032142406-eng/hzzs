/**
 * 开发者设置页。
 *
 * 职责：调试帧管理、日志级别、强制截图后端、坐标网格、系统指针位置等高级调试项。
 * 安全：关于页连点版本号 7 次开启 [DeveloperConfig.enabled]；本页开关可关闭，关闭后设置首页隐藏入口。
 * 边界：不启动 MCP 服务本体；诊断导出不含 Bearer；系统指针位置经 [SystemCapabilityAccess]
 *（可点授权 Shizuku → 绝对路径 settings 首成功即停 → WRITE_SETTINGS → Root），不静默要权。
 * 设置分类与关于入口共用本 Composable。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.developerLabel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsNavigationRow
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.platform.compat.PointerLocationWritePath
import top.azek431.hzzs.platform.compat.PointerLocationWriteResult
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.platform.compat.isSupportedOnThisDevice
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend

/**
 * 开发者选项设置页（设置分类与关于页入口共用）。
 *
 * 本页开关可关闭 [DeveloperConfig.enabled]；关闭后调试项隐藏，设置首页入口也会消失。
 * 再次开启需在关于页连续点击版本号 7 次。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperSettingsScreen(
    developerEnabled: Boolean,
    config: top.azek431.hzzs.core.model.AppConfig,
    update: ((top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig) -> Unit,
    debugFrameCount: Int,
    onRefreshDebugFrames: () -> Unit = {},
    onClearDebugFrames: () -> Unit = {},
    onBuildDiagnostics: () -> String = { "" },
    onOpenLogViewer: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Pre-compute strings for use inside non-@Composable click handlers
    val exportChooserTitle = stringResource(R.string.dev_export_chooser)
    val diagnosticEmptyMsg = stringResource(R.string.dev_diagnostic_empty)
    val copyFailedMsg = stringResource(R.string.dev_copy_failed)
    val exportFailedTemplate = stringResource(R.string.dev_export_failed)
    val generateFailedTemplate = stringResource(R.string.dev_generate_failed)
    val diagnosticCopiedTemplate = stringResource(R.string.dev_diagnostic_copied)
    val followSettingsLabel = stringResource(R.string.dev_force_capture_follow)
    val unavailableSuffix = stringResource(R.string.dev_force_capture_unavailable)
    val fellBackLabel = stringResource(R.string.dev_force_capture_fell_back)
    val pointerNeedWriteMsg = stringResource(R.string.dev_pointer_location_need_write)
    val pointerWriteFailedMsg = stringResource(R.string.dev_pointer_location_write_failed)
    val pointerShizukuFailedMsg = stringResource(R.string.dev_pointer_location_shizuku_failed)
    val pointerViaWriteSettings = stringResource(R.string.dev_pointer_location_via_write_settings)
    val pointerViaShizuku = stringResource(R.string.dev_pointer_location_via_shizuku)
    val pointerViaRoot = stringResource(R.string.dev_pointer_location_via_root)
    val pointerBusyMsg = stringResource(R.string.dev_pointer_location_busy)
    val pointerShizukuGrantedMsg = stringResource(R.string.dev_pointer_location_shizuku_granted)
    val pointerShizukuDeniedMsg = stringResource(R.string.dev_pointer_location_shizuku_denied)
    val pointerShizukuOfflineMsg = stringResource(R.string.dev_pointer_location_shizuku_offline)

    // 系统指针位置：真相源是 Settings.System，不进 AppConfig；回页时刷新。
    var canWriteSystem by remember {
        mutableStateOf(SystemCapabilityAccess.canWriteSystemSettings(context))
    }
    var shizukuAuthorized by remember {
        mutableStateOf(SystemCapabilityAccess.isShizukuAuthorized())
    }
    var shizukuBinder by remember {
        mutableStateOf(SystemCapabilityAccess.isShizukuBinderAlive())
    }
    var pointerLocationOn by remember {
        mutableStateOf(SystemCapabilityAccess.isPointerLocationEnabled(context))
    }
    var pointerBusy by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canWriteSystem = SystemCapabilityAccess.canWriteSystemSettings(context)
                shizukuAuthorized = SystemCapabilityAccess.isShizukuAuthorized()
                shizukuBinder = SystemCapabilityAccess.isShizukuBinderAlive()
                pointerLocationOn = SystemCapabilityAccess.isPointerLocationEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pointerSubtitle = when {
        shizukuAuthorized -> stringResource(R.string.dev_pointer_location_subtitle_shizuku)
        canWriteSystem -> stringResource(R.string.dev_pointer_location_subtitle)
        shizukuBinder -> stringResource(R.string.dev_pointer_location_subtitle_shizuku_need_grant)
        else -> stringResource(R.string.dev_pointer_location_subtitle_elevated)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. 开发者开关 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.dev_section_enable_title),
                description = stringResource(R.string.dev_section_enable_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.dev_enable_switch),
                    checked = developerEnabled,
                    // 仅允许关闭；再次开启必须走关于页连点版本号。
                    onCheckedChange = { value ->
                        if (!value) {
                            update { it.copy(developer = it.developer.copy(enabled = false)) }
                        }
                    },
                )
                Text(
                    stringResource(
                        if (developerEnabled) {
                            R.string.dev_enable_subtitle
                        } else {
                            R.string.dev_enable_subtitle_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (developerEnabled) {
            // ── 2. 调试帧管理 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_debug_frames_title),
                    description = stringResource(R.string.dev_debug_frames_desc, debugFrameCount),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.dev_save_debug_frames),
                        checked = config.developer.saveDebugFrames,
                        onCheckedChange = { value ->
                            update { it.copy(developer = it.developer.copy(saveDebugFrames = value)) }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefreshDebugFrames) {
                            Text(stringResource(R.string.dev_refresh_count))
                        }
                        TextButton(
                            onClick = onClearDebugFrames,
                            enabled = debugFrameCount > 0,
                        ) {
                            Text(stringResource(R.string.dev_clear_debug_frames))
                        }
                    }
                }
            }

            // ── 3. 坐标网格 / 系统指针位置 / 导航 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_tools_title),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.dev_show_coordinate_grid),
                        subtitle = stringResource(R.string.dev_show_coordinate_grid_subtitle),
                        checked = config.developer.showCoordinateGrid,
                        onCheckedChange = { value ->
                            update {
                                it.copy(developer = it.developer.copy(showCoordinateGrid = value))
                            }
                        },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.dev_pointer_location_title),
                        subtitle = pointerSubtitle,
                        checked = pointerLocationOn,
                        enabled = !pointerBusy,
                        onCheckedChange = { wantOn ->
                            if (pointerBusy) {
                                onMessage(pointerBusyMsg)
                                return@SettingsSwitchRow
                            }
                            val previous = pointerLocationOn
                            pointerLocationOn = wantOn
                            pointerBusy = true
                            scope.launch {
                                try {
                                    val result =
                                        SystemCapabilityAccess.setPointerLocationEnabledBestEffort(
                                            context,
                                            wantOn,
                                        )
                                    canWriteSystem =
                                        SystemCapabilityAccess.canWriteSystemSettings(context)
                                    shizukuAuthorized =
                                        SystemCapabilityAccess.isShizukuAuthorized()
                                    shizukuBinder =
                                        SystemCapabilityAccess.isShizukuBinderAlive()
                                    when (result) {
                                        is PointerLocationWriteResult.Success -> {
                                            pointerLocationOn =
                                                SystemCapabilityAccess.isPointerLocationEnabled(
                                                    context,
                                                )
                                            val base = when (result.path) {
                                                PointerLocationWritePath.WRITE_SETTINGS ->
                                                    pointerViaWriteSettings
                                                PointerLocationWritePath.SHIZUKU ->
                                                    pointerViaShizuku
                                                PointerLocationWritePath.ROOT ->
                                                    pointerViaRoot
                                            }
                                            val via = result.via
                                                ?.takeIf { it.isNotBlank() && it != "content_resolver" }
                                                ?.let { " · $it" }
                                                .orEmpty()
                                            onMessage(base + via)
                                        }
                                        is PointerLocationWriteResult.Failed -> {
                                            pointerLocationOn = result.observedEnabled
                                            val shellHint = result.lastShellDetail
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { " ($it)" }
                                                .orEmpty()
                                            when {
                                                result.shizukuAuthorized ->
                                                    onMessage(pointerShizukuFailedMsg + shellHint)
                                                result.shizukuBinderAlive &&
                                                    !result.shizukuAuthorized ->
                                                    onMessage(pointerShizukuDeniedMsg)
                                                !result.canWriteSettings -> {
                                                    onMessage(pointerNeedWriteMsg)
                                                    SystemCapabilityAccess.openManageWriteSettings(
                                                        context,
                                                    )
                                                }
                                                else ->
                                                    onMessage(pointerWriteFailedMsg + shellHint)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    pointerLocationOn =
                                        SystemCapabilityAccess.isPointerLocationEnabled(context)
                                    shizukuAuthorized =
                                        SystemCapabilityAccess.isShizukuAuthorized()
                                    shizukuBinder =
                                        SystemCapabilityAccess.isShizukuBinderAlive()
                                    onMessage(pointerWriteFailedMsg)
                                } finally {
                                    pointerBusy = false
                                }
                            }
                        },
                    )
                    if (shizukuBinder && !shizukuAuthorized) {
                        OutlinedButton(
                            onClick = {
                                if (pointerBusy) {
                                    onMessage(pointerBusyMsg)
                                    return@OutlinedButton
                                }
                                pointerBusy = true
                                scope.launch {
                                    try {
                                        val ok = SystemCapabilityAccess.requestShizukuPermission()
                                        shizukuAuthorized =
                                            SystemCapabilityAccess.isShizukuAuthorized()
                                        shizukuBinder =
                                            SystemCapabilityAccess.isShizukuBinderAlive()
                                        onMessage(
                                            when {
                                                ok || shizukuAuthorized -> pointerShizukuGrantedMsg
                                                !shizukuBinder -> pointerShizukuOfflineMsg
                                                else -> pointerShizukuDeniedMsg
                                            },
                                        )
                                    } finally {
                                        pointerBusy = false
                                    }
                                }
                            },
                            enabled = !pointerBusy,
                        ) {
                            Text(stringResource(R.string.dev_pointer_location_request_shizuku))
                        }
                    }
                    if (!canWriteSystem) {
                        OutlinedButton(
                            onClick = {
                                SystemCapabilityAccess.openManageWriteSettings(context)
                            },
                        ) {
                            Text(stringResource(R.string.dev_pointer_location_open_write_settings))
                        }
                    }
                    SettingsNavigationRow(
                        title = stringResource(R.string.dev_open_log_viewer),
                        subtitle = stringResource(R.string.dev_open_log_viewer_subtitle),
                        onClick = onOpenLogViewer,
                    )
                }
            }

            // ── 4. 强制截图后端 ──
            item {
                val captureResolution = resolveEffectiveCaptureBackend(
                    captureBackend = config.captureBackend,
                    developerEnabled = true,
                    forceCaptureBackend = config.developer.forceCaptureBackend,
                )
                SettingsSectionCard(
                    title = stringResource(R.string.dev_force_capture_backend_title),
                    description = stringResource(R.string.dev_force_capture_backend_desc),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (listOf<CaptureBackend?>(null) + CaptureBackend.entries).forEach { backend ->
                            val supported = backend == null || backend.isSupportedOnThisDevice()
                            FilterChip(
                                selected = config.developer.forceCaptureBackend == backend,
                                onClick = {
                                    update {
                                        it.copy(
                                            developer = it.developer.copy(
                                                forceCaptureBackend = backend,
                                            ),
                                        )
                                    }
                                },
                                label = {
                                    val base = backend?.developerLabel() ?: followSettingsLabel
                                    Text(
                                        if (backend != null && !supported) {
                                            "$base$unavailableSuffix"
                                        } else {
                                            base
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (captureResolution.fellBack) {
                        Text(
                            stringResource(
                                R.string.dev_force_capture_fallback_reason,
                                captureResolution.effective.developerLabel(),
                            ) + "：" + (captureResolution.fallbackReason ?: fellBackLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── 5. 日志级别 / 缓冲容量 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_log_level_title),
                    description = stringResource(R.string.dev_log_level_desc),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLogLevel.entries.forEach { level ->
                            SettingsRadioCard(
                                title = level.displayName(),
                                selected = config.developer.logLevel == level,
                                onClick = {
                                    update {
                                        it.copy(developer = it.developer.copy(logLevel = level))
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dev_log_ring_capacity, config.developer.logRingCapacity),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = config.developer.logRingCapacity.toFloat(),
                        onValueChange = { value ->
                            update {
                                it.copy(
                                    developer = it.developer.copy(logRingCapacity = value.toInt()),
                                )
                            }
                        },
                        valueRange = 500f..3000f,
                        steps = 4,
                    )
                }
            }


            // ── 6. 识别帧率上限 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_frame_rate_title),
                    description = stringResource(R.string.dev_frame_rate_desc),
                ) {
                    Text(
                        stringResource(R.string.dev_frame_rate_current, config.developer.frameRateLimit),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = config.developer.frameRateLimit.toFloat(),
                        onValueChange = { value ->
                            update {
                                it.copy(
                                    developer = it.developer.copy(frameRateLimit = value.toInt()),
                                )
                            }
                        },
                        valueRange = 1f..120f,
                    )
                }
            }


            // ── 8. 诊断导出 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_diagnostics_title),
                    description = stringResource(R.string.dev_diagnostics_desc),
                ) {
                    Text(
                        stringResource(R.string.dev_diagnostics_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val report = onBuildDiagnostics()
                                check(report.isNotBlank()) { diagnosticEmptyMsg }
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "HZZS diagnostics")
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, exportChooserTitle),
                                )
                            }.onFailure { error ->
                                onMessage(
                                    exportFailedTemplate.format(
                                        error.message ?: error.javaClass.simpleName,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dev_export_diagnostics))
                    }
                    TextButton(
                        onClick = {
                            val report = runCatching { onBuildDiagnostics() }.getOrElse { error ->
                                onMessage(
                                    generateFailedTemplate.format(
                                        error.message ?: error.javaClass.simpleName,
                                    ),
                                )
                                return@TextButton
                            }
                            if (report.isBlank()) {
                                onMessage(diagnosticEmptyMsg)
                                return@TextButton
                            }
                            val ok = top.azek431.hzzs.core.platform.ClipboardHelper.copyText(
                                context,
                                "HZZS diagnostics",
                                report,
                            )
                            onMessage(
                                if (ok) {
                                    diagnosticCopiedTemplate.format(report.lines().size)
                                } else {
                                    copyFailedMsg
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.dev_copy_to_clipboard))
                    }
                    SettingsNavigationRow(
                        title = stringResource(R.string.dev_open_log_viewer),
                        subtitle = stringResource(R.string.dev_open_log_viewer_subtitle),
                        onClick = onOpenLogViewer,
                    )
                }
            }
        }

        // ── 9. 安全提示 ──
        item {
            SettingsWarningCard(
                title = stringResource(R.string.dev_security_title),
                body = stringResource(R.string.dev_security_body),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
