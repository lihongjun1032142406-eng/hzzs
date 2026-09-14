/**
 * 网络与更新设置页。
 *
 * 职责：下载来源、Wi‑Fi 策略、算法通道/自动检查、应用更新检查下载安装。
 * 数据流：偏好经 [update] 草稿预览；检查/下载/安装为 ViewModel 即时任务。
 * 边界：不绕过签名校验；不在 feature 内直接 HTTP。
 * 算法包检查/下载已接入：HTTPS 目录 + 哈希 + ZIP 白名单（当前暂未启用 Ed25519 签名验签）。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.UpdateUiState
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard

/**
 * 网络与更新设置页。
 *
 * 应用更新检查/下载/安装走 ViewModel 即时任务。
 * 未发布签名索引时检查失败为预期。本页不绕过证书绑定验签。
 * Clean Base：算法包下载/通道设置已随算法层清退移除。
 */
@Composable
fun NetworkUpdateSettingsScreen(
    config: AppConfig,
    updateState: UpdateUiState,
    update: ((AppConfig) -> AppConfig) -> Unit,
    onCheckApp: () -> Unit,
    onDownloadApp: () -> Unit,
    onInstallApp: () -> Unit,
    onIgnoreApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(
                title = "下载来源",
                description = "默认使用 Gitee，无法访问时自动切换 GitHub。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRadioCard(
                        title = "自动选择",
                        subtitle = "默认使用 Gitee，无法访问时自动切换 GitHub。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.AUTO,
                        onClick = {
                            update {
                                it.copy(update = it.update.copy(sourcePreference = UpdateSourcePreference.AUTO))
                            }
                        },
                    )
                    SettingsRadioCard(
                        title = "优先 Gitee",
                        subtitle = "始终先尝试 Gitee，失败再回退 GitHub。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.PREFER_GITEE,
                        onClick = {
                            update {
                                it.copy(
                                    update = it.update.copy(
                                        sourcePreference = UpdateSourcePreference.PREFER_GITEE,
                                    ),
                                )
                            }
                        },
                    )
                    SettingsRadioCard(
                        title = "优先 GitHub",
                        subtitle = "始终先尝试 GitHub，失败再回退 Gitee。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.PREFER_GITHUB,
                        onClick = {
                            update {
                                it.copy(
                                    update = it.update.copy(
                                        sourcePreference = UpdateSourcePreference.PREFER_GITHUB,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "网络策略",
                description = "仅限制大文件下载；小型目录检查不受该开关限制。",
            ) {
                SettingsSwitchRow(
                    title = "仅 Wi‑Fi 下载大文件",
                    subtitle = "应用 APK 大文件下载使用此策略；小型检查不受限制。",
                    checked = config.update.wifiOnly,
                    onCheckedChange = { value ->
                        update { it.copy(update = it.update.copy(wifiOnly = value)) }
                    },
                )
            }
        }


        item {
            SettingsSectionCard(
                title = "应用更新",
                description = "按来源偏好检查签名清单；安装前校验包名/版本/证书/哈希。未发布正式索引时检查失败是预期行为。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChannel.entries.forEach { channel ->
                        SettingsRadioCard(
                            title = channel.displayName(),
                            selected = config.update.channel == channel,
                            onClick = {
                                update { it.copy(update = it.update.copy(channel = channel)) }
                            },
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "自动检查应用更新",
                    checked = config.update.autoCheck,
                    onCheckedChange = { value ->
                        update { it.copy(update = it.update.copy(autoCheck = value)) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheckApp, enabled = !updateState.busy) {
                        Text(if (updateState.busy) "处理中…" else "检查更新")
                    }
                    if (updateState.available != null) {
                        OutlinedButton(onClick = onDownloadApp, enabled = !updateState.busy) {
                            Text("下载")
                        }
                    }
                    if (updateState.localApk != null) {
                        Button(onClick = onInstallApp, enabled = !updateState.busy) {
                            Text("安装")
                        }
                    }
                    if (updateState.available != null) {
                        TextButton(onClick = onIgnoreApp, enabled = !updateState.busy) {
                            Text("忽略此版本")
                        }
                    }
                }
                updateState.available?.let { available ->
                    Text(
                        "远端 ${available.manifest.versionName}（code ${available.manifest.versionCode}，来源 ${available.source.name}）\n" +
                            available.manifest.releaseNotes.take(240),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                updateState.message?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SettingsWarningCard(
                title = "不可信网络提示",
                body = "请勿在公共不可信网络强制关闭证书校验或安装未知来源算法包。官方算法必须通过签名校验。",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
