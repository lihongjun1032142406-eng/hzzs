/**
 * 自动操作与安全设置页。
 *
 * 职责：手势后端、总开关、触发距离与节流；展示无障碍连接状态。
 * 数据流：automation 经 [update] 草稿预览；开启与高风险后端须确认。
 * 边界：不直接 shell/root；默认关闭，导入/迁移不得静默开启。全场景共用总开关。
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.platform.compat.GestureCapability
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess

@Composable
fun AutomationSettingsScreen(
    config: AppConfig,
    gestureCapabilities: List<GestureCapability>,
    update: ((AppConfig) -> AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var riskDialog by remember { mutableStateOf(false) }
    var pendingGestureBackend by remember { mutableStateOf<GestureBackend?>(null) }
    var accessibilityConnected by remember {
        mutableStateOf(SystemCapabilityAccess.isAccessibilityServiceConnected())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityConnected = SystemCapabilityAccess.isAccessibilityServiceConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsWarningCard(
                title = "自动操作默认关闭",
                body = "自动操作可选手势后端：无障碍 dispatchGesture，或 Shizuku/Root 的 input 命令。" +
                    "识别误差、系统卡顿与游戏更新都可能导致误触。配置导入与迁移不会静默开启。" +
                    "自动推荐永不升权到 Root，也不会在自动路径弹出 Shizuku 授权。",
            )
        }
        item {
            SettingsSectionCard(
                title = "手势注入方式",
                description = "与截图后端独立。切换后写入草稿，需「保存并应用」。Shell input 的完成语义弱于无障碍回执。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gestureCapabilities.forEach { capability ->
                        SettingsRadioCard(
                            title = capability.title,
                            subtitle = "${capability.summary}（风险：${capability.riskLevel}）",
                            selected = config.automation.gestureBackend == capability.backend,
                            enabled = capability.supported,
                            trailing = when {
                                !capability.supported -> "不可用"
                                capability.recommended -> "推荐"
                                !capability.ready -> "未就绪"
                                capability.backend == GestureBackend.ROOT -> "高风险"
                                else -> null
                            },
                            onClick = {
                                val target = capability.backend
                                val escalate = target == GestureBackend.SHIZUKU ||
                                    target == GestureBackend.ROOT
                                val fromLow = config.automation.gestureBackend == GestureBackend.AUTO ||
                                    config.automation.gestureBackend == GestureBackend.ACCESSIBILITY
                                if (escalate && fromLow) {
                                    pendingGestureBackend = target
                                } else {
                                    update {
                                        it.copy(
                                            automation = it.automation.copy(gestureBackend = target),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionCard(
                title = stringResource(R.string.permission_accessibility_section),
                description = stringResource(R.string.permission_refresh_hint) +
                    " 无障碍手势与「自动推荐」优先路径需要已连接服务。",
            ) {
                Text(
                    if (accessibilityConnected) {
                        stringResource(R.string.permission_accessibility_connected)
                    } else {
                        stringResource(R.string.permission_accessibility_disconnected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (accessibilityConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedButton(
                    onClick = { SystemCapabilityAccess.openAccessibilitySettings(context) },
                ) {
                    Text(stringResource(R.string.permission_accessibility_open))
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "总开关",
                description = "开启后将在视觉分析运行时按识别结果自动发送手势。" +
                    "甜品工厂、竹影书屋与海盐客厅共用本开关，无额外赛季硬锁。",
            ) {
                SettingsSwitchRow(
                    title = "启用自动操作",
                    // 风险对话框打开期间保持「开」外观，避免受控 Switch 弹回关、用户以为没生效再拨一次。
                    checked = config.automation.enabled || riskDialog,
                    subtitle = when {
                        riskDialog -> "请在对话框中完成风险确认（倒计时结束后可确认）"
                        config.automation.enabled -> "已开启：分析运行中按门控规划手势"
                        else -> "默认关闭；开启须阅读风险说明"
                    },
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (riskDialog) return@SettingsSwitchRow
                            // 已接受当前免责声明版本时直接开启，避免重复风险弹窗。
                            if (config.automation.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION) {
                                update {
                                    it.copy(
                                        automation = it.automation.copy(
                                            enabled = true,
                                            disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
                                        ),
                                    )
                                }
                            } else {
                                riskDialog = true
                            }
                        } else {
                            riskDialog = false
                            update { it.copy(automation = it.automation.copy(enabled = false)) }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow(
                    title = "自动复活",
                    subtitle = if (config.automation.autoReviveEnabled) {
                        "已开启：无障碍识别「原地复活 / 重新冒险」并点按钮中心（不用找色）；" +
                            "与上方障碍自动操作独立，冷却约 0.3 秒"
                    } else {
                        "已关闭：不自动点复活控件"
                    },
                    checked = config.automation.autoReviveEnabled,
                    onCheckedChange = { value ->
                        update {
                            it.copy(automation = it.automation.copy(autoReviveEnabled = value))
                        }
                    },
                )
            }
        }
        item {
            SettingsSectionCard(
                title = "前台应用限制",
                description = "默认不限制前台包名。仅当你明确开启后，才只在列表中的应用前台派发手势。",
            ) {
                SettingsSwitchRow(
                    title = "仅允许指定应用",
                    subtitle = if (config.automation.restrictPackages) {
                        "已开启：前台包须命中下方列表"
                    } else {
                        "已关闭：任意前台应用均可（仍须所选手势后端可用与其它门控）"
                    },
                    checked = config.automation.restrictPackages,
                    onCheckedChange = { value ->
                        update {
                            val nextPackages = if (value && it.automation.allowedPackages.isEmpty()) {
                                top.azek431.hzzs.core.model.AutomationConfig.SUGGESTED_PACKAGES
                            } else {
                                it.automation.allowedPackages
                            }
                            it.copy(
                                automation = it.automation.copy(
                                    restrictPackages = value,
                                    allowedPackages = nextPackages,
                                ),
                            )
                        }
                    },
                )
                if (config.automation.restrictPackages) {
                    Text(
                        "当前允许：${
                            config.automation.allowedPackages.sorted().joinToString().ifBlank { "（空）" }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            update {
                                it.copy(
                                    automation = it.automation.copy(
                                        allowedPackages =
                                            top.azek431.hzzs.core.model.AutomationConfig.SUGGESTED_PACKAGES,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("填入建议包（快手系）")
                    }
                    Text(
                        "自定义包名请通过配置导入或 MCP 写入 automation.allowedPackages；" +
                            "开启限制后导入不会静默扩大列表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "节流门控",
                description = "限制动作频率，降低误触。",
            ) {
                LabeledSlider(
                    "每秒最多动作数",
                    config.automation.maxActionsPerSecond.toFloat(),
                    1f..8f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(maxActionsPerSecond = value.toInt()))
                    }
                }
                LabeledSlider(
                    "失败重试上限",
                    config.automation.retryLimit.toFloat(),
                    0f..2f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(retryLimit = value.toInt()))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (riskDialog) {
        AutomationRiskDialog(
            onDismiss = { riskDialog = false },
            onConfirm = {
                riskDialog = false
                update {
                    it.copy(
                        automation = it.automation.copy(
                            enabled = true,
                            disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
                        ),
                    )
                }
            },
        )
    }
    pendingGestureBackend?.let { target ->
        GestureBackendRiskDialog(
            backend = target,
            onDismiss = { pendingGestureBackend = null },
            onConfirm = {
                pendingGestureBackend = null
                update {
                    it.copy(automation = it.automation.copy(gestureBackend = target))
                }
            },
        )
    }
}

@Composable
private fun AutomationRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("自动操作风险说明") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "自动操作会按所选注入后端模拟点击或滑动（无障碍手势或 shell input）。" +
                        "识别误差、系统卡顿、游戏更新和网络延迟都可能导致错误操作。请自行承担使用风险。",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text("我已阅读并理解风险")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = checked && remaining <= 0,
            ) {
                Text(if (remaining > 0) "请等待 ${remaining}s" else "确认开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun GestureBackendRiskDialog(
    backend: GestureBackend,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var checked by remember(backend) { mutableStateOf(false) }
    val body = when (backend) {
        GestureBackend.SHIZUKU ->
            "Shizuku input 通过 shell 执行 input tap/swipe，完成语义弱于无障碍回执；" +
                "前台包由 dumpsys 探测，OEM 差异可能导致门控失败。需已安装并授权 Shizuku。"
        GestureBackend.ROOT ->
            "Root input 通过 su 执行 input，风险最高。应用不会代为提权；误触与兼容问题自负。"
        else -> "将切换到手势后端 ${backend.name}。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("切换手势后端") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text("我已理解风险")
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = checked) { Text("确认切换") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
