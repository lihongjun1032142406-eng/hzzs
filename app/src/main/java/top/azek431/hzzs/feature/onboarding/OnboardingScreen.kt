/**
 * 首次引导向导。
 *
 * 职责：5 步编辑内存草稿 [AppConfig]；主题/悬浮窗经 [onPreview] 即时预览。
 * 数据流：完成前不落盘；点「完成」时才 [onComplete] 写入并标记 onboarding/免责声明版本。
 * 边界：自动操作默认强制关闭；仅完成页高级区可开，且须风险对话框倒计时确认。
 *       不直接申请 Root/Shizuku/JNI；截图仅展示低权/可选无障碍，不推荐高级后端。
 * 动效：步骤切换走 [LocalHzzsMotion] shared-axis；减少动效时即时切换。
 */
package top.azek431.hzzs.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HeroCard
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.designsystem.LocalHzzsMotion
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.contentStepBackwardEnter
import top.azek431.hzzs.core.designsystem.contentStepBackwardExit
import top.azek431.hzzs.core.designsystem.contentStepForwardEnter
import top.azek431.hzzs.core.designsystem.contentStepForwardExit
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.ThemePreset
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.validated
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.platform.compat.CaptureCapability
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess

private val OnboardingCaptureBackends = setOf(
    CaptureBackend.AUTO,
    CaptureBackend.MEDIA_PROJECTION,
    CaptureBackend.ACCESSIBILITY,
)

private val OnboardingThemePresets = listOf(
    ThemePreset.FIRE_ORANGE,
    ThemePreset.BAMBOO,
    ThemePreset.OCEAN,
    ThemePreset.BLACK_GOLD,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    initial: AppConfig,
    captureCapabilities: List<CaptureCapability>,
    onPreview: (AppConfig) -> Unit,
    onComplete: (AppConfig) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    /**
     * 草稿只从首帧 [initial] 播种一次。
     *
     * 禁止 `remember(initial)`：引导过程中 [onPreview] 会把草稿写回仓库 preview，
     * 根界面收集到的 config 变化会让 [initial] 引用/内容变化；若以此为 key 会重建草稿，
     * 且旧逻辑强制 `enabled=false`，表现为「风险弹窗确认后开关又关、要开两次」。
     */
    var draft by remember {
        val capture = if (initial.captureBackend in OnboardingCaptureBackends) {
            initial.captureBackend
        } else {
            CaptureBackend.AUTO
        }
        mutableStateOf(
            initial.copy(
                automation = initial.automation.copy(enabled = false),
                captureBackend = capture,
            ),
        )
    }
    var showAutomationRisk by remember { mutableStateOf(false) }
    val pageMetas = onboardingPageMetas()
    val motion = LocalHzzsMotion.current
    val dimensions = LocalHzzsDimensions.current
    val onboardingCaptures = remember(captureCapabilities) {
        captureCapabilities.filter { it.backend in OnboardingCaptureBackends }
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        // 与仓库 preview/save 同源校验：enabled 与 disclaimer 必须同帧合法，否则会被洗回关。
        val next = transform(draft).validated()
        draft = next
        onPreview(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_top_title)) },
                actions = {
                    Text(
                        stringResource(R.string.onboarding_step_counter, page + 1, pageMetas.size),
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
        bottomBar = {
            Column {
                LinearProgressIndicator(
                    progress = { (page + 1f) / pageMetas.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensions.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OnboardingStepDots(
                        total = pageMetas.size,
                        current = page,
                        modifier = Modifier.weight(1f),
                    )
                    if (page > 0) {
                        OutlinedButton(onClick = { page-- }) {
                            Text(stringResource(R.string.action_previous))
                        }
                    }
                    Button(
                        onClick = {
                            if (page == pageMetas.lastIndex) {
                                onComplete(
                                    draft.copy(
                                        onboarding = draft.onboarding.copy(
                                            completed = true,
                                            acceptedDisclaimerVersion = AppConfig.DISCLAIMER_VERSION,
                                        ),
                                    ),
                                )
                            } else {
                                page++
                            }
                        },
                    ) {
                        Text(
                            if (page == pageMetas.lastIndex) {
                                stringResource(R.string.action_finish)
                            } else {
                                stringResource(R.string.action_next)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                val forward = targetState >= initialState
                if (forward) {
                    motion.contentStepForwardEnter() togetherWith motion.contentStepForwardExit()
                } else {
                    motion.contentStepBackwardEnter() togetherWith motion.contentStepBackwardExit()
                }
            },
            label = "onboarding-step",
        ) { current ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(dimensions.screenPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    val meta = pageMetas[current]
                    HeroCard(
                        title = meta.title,
                        subtitle = meta.subtitle,
                        icon = meta.icon,
                    ) {
                        Text(
                            stringResource(R.string.onboarding_step_counter, current + 1, pageMetas.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    when (current) {
                        0 -> WelcomePage()
                        1 -> CapturePage(
                            draft = draft,
                            capabilities = onboardingCaptures,
                            onSelect = { backend -> update { it.copy(captureBackend = backend) } },
                        )
                        2 -> PermissionsPage()
                        else -> FinishPage(
                            draft = draft,
                            onTheme = { preset -> update { it.copy(theme = it.theme.copy(preset = preset)) } },
                            onOverlay = { style -> update { it.copy(overlay = it.overlay.copy(style = style)) } },
                            automationEnabled = draft.automation.enabled || showAutomationRisk,
                            riskDialogPending = showAutomationRisk,
                            onAutomationChange = { enabled ->
                                when {
                                    enabled && showAutomationRisk -> Unit
                                    enabled &&
                                        draft.automation.disclaimerAcceptedVersion >=
                                        AppConfig.DISCLAIMER_VERSION -> {
                                        update {
                                            it.copy(
                                                automation = it.automation.copy(
                                                    enabled = true,
                                                    disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
                                                ),
                                            )
                                        }
                                    }
                                    enabled -> showAutomationRisk = true
                                    else -> {
                                        showAutomationRisk = false
                                        update {
                                            it.copy(automation = it.automation.copy(enabled = false))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(dimensions.bottomBarClearance)) }
            }
        }
    }

    if (showAutomationRisk) {
        OnboardingAutomationRiskDialog(
            onDismiss = { showAutomationRisk = false },
            onConfirm = {
                showAutomationRisk = false
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
}

@Composable
private fun OnboardingStepDots(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.onboarding_step_counter, current + 1, total)
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Surface(
                modifier = Modifier.size(if (active) 10.dp else 8.dp),
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ) {}
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Text(
                stringResource(R.string.onboarding_welcome_what_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_welcome_what_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HzzsCallout(
            title = stringResource(R.string.onboarding_welcome_privacy_title),
            text = stringResource(R.string.onboarding_welcome_privacy_body),
            tone = HzzsCalloutTone.INFO,
            icon = Icons.Rounded.Security,
        )
        HzzsCallout(
            title = stringResource(R.string.onboarding_welcome_boundary_title),
            text = stringResource(R.string.onboarding_welcome_boundary_body),
            tone = HzzsCalloutTone.WARNING,
            icon = Icons.Rounded.Info,
        )
        Text(
            stringResource(R.string.onboarding_welcome_accept_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun CapturePage(
    draft: AppConfig,
    capabilities: List<CaptureCapability>,
    onSelect: (CaptureBackend) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Text(
                stringResource(R.string.onboarding_capture_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_capture_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                capabilities.forEach { capability ->
                    SettingsRadioCard(
                        title = capability.title,
                        subtitle = capability.summary,
                        selected = draft.captureBackend == capability.backend,
                        enabled = capability.supported,
                        trailing = when {
                            !capability.supported -> stringResource(R.string.state_unsupported)
                            capability.recommended -> stringResource(R.string.onboarding_capture_recommended)
                            else -> null
                        },
                        onClick = { onSelect(capability.backend) },
                    )
                }
            }
        }
        HzzsCallout(
            text = stringResource(R.string.onboarding_capture_advanced_hint),
            tone = HzzsCalloutTone.INFO,
        )
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember {
        mutableStateOf(SystemCapabilityAccess.canDrawOverlays(context))
    }
    var accessibilityConnected by remember {
        mutableStateOf(SystemCapabilityAccess.isAccessibilityServiceConnected())
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = SystemCapabilityAccess.canDrawOverlays(context)
                accessibilityConnected = SystemCapabilityAccess.isAccessibilityServiceConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HzzsCallout(
            text = stringResource(R.string.onboarding_permissions_desc),
            tone = HzzsCalloutTone.INFO,
            icon = Icons.Rounded.VerifiedUser,
        )
        SectionCard {
            Text(
                stringResource(R.string.onboarding_permissions_overlay_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_permissions_overlay_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (overlayGranted) {
                    stringResource(R.string.permission_overlay_granted)
                } else {
                    stringResource(R.string.permission_overlay_denied)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (overlayGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            OutlinedButton(onClick = { SystemCapabilityAccess.openOverlayPermissionSettings(context) }) {
                Text(stringResource(R.string.permission_overlay_open))
            }
        }
        SectionCard {
            Text(
                stringResource(R.string.onboarding_permissions_a11y_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_permissions_a11y_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (accessibilityConnected) {
                    stringResource(R.string.permission_accessibility_connected)
                } else {
                    stringResource(R.string.permission_accessibility_disconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (accessibilityConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            OutlinedButton(onClick = { SystemCapabilityAccess.openAccessibilitySettings(context) }) {
                Text(stringResource(R.string.permission_accessibility_open))
            }
        }
        HzzsCallout(
            text = stringResource(R.string.onboarding_permissions_capture_note),
            tone = HzzsCalloutTone.INFO,
        )
        Text(
            stringResource(R.string.permission_refresh_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishPage(
    draft: AppConfig,
    onTheme: (ThemePreset) -> Unit,
    onOverlay: (OverlayStyle) -> Unit,
    automationEnabled: Boolean,
    riskDialogPending: Boolean = false,
    onAutomationChange: (Boolean) -> Unit,
) {
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Text(
                stringResource(R.string.onboarding_appearance_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_appearance_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.onboarding_label_theme),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnboardingThemePresets.forEach { preset ->
                    FilterChip(
                        selected = draft.theme.preset == preset,
                        onClick = { onTheme(preset) },
                        label = { Text(preset.displayName()) },
                    )
                }
            }
            Text(
                stringResource(R.string.onboarding_label_overlay),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverlayStyle.entries.forEach { style ->
                    FilterChip(
                        selected = draft.overlay.style == style,
                        onClick = { onOverlay(style) },
                        label = { Text(style.displayName()) },
                    )
                }
            }
        }

        SectionCard {
            Text(
                stringResource(R.string.onboarding_finish_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.onboarding_finish_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryLine(stringResource(R.string.onboarding_finish_capture, draft.captureBackend.displayName()))
            SummaryLine(stringResource(R.string.onboarding_finish_theme, draft.theme.preset.displayName()))
            SummaryLine(stringResource(R.string.onboarding_finish_overlay, draft.overlay.style.displayName()))
            SummaryLine(
                if (automationEnabled) {
                    stringResource(R.string.onboarding_finish_automation_on)
                } else {
                    stringResource(R.string.onboarding_finish_automation_off)
                },
            )
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.onboarding_advanced_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.onboarding_advanced_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Icon(
                        if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (advancedExpanded) {
                            stringResource(R.string.onboarding_advanced_collapse)
                        } else {
                            stringResource(R.string.onboarding_advanced_expand)
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = advancedExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HzzsCallout(
                        text = stringResource(R.string.onboarding_automation_desc),
                        tone = HzzsCalloutTone.WARNING,
                        icon = Icons.Rounded.Security,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.onboarding_automation_allow),
                        subtitle = if (riskDialogPending) {
                            stringResource(R.string.onboarding_automation_pending_risk)
                        } else {
                            stringResource(R.string.onboarding_automation_hint)
                        },
                        checked = automationEnabled,
                        onCheckedChange = onAutomationChange,
                    )
                }
            }
        }

        Text(
            stringResource(R.string.onboarding_finish_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun OnboardingAutomationRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text(stringResource(R.string.onboarding_risk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onboarding_risk_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(stringResource(R.string.onboarding_risk_checkbox))
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = remaining == 0 && accepted) {
                Text(
                    if (remaining > 0) stringResource(R.string.onboarding_risk_wait, remaining)
                    else stringResource(R.string.onboarding_risk_confirm),
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_risk_keep_off))
            }
        },
    )
}

private data class OnboardingPageMeta(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
private fun onboardingPageMetas(): List<OnboardingPageMeta> = listOf(
    OnboardingPageMeta(
        stringResource(R.string.onboarding_page0_title),
        stringResource(R.string.onboarding_page0_subtitle),
        Icons.Rounded.Info,
    ),
    OnboardingPageMeta(
        stringResource(R.string.onboarding_page2_title),
        stringResource(R.string.onboarding_page2_subtitle),
        Icons.Rounded.DesktopWindows,
    ),
    OnboardingPageMeta(
        stringResource(R.string.onboarding_page3_title),
        stringResource(R.string.onboarding_page3_subtitle),
        Icons.Rounded.VerifiedUser,
    ),
    OnboardingPageMeta(
        stringResource(R.string.onboarding_page4_title),
        stringResource(R.string.onboarding_page4_subtitle),
        Icons.Rounded.CheckCircle,
    ),
)
