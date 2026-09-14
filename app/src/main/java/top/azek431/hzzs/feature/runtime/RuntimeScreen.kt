/**
 * 运行控制页（工具专业风操作台）。
 *
 * 职责：启停视觉分析、展示运行时指标。
 * 数据流：状态来自 [VisionRuntimeController]；配置只读。
 * 边界：feature 只发意图；不直接 JNI / 截图 / WindowManager。
 */
package top.azek431.hzzs.feature.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HeroCard
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.HzzsMetricGrid
import top.azek431.hzzs.core.designsystem.HzzsPrimaryAction
import top.azek431.hzzs.core.designsystem.HzzsScrollPage
import top.azek431.hzzs.core.designsystem.HzzsStatusStrip
import top.azek431.hzzs.core.designsystem.LocalHzzsStatusColors
import top.azek431.hzzs.core.designsystem.MetricTile
import top.azek431.hzzs.core.designsystem.PageHeader
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.StatusChip
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import javax.inject.Inject

@HiltViewModel
class RuntimeViewModel @Inject constructor(
    private val controller: VisionRuntimeController,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val status: StateFlow<RuntimeStatus> = controller.status
    val config: StateFlow<AppConfig> = settingsRepository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    var transientMessage by mutableStateOf<String?>(null)
        private set

    fun toggle() = viewModelScope.launch {
        if (status.value.running) controller.stop() else controller.start()
    }

    fun clearMessage() {
        transientMessage = null
    }
}


@Composable
fun RuntimeScreen(vm: RuntimeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val statusColors = LocalHzzsStatusColors.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm.transientMessage) {
        vm.transientMessage?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        HzzsScrollPage(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                PageHeader(
                    title = stringResource(R.string.runtime_title),
                    subtitle = stringResource(R.string.runtime_subtitle_default),
                )
            }

            item {
                HeroCard(
                    title = if (status.running) {
                        stringResource(R.string.runtime_hero_running)
                    } else {
                        stringResource(R.string.runtime_hero_stopped)
                    },
                    subtitle = status.activeBackend.displayName(),
                    icon = if (status.running) Icons.Rounded.Visibility else Icons.Rounded.Stop,
                ) {
                    HzzsStatusStrip {
                        StatusChip(
                            if (status.running) {
                                stringResource(R.string.runtime_chip_analyzing)
                            } else {
                                stringResource(R.string.runtime_chip_idle)
                            },
                            active = status.running,
                            activeColor = statusColors.running,
                        )
                        StatusChip(
                            if (status.captureReady) {
                                stringResource(R.string.runtime_chip_capture_ready)
                            } else {
                                stringResource(R.string.runtime_chip_capture_wait)
                            },
                            active = status.captureReady,
                        )
                        StatusChip(
                            if (status.overlayVisible) {
                                stringResource(R.string.runtime_chip_overlay_on)
                            } else {
                                stringResource(R.string.runtime_chip_overlay_off)
                            },
                            active = status.overlayVisible,
                        )
                    }

                    if (status.running) {
                        HzzsMetricGrid {
                            MetricTile(
                                label = stringResource(R.string.runtime_metric_fps),
                                value = "${"%.1f".format(status.fps)}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HzzsPrimaryAction(
                        text = if (status.running) {
                            stringResource(R.string.runtime_stop)
                        } else {
                            stringResource(R.string.runtime_start)
                        },
                        onClick = vm::toggle,
                        icon = if (status.running) Icons.Rounded.Stop else Icons.Rounded.Visibility,
                    )
                }
            }

            if (status.running && !status.overlayVisible) {
                when (status.overlayBlockReason) {
                    OverlayBlockReason.PERMISSION -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HzzsCallout(
                                title = stringResource(R.string.runtime_overlay_permission_title),
                                text = stringResource(R.string.runtime_overlay_permission_body),
                                tone = HzzsCalloutTone.WARNING,
                            )
                            HzzsPrimaryAction(
                                text = stringResource(R.string.runtime_overlay_permission_action),
                                onClick = { SystemCapabilityAccess.openOverlayPermissionSettings(context) },
                                icon = Icons.Rounded.Visibility,
                            )
                        }
                    }
                    OverlayBlockReason.ADD_VIEW_FAILED -> item {
                        HzzsCallout(
                            title = stringResource(R.string.runtime_overlay_add_failed_title),
                            text = stringResource(R.string.runtime_overlay_add_failed_body),
                            tone = HzzsCalloutTone.ERROR,
                        )
                    }
                    OverlayBlockReason.DISABLED -> item {
                        HzzsCallout(
                            title = stringResource(R.string.runtime_overlay_disabled_title),
                            text = stringResource(R.string.runtime_overlay_disabled_body),
                            tone = HzzsCalloutTone.INFO,
                        )
                    }
                    null -> Unit
                }
            }

            item {
                SectionCard {
                    if (!config.automation.enabled) {
                        Text(
                            stringResource(R.string.runtime_automation_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.runtime_automation_auto_mode_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            status.lastError?.let { error ->
                item {
                    HzzsCallout(
                        title = stringResource(R.string.runtime_error_title),
                        text = error,
                        tone = HzzsCalloutTone.ERROR,
                    )
                }
            }

            item {
                HzzsCallout(
                    text = stringResource(R.string.runtime_permission_hint),
                    tone = HzzsCalloutTone.INFO,
                    icon = Icons.Rounded.Security,
                )
            }
        }
    }
}
