/**
 * 设置模块 ViewModel：草稿预览 + 显式保存。
 *
 * 职责：订阅/维护当前草稿 [AppConfig]；普通改动经 [update] 写入内存预览（不落盘）；
 * [save] 才 [SettingsRepository.save] 永久保存；[discard] 丢弃预览并恢复已保存快照。
 * 危险项（如开自动操作）由子页对话框确认后再调用 [update]。
 * 应用更新检查/下载/安装为即时任务，与配置字段无关。
 * 边界：不直接 JNI/Root/WindowManager；更新经 [UpdateRepository]。
 * Clean Base：不持有任何算法目录/激活/原生视觉依赖。
 */
package top.azek431.hzzs.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.preferences.validated
import top.azek431.hzzs.core.theme.HzzsThemePackage
import top.azek431.hzzs.core.theme.ThemePackageCodec
import top.azek431.hzzs.core.update.ApkInstaller
import top.azek431.hzzs.core.update.DeltaPatchApplier
import top.azek431.hzzs.core.update.SourceResult
import top.azek431.hzzs.core.update.UpdateFileVerifier
import top.azek431.hzzs.core.update.UpdateRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.platform.compat.CaptureCapabilityResolver
import top.azek431.hzzs.platform.compat.GestureCapabilityResolver
import java.io.File
import javax.inject.Inject

/** 应用更新检查/下载/安装过程的界面态（即时任务，非草稿字段）。 */
data class UpdateUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val available: SourceResult? = null,
    val localApk: File? = null,
)

/**
 * 设置模块配置编辑入口。
 *
 * 子页面共享本 ViewModel；改动经 [update] 乐观更新 UI 并写入进程内 preview。
 * 仅 [save] 落盘；离开未保存时由 UI 弹窗后 [discard] 或 [save]。
 * 导入/MCP 等外部写入通过 [SettingsRepository.config] 回流；本地有未保存草稿时不覆盖。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val capabilityResolver: CaptureCapabilityResolver,
    private val gestureCapabilityResolver: GestureCapabilityResolver,
    private val updateRepository: UpdateRepository,
    private val visionRuntime: VisionRuntimeController,
    private val debugFrames: DebugFrameRecorder,
    mcpUiBridge: McpUiBridge,
) : ViewModel() {
    private val mutableConfig = MutableStateFlow(AppConfig())
    /**
     * 当前设置页展示的配置（已保存快照，或带预览的草稿）。
     * 历史命名 [draft] 保留，避免子页签名大面积改动。
     */
    val draft: StateFlow<AppConfig> = mutableConfig.asStateFlow()

    private val mutableDirty = MutableStateFlow(false)
    /** 相对上次已保存快照是否有未提交预览。 */
    val dirty: StateFlow<Boolean> = mutableDirty.asStateFlow()

    val capabilities = capabilityResolver.all()
    /** 手势后端能力快照；进入页时可再刷新，首屏用构造时快照。 */
    val gestureCapabilities = gestureCapabilityResolver.all()
    private val mutableUpdate = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdate.asStateFlow()
    val mcpState: StateFlow<McpServerState> = mcpUiBridge.serverState
    private val mutableDebugFrameCount = MutableStateFlow(0)
    val debugFrameCount: StateFlow<Int> = mutableDebugFrameCount.asStateFlow()

    /** 最近一次已保存快照（不含预览）。 */
    private var baseline: AppConfig = AppConfig()
    private val editMutex = Mutex()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val snap = repository.snapshot()
            baseline = snap
            mutableConfig.value = snap
            mutableDirty.value = false
            refreshDebugFrameCount()
        }
        viewModelScope.launch {
            repository.config.collectLatest { remote ->
                // 本地有未保存草稿时，不以远端/预览回流覆盖编辑中的 UI。
                if (mutableDirty.value) return@collectLatest
                if (remote != mutableConfig.value) {
                    baseline = remote
                    mutableConfig.value = remote
                }
            }
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

    /** 基于当前配置与运行态生成脱敏诊断文本（不含 Bearer）。 */
    fun buildDiagnosticsReport(): String {
        val config = mutableConfig.value
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
        return DiagnosticsExporter.buildReport(
            versionName = versionName,
            versionCode = versionCode,
            config = config,
            mcp = McpDiagnosticsSnapshot(
                running = mcp.running,
                port = mcp.port.takeIf { mcp.running },
                lastError = mcp.lastError,
            ),
            debugFrameCount = mutableDebugFrameCount.value,
            runtime = visionRuntime.status.value,
            appContext = appContext,
        )
    }

    /**
     * 乐观更新 UI 并写入进程内 preview（不落盘）。
     * 子页危险确认应在调用本方法前完成（如自动操作风险对话框）。
     *
     * 本地草稿与 [SettingsRepository.preview] 均走 [top.azek431.hzzs.core.preferences.validated]，
     * 避免 UI 显示 `enabled=true` 而 preview 因免责版本被洗回 `false`，造成「确认后开关又弹」。
     */
    fun update(transform: (AppConfig) -> AppConfig) {
        val optimistic = transform(mutableConfig.value).validated()
        mutableConfig.value = optimistic
        val isDirty = optimistic != baseline
        mutableDirty.value = isDirty
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            editMutex.withLock {
                if (isDirty) {
                    repository.preview(optimistic)
                } else {
                    repository.clearPreview()
                }
            }
        }
    }

    /**
     * 将当前草稿校验后永久保存，并清空预览。
     * [onDone] 在主协程完成后回调（成功或失败均调用，便于关闭离开对话框）。
     */
    fun save(onDone: (success: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = commitSave()
            onDone(ok)
        }
    }

    /** 丢弃草稿预览，恢复已保存快照。 */
    fun discard(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            commitDiscard()
            onDone()
        }
    }

    private suspend fun commitSave(): Boolean = editMutex.withLock {
        previewJob?.cancel()
        val toWrite = mutableConfig.value
        return runCatching {
            repository.save(toWrite)
            val saved = repository.snapshot()
            baseline = saved
            mutableConfig.value = saved
            mutableDirty.value = false
            AppLog.i(
                "settings",
                "settings saved developer=${saved.developer.enabled} logLevel=${saved.developer.logLevel}",
            )
            true
        }.getOrElse { error ->
            AppLog.w(
                "settings",
                "settings save failed: ${error.message ?: error.javaClass.simpleName}",
            )
            false
        }
    }


    private suspend fun commitDiscard() = editMutex.withLock {
        previewJob?.cancel()
        repository.clearPreview()
        val snap = repository.snapshot()
        baseline = snap
        mutableConfig.value = snap
        mutableDirty.value = false
        AppLog.i("settings", "settings draft discarded")
    }

    /** 将主题包解码后写入主题/悬浮窗（保留当前悬浮窗开关）并进入预览。 */
    fun importTheme(raw: String) {
        val pack = ThemePackageCodec.decode(raw)
        update { it.copy(theme = pack.theme, overlay = pack.overlay.copy(enabled = it.overlay.enabled)) }
    }

    /** 从当前草稿导出声明式主题包 JSON。 */
    fun exportTheme(): String {
        val config = mutableConfig.value
        return ThemePackageCodec.encode(
            HzzsThemePackage(
                name = "HZZS 自定义主题",
                theme = config.theme,
                overlay = config.overlay,
            ),
        )
    }

    /**
     * Composition 卸载时丢弃未保存预览，避免进程内 preview 残留影响其它界面。
     * 正常离开应先经 UI 弹窗 [save]/[discard]。
     */
    fun onLeaveComposition() {
        if (!mutableDirty.value) return
        viewModelScope.launch {
            commitDiscard()
        }
    }

    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val config = mutableConfig.value
            mutableUpdate.value = UpdateUiState(busy = true, message = "正在检查更新…")
            runCatching {
                if (config.update.wifiOnly && !isOnUnmeteredNetwork()) {
                    error("当前设置要求仅在 Wi‑Fi 下检查/下载更新")
                }
                val result = updateRepository.check(
                    beta = config.update.channel == UpdateChannel.BETA,
                    sourcePreference = config.update.sourcePreference,
                )
                val installed = installedVersionCode()
                if (result.manifest.versionCode <= installed) {
                    UpdateUiState(
                        busy = false,
                        message = "已是最新（远端 ${result.manifest.versionName} / ${result.manifest.versionCode}）",
                    )
                } else if (config.update.ignoredVersionCode == result.manifest.versionCode) {
                    UpdateUiState(
                        busy = false,
                        message = "已忽略版本 ${result.manifest.versionName}",
                        available = result,
                    )
                } else {
                    UpdateUiState(
                        busy = false,
                        message = "发现 ${result.manifest.versionName}（${result.source.name}）",
                        available = result,
                    )
                }
            }.onSuccess { mutableUpdate.value = it }
                .onFailure { error ->
                    mutableUpdate.value = UpdateUiState(
                        busy = false,
                        message = "检查失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
        }
    }

    private var updateJob: Job? = null

    fun downloadAvailableUpdate() {
        val available = mutableUpdate.value.available ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val config = mutableConfig.value
            mutableUpdate.value = mutableUpdate.value.copy(busy = true, message = "正在下载更新…")
            runCatching {
                if (config.update.wifiOnly && !isOnUnmeteredNetwork()) {
                    error("当前设置要求仅在 Wi‑Fi 下下载更新")
                }
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val fullApk = File(dir, available.manifest.fullApk.name)
                val patch = available.manifest.patches.firstOrNull {
                    it.fromVersionCode == installedVersionCode()
                }
                if (patch != null) {
                    val patchFile = File(dir, patch.patch.name)
                    updateRepository.download(available.source, available.manifest, patch.patch, patchFile)
                    val oldApk = File(appContext.applicationInfo.sourceDir)
                    DeltaPatchApplier.apply(oldApk, patchFile, fullApk)
                    patchFile.delete()
                } else {
                    updateRepository.download(
                        available.source,
                        available.manifest,
                        available.manifest.fullApk,
                        fullApk,
                    )
                }
                UpdateUiState(
                    busy = false,
                    message = "下载完成，可安装 ${available.manifest.versionName}",
                    available = available,
                    localApk = fullApk,
                )
            }.onSuccess { mutableUpdate.value = it }
                .onFailure { error ->
                    mutableUpdate.value = mutableUpdate.value.copy(
                        busy = false,
                        message = "下载失败：${error.message ?: error.javaClass.simpleName}",
                        localApk = null,
                    )
                }
        }
    }

    fun installDownloadedUpdate() {
        val apk = mutableUpdate.value.localApk ?: return
        val available = mutableUpdate.value.available
        if (available == null) {
            mutableUpdate.value = mutableUpdate.value.copy(message = "缺少已校验的更新清单，请重新检查更新")
            return
        }
        runCatching {
            UpdateFileVerifier.verifyPackage(appContext, apk, available.manifest)
            ApkInstaller.launch(appContext, apk)
        }.onFailure { error ->
            mutableUpdate.value = mutableUpdate.value.copy(
                message = "无法安装：${error.message ?: error.javaClass.simpleName}",
                localApk = null,
            )
            runCatching { apk.delete() }
        }
    }

    /** 将忽略版本号写入草稿预览（需再点保存并应用才落盘）。 */
    fun ignoreAvailableUpdate() {
        val code = mutableUpdate.value.available?.manifest?.versionCode ?: return
        update { it.copy(update = it.update.copy(ignoredVersionCode = code)) }
        mutableUpdate.value = mutableUpdate.value.copy(message = "已忽略该版本（保存后生效）")
    }


    private fun installedVersionCode(): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_META_DATA,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}