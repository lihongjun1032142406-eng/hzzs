/**
 * 关于页与开发者入口。
 *
 * 职责：展示版本 / 免责声明 / 捐赠入口；连续点击版本号 7 次开启 [DeveloperConfig.enabled]。
 * 开启后可进入与设置页相同的 [DeveloperSettingsScreen]（不维护第二套开发者 UI）。
 * 边界：不直接操作 WindowManager / Root / JNI；诊断与调试能力经注入控制器与设置共用组件。
 */
package top.azek431.hzzs.feature.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.algorithm.AlgorithmActivationCoordinator
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.logging.AlgorithmDiagnosticsSnapshot
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.data.vision.NativeBenchmarkRunner
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.feature.settings.screens.AlgorithmPipelineScreen
import top.azek431.hzzs.feature.settings.screens.DeveloperSettingsScreen
import top.azek431.hzzs.feature.settings.screens.LogViewerScreen
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.nativevision.NativeVision
import javax.inject.Inject

enum class DonationKind { WECHAT, ALIPAY, IEF_DIAN }

/** 关于页状态：配置流、调试帧计数、Native 自检；开发者 UI 复用设置模块组件。 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    mcpUiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
    private val benchmarkRunner: NativeBenchmarkRunner,
    private val visionEngine: VisionEngine,
    private val visionRuntime: VisionRuntimeController,
    private val algorithmActivation: AlgorithmActivationCoordinator,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    private val mcpState = mcpUiBridge.serverState
    private val mutableDebugFrameCount = MutableStateFlow(0)
    val debugFrameCount: StateFlow<Int> = mutableDebugFrameCount.asStateFlow()
    private val mutableBenchmark = MutableStateFlow<Result<NativeBenchmarkResult>?>(null)
    val benchmark: StateFlow<Result<NativeBenchmarkResult>?> = mutableBenchmark.asStateFlow()

    init {
        refreshDebugFrameCount()
    }

    fun setDeveloperEnabled(enabled: Boolean) {
        update { it.copy(developer = it.developer.copy(enabled = enabled)) }
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch {
            val current = repository.snapshot()
            repository.save(transform(current))
        }
    }

    fun refreshDebugFrameCount() {
        viewModelScope.launch { mutableDebugFrameCount.value = debugFrames.list().size }
    }

    fun clearDebugFrames() {
        viewModelScope.launch {
            debugFrames.clear()
            mutableDebugFrameCount.value = 0
        }
    }

    fun runNativeBenchmark() {
        val iterations = config.value.developer.nativeBenchmarkIterations
        viewModelScope.launch { mutableBenchmark.value = benchmarkRunner.run(iterations) }
    }

    /** 脱敏诊断摘要：版本 / 机型 / 配置 / 算法激活 / 运行态 / 最近日志；不含 Bearer。 */
    fun buildDiagnosticsReport(): String {
        val current = config.value
        val mcp = mcpState.value
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 0L
        }
        val activation = runCatching { visionEngine.currentActivation() }.getOrNull()
        return DiagnosticsExporter.buildReport(
            versionName = versionName,
            versionCode = versionCode,
            config = current,
            mcp = McpDiagnosticsSnapshot(
                running = mcp.running,
                port = mcp.port.takeIf { mcp.running },
                lastError = mcp.lastError,
            ),
            debugFrameCount = mutableDebugFrameCount.value,
            algorithm = activation?.let {
                AlgorithmDiagnosticsSnapshot(
                    algorithmId = it.profile.algorithmId,
                    version = it.profile.version,
                    generation = it.generation,
                    usingBuiltinFallback = it.usingBuiltinFallback,
                    loadError = it.loadError,
                    nativeAvailable = NativeVision.isAvailable,
                    pendingCatalogId = algorithmActivation.pendingCatalogId(),
                    analysisRunning = algorithmActivation.isAnalysisRunning(),
                )
            },
            runtime = visionRuntime.status.value,
            appContext = appContext,
        )
    }
}

/**
 * 关于主界面。
 * 版本号连点 7 次开启开发者选项；开启后入口进入与设置共用的 [DeveloperSettingsScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onSaveQr: (DonationKind) -> Unit,
    vm: AboutViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val debugFrameCount by vm.debugFrameCount.collectAsState()
    val benchmark by vm.benchmark.collectAsState()
    var donation by remember { mutableStateOf<DonationKind?>(null) }
    var developerPage by remember { mutableStateOf(false) }
    var logViewerPage by remember { mutableStateOf(false) }
    var algorithmPipelinePage by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var unlockMessage by remember { mutableStateOf<String?>(null) }
    val toastContext = LocalContext.current
    val resources = LocalResources.current
    val unlockDoneMsg = stringResource(R.string.about_unlock_done)

    LaunchedEffect(config.developer.enabled, developerPage) {
        if (developerPage && !config.developer.enabled) {
            developerPage = false
        }
    }

    if (logViewerPage && config.developer.enabled) {
        LogViewerScreen(
            onBack = { logViewerPage = false },
            onMessage = { msg ->
                Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
            },
        )
        return
    }

    if (algorithmPipelinePage && config.developer.enabled) {
        AlgorithmPipelineScreen(
            onBack = { algorithmPipelinePage = false },
            onMessage = { msg ->
                Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
            },
            onOpenLogs = {
                algorithmPipelinePage = false
                logViewerPage = true
            },
        )
        return
    }

    if (developerPage && config.developer.enabled) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_cat_developer_title)) },
                    navigationIcon = {
                        IconButton(onClick = { developerPage = false }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            DeveloperSettingsScreen(
                developerEnabled = config.developer.enabled,
                config = config,
                update = vm::update,
                debugFrameCount = debugFrameCount,
                benchmark = benchmark,
                onRefreshDebugFrames = vm::refreshDebugFrameCount,
                onClearDebugFrames = vm::clearDebugFrames,
                onRunBenchmark = vm::runNativeBenchmark,
                onBuildDiagnostics = vm::buildDiagnosticsReport,
                onOpenLogViewer = { logViewerPage = true },
                onOpenAlgorithmPipeline = { algorithmPipelinePage = true },
                onMessage = { msg ->
                    Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val dimensions = LocalHzzsDimensions.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(dimensions.cardPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("HZZS", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.about_tagline),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        AssistChip(
                            onClick = {
                                if (config.developer.enabled) {
                                    unlockMessage = unlockDoneMsg
                                    return@AssistChip
                                }
                                versionTapCount++
                                val remaining = 7 - versionTapCount
                                when {
                                    remaining > 0 && remaining <= 3 -> {
                                        unlockMessage = resources.getString(
                                            R.string.about_unlock_remaining,
                                            remaining,
                                        )
                                    }
                                    remaining <= 0 -> {
                                        versionTapCount = 0
                                        vm.setDeveloperEnabled(true)
                                        unlockMessage = unlockDoneMsg
                                    }
                                }
                            },
                            label = {
                                Text(stringResource(R.string.about_version_chip, versionName))
                            },
                            leadingIcon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                        )
                        unlockMessage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (config.developer.enabled) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.about_developer_entry)) },
                        supportingContent = {
                            Text(stringResource(R.string.about_developer_entry_sub))
                        },
                        leadingContent = { Icon(Icons.Rounded.DeveloperMode, contentDescription = null) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable { developerPage = true },
                    )
                }
            }
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.about_project_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.about_project_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HzzsCallout(
                    title = stringResource(R.string.about_disclaimer_title),
                    text = stringResource(R.string.about_disclaimer_body),
                    tone = HzzsCalloutTone.WARNING,
                )
            }
            item {
                Text(
                    stringResource(R.string.about_support_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { donation = DonationKind.WECHAT }) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.about_wechat))
                    }
                    FilledTonalButton(onClick = { donation = DonationKind.ALIPAY }) {
                        Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.about_alipay))
                    }
                    FilledTonalButton(onClick = { donation = DonationKind.IEF_DIAN }) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.about_donation_prompt_iefdian))
                    }
                }
            }
            item {
                AboutRow(
                    Icons.Rounded.Code,
                    stringResource(R.string.about_github),
                    stringResource(R.string.about_github_sub),
                    "https://github.com/Azek431/hzzs",
                )
            }
            item {
                AboutRow(
                    Icons.AutoMirrored.Rounded.Article,
                    stringResource(R.string.about_license),
                    stringResource(R.string.about_license_sub),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    donation?.let { kind ->
        DonationDialog(
            kind = kind,
            onDismiss = { donation = null },
            onSave = {
                when (kind) {
                    DonationKind.WECHAT, DonationKind.ALIPAY -> onSaveQr(kind)
                    DonationKind.IEF_DIAN -> {
                        donation = null
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.ifdian.net/a/Azek431"),
                        )
                        if (intent.resolveActivity(toastContext.packageManager) != null) {
                            toastContext.startActivity(intent)
                        } else {
                            Toast.makeText(
                                toastContext,
                                R.string.about_open_link_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DonationDialog(kind: DonationKind, onDismiss: () -> Unit, onSave: () -> Unit) {
    val isIEFDian = kind == DonationKind.IEF_DIAN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kind) {
                    DonationKind.WECHAT -> stringResource(R.string.about_donation_wechat)
                    DonationKind.ALIPAY -> stringResource(R.string.about_donation_alipay)
                    DonationKind.IEF_DIAN -> stringResource(R.string.about_donation_prompt_iefdian)
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isIEFDian) {
                    val id = if (kind == DonationKind.WECHAT) {
                        R.drawable.donation_wechat
                    } else {
                        R.drawable.donation_alipay
                    }
                    Image(
                        bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id),
                        contentDescription = stringResource(R.string.about_donation_qr_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .sizeIn(maxWidth = 340.dp, maxHeight = 460.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { onSave() })
                            },
                    )
                    Text(
                        stringResource(R.string.about_donation_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.about_support_iefdian_desc),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "https://www.ifdian.net/a/Azek431",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (isIEFDian) {
                Button(onClick = onSave) {
                    Text(stringResource(R.string.action_open_link))
                }
            } else {
                Button(onClick = onSave) {
                    Text(stringResource(R.string.action_save_to_gallery))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    url: String? = null,
) {
    val context = LocalContext.current
    val openLinkFailedMessage = stringResource(R.string.about_open_link_failed)
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            if (url != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        },
        modifier = Modifier.clickable(enabled = url != null) {
            url?.let { target ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            openLinkFailedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        },
    )
}
