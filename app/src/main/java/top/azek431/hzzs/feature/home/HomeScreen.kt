/**
 * 首页概览（工具专业风）。
 *
 * 职责：展示已保存配置速览与进入运行/设置；不编辑草稿。
 * 数据流：只读 [SettingsRepository.config] 与运行状态。
 * 边界：不启动分析、不触碰权限型运行时能力。
 */
package top.azek431.hzzs.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HeroCard
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.HzzsMetricGrid
import top.azek431.hzzs.core.designsystem.HzzsPrimaryAction
import top.azek431.hzzs.core.designsystem.HzzsScrollPage
import top.azek431.hzzs.core.designsystem.HzzsSecondaryAction
import top.azek431.hzzs.core.designsystem.HzzsStatusStrip
import top.azek431.hzzs.core.designsystem.MetricTile
import top.azek431.hzzs.core.designsystem.PageHeader
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.StatusChip
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.VisionRuntimeController
import javax.inject.Inject

/** 首页只读已落盘配置与运行状态摘要。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: SettingsRepository,
    runtime: VisionRuntimeController,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repo.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    val status: StateFlow<RuntimeStatus> = runtime.status
}

/** 首页 UI：就绪摘要 + 单一主路径进入运行。 */
@Composable
fun HomeScreen(
    onOpenRuntime: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val status by vm.status.collectAsState()

    HzzsScrollPage(modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(
                title = stringResource(R.string.home_title),
                subtitle = stringResource(R.string.home_subtitle),
            )
        }

        item {
            HzzsStatusStrip {
                StatusChip(
                    if (status.running) {
                        stringResource(R.string.home_status_running)
                    } else {
                        stringResource(R.string.home_status_idle)
                    },
                    active = status.running,
                )
                StatusChip(config.captureBackend.displayName(), active = false)
            }
        }

        item {
            HeroCard(
                title = if (status.running) {
                    stringResource(R.string.home_hero_running)
                } else {
                    stringResource(R.string.home_hero_idle)
                },
                subtitle = config.captureBackend.displayName(),
                icon = Icons.Rounded.Visibility,
            ) {
                Text(
                    stringResource(R.string.home_hero_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HzzsPrimaryAction(
                    text = if (status.running) {
                        stringResource(R.string.home_open_runtime_running)
                    } else {
                        stringResource(R.string.home_open_runtime)
                    },
                    onClick = onOpenRuntime,
                    icon = Icons.Rounded.PlayArrow,
                )
            }
        }

        item {
            SectionCard {
                Text(
                    stringResource(R.string.home_config_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.home_config_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HzzsMetricGrid {
                        MetricTile(
                            label = stringResource(R.string.home_metric_capture),
                            value = config.captureBackend.displayName(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HzzsMetricGrid {
                        MetricTile(
                            label = stringResource(R.string.home_metric_automation),
                            value = if (config.automation.enabled) {
                                stringResource(R.string.home_automation_enabled)
                            } else {
                                stringResource(R.string.home_closed)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = stringResource(R.string.home_metric_mcp),
                            value = if (config.mcp.enabled) {
                                config.mcp.permissionLevel.displayName()
                            } else {
                                stringResource(R.string.home_closed)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            HzzsCallout(
                title = stringResource(R.string.home_security_title),
                text = stringResource(R.string.home_security_body),
                tone = HzzsCalloutTone.INFO,
                icon = Icons.Rounded.Security,
            )
        }

        item {
            HzzsSecondaryAction(
                text = stringResource(R.string.home_open_settings),
                onClick = onOpenSettings,
                icon = Icons.Rounded.Settings,
                tonal = true,
            )
        }
    }
}
