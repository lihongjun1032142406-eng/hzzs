package top.azek431.hzzs.data.vision

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.azek431.hzzs.R
import top.azek431.hzzs.core.algorithm.AlgorithmActivationCoordinator
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.algorithm.AlgorithmDetectionTrace
import top.azek431.hzzs.core.algorithm.AlgorithmFrameTraceEntry
import top.azek431.hzzs.core.algorithm.AlgorithmRuntimeTrace
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.domain.automation.ActionCommitLedger
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.GestureArbiter
import top.azek431.hzzs.domain.automation.GestureSpec
import top.azek431.hzzs.domain.automation.TriggerDistanceAutoTuner
import top.azek431.hzzs.domain.vision.Avoidance
import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.FrameMeta
import top.azek431.hzzs.domain.vision.NormalizedRect
import top.azek431.hzzs.domain.vision.ObjectKind
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.domain.vision.VisionFrame
import top.azek431.hzzs.domain.vision.VisionResult
import top.azek431.hzzs.domain.vision.withApproximateDisplayContour
import top.azek431.hzzs.platform.compat.CaptureBackendResolution
import top.azek431.hzzs.platform.compat.GestureCapabilityResolver
import top.azek431.hzzs.platform.compat.ShizukuHealthCheck
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend
import top.azek431.hzzs.service.automation.AccessibilityForegroundProbe
import top.azek431.hzzs.service.automation.GestureDispatcherFactory
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.capture.CaptureState
import top.azek431.hzzs.service.capture.FrameSource
import top.azek431.hzzs.service.capture.FrameSourceFactory
import top.azek431.hzzs.service.overlay.OverlayController
import top.azek431.hzzs.service.vision.VisionAnalysisForegroundService
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 视觉运行时控制器：帧循环的唯一所有者。
 *
 * 职责：
 * - 串行编排「截图 → Native 分析 → 跨帧追踪 → 悬浮窗 → 可选自动动作」；
 * - 持有 [MultiObjectTracker]、动作账本与手势仲裁器，避免多入口并发 reset/analyze；
 * - 通过 [generation] 令牌丢弃已停止会话的陈旧帧与动作结果。
 *
 * 线程与所有权：
 * - 生命周期（start/stop/restart）在 [lifecycleMutex] 下串行；
 * - 帧循环运行于 [scope]（Default）；动作在独立 [actionJob] 中执行，与分析解耦；
 * - [CapturedFrame] 仅在 `frame.use { }` 内借用，循环不跨帧持有像素缓冲。
 *
 * 安全不变量：
 * - 自动操作默认关闭；启用后仍受免责声明版本门控。
 * - 场景或算法 generation 变化时必须进入安全点：取消动作、清空 tracker 与去重缓存。
 * - 设置收集器只替换不可变配置快照，不直接操作引擎。
 *
 * 坐标：视觉结果与手势规划使用视口归一化坐标 `[0,1]`。
 */
@Singleton
class VisionRuntimeController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val sources: FrameSourceFactory,
    private val engine: VisionEngine,
    private val overlay: OverlayController,
    private val debugFrameRecorder: DebugFrameRecorder,
    private val algorithmActivation: AlgorithmActivationCoordinator,
    private val algorithmCatalog: AlgorithmCatalogController,
    private val gestureDispatchers: GestureDispatcherFactory,
    private val gestureCapabilities: GestureCapabilityResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    /** 会话代数：stop/start 递增，用于 fail-closed 丢弃陈旧帧与动作。 */
    private val generation = AtomicLong(0)
    private val latestConfig = AtomicReference(AppConfig())
    private val tracker = MultiObjectTracker()
    private val ledger = ActionCommitLedger()
    /**
     * 手势串行闸门。具体 [top.azek431.hzzs.domain.automation.GestureDispatcher]
     * 按动作上挂的 [latestGestureBackend] 在 dispatch 时再解析，避免绑死无障碍。
     */
    private val latestGestureBackend = AtomicReference(GestureBackend.ACCESSIBILITY)
    private val arbiter = GestureArbiter(
        clock = SystemClock::uptimeMillis,
        dispatcher = { action ->
            gestureDispatchers.dispatcher(latestGestureBackend.get()).dispatch(action)
        },
    )
    private val mutableStatus = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val mutableLatestResult = MutableStateFlow<VisionResult?>(null)
    val latestResult: StateFlow<VisionResult?> = mutableLatestResult.asStateFlow()

    @Volatile
    private var runtimeJob: Job? = null

    /** 与帧循环解耦的动作协程；场景/算法切换或 stop 时必须取消。 */
    @Volatile
    private var actionJob: Job? = null

    @Volatile
    private var activeSource: FrameSource? = null

    private val actionIds = AtomicLong(1)
    private val recentActionTimes = ArrayDeque<Long>()
    private val detectedPlayerReference = AtomicReference<Detection?>(null)
    /** 自动复活连点冷却（elapsedRealtime）。 */
    private val lastAutoReviveAtMs = AtomicLong(0L)
    private val actionMutex = Mutex()
    private val actionInFlight = AtomicBoolean(false)
    private var lastOverlaySignature: Int = Int.MIN_VALUE
    private val triggerDistanceTuner = TriggerDistanceAutoTuner()

    // Shizuku 持续监控相关字段
    private var shizukuHealthCheckJob: Job? = null
    private var shizukuLastHealthState: Boolean? = null
    private var shizukuSessionSelected = false
    init {
        scope.launch {
            // 主题/悬浮窗可跟 preview；自动操作与截图后端只跟已落盘 saved，
            // 避免设置草稿未「保存并应用」就派发手势或换源。
            combine(
                settingsRepository.savedConfig,
                settingsRepository.config,
            ) { saved, previewOrSaved ->
                previewOrSaved.withSavedSafetyGates(saved)
            }.collect { next ->
                val previous = latestConfig.getAndSet(next)
                triggerDistanceTuner.onBaselineChanged(
                    next.selectedScene,
                    baselineTriggerMultiplier(next, next.selectedScene),
                )
                if (!next.automation.autoAdjustTriggerDistance) {
                    triggerDistanceTuner.clear()
                }
                val previousBackend = previous.effectiveCaptureBackend()
                val nextBackend = next.effectiveCaptureBackend()
                val gestureBackendChanged =
                    previous.automation.gestureBackend != next.automation.gestureBackend
                val safetyBoundaryChanged =
                    previous.selectedScene != next.selectedScene ||
                        previousBackend != nextBackend ||
                        previous.automation.allowedPackages != next.automation.allowedPackages ||
                        previous.automation.restrictPackages != next.automation.restrictPackages ||
                        previous.automation.enabled != next.automation.enabled ||
                        previous.automation.disclaimerAcceptedVersion !=
                        next.automation.disclaimerAcceptedVersion ||
                        gestureBackendChanged
                // 安全边界变化：取消在飞动作，防止旧会话权限延续。
                if (safetyBoundaryChanged) {
                    cancelActions()
                    if (gestureBackendChanged) {
                        gestureDispatchers.clearShellCaches()
                    }
                }
                mutableStatus.update {
                    it.copy(
                        activeScene = next.selectedScene,
                        activeGestureBackend = resolveGestureBackend(next).effective,
                    )
                }
                if (
                    previousBackend != nextBackend &&
                    mutableStatus.value.running
                ) {
                    // 截图后端仅随 saved 变化；能走到这里说明已落盘。
                    launch { restart() }
                }
            }
        }
    }

    /**
     * 启动帧循环。
     *
     * 输入：当前设置中的截图后端与场景；输出：更新 [status]/[latestResult]。
     * 在 [lifecycleMutex] 内推进 [generation] 并 [resetPipeline]，保证单会话独占引擎与 tracker。
     */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (runtimeJob?.isActive == true) return@withLock

            val saved = settingsRepository.snapshot()
            val previewOrSaved = settingsRepository.current()
            val config = previewOrSaved.withSavedSafetyGates(saved).also(latestConfig::set)
            val resolution = config.resolveCaptureBackend()
            val backend = resolution.effective
            val source = sources.source(backend)
            val token = generation.incrementAndGet()
            if (resolution.fellBack) {
                AppLog.w(
                    "vision",
                    "capture backend fallback requested=${resolution.requested.name} " +
                        "effective=${backend.name} reason=${resolution.fallbackReason}",
                )
            }
            val gestureResolution = resolveGestureBackend(config)

            // 新增：Shizuku 健康检查与自动降级
            var finalGestureBackend = gestureResolution.effective
            var healthCheckReason: String? = null

            // 新会话重新建立健康基线，避免上一会话状态抑制本次降级。
            shizukuLastHealthState = null
            shizukuSessionSelected = finalGestureBackend == GestureBackend.SHIZUKU
            if (shizukuSessionSelected) {
                // 异步检查 Shizuku 健康状况，如果问题严重则降级
                // 这里使用 withContext(Dispatchers.IO) 避免阻塞主线程
                val health = withContext(Dispatchers.IO) {
                    runCatching { ShizukuHealthCheck.check() }.getOrNull()
                }

                if (health != null && !health.isHealthy) {
                    healthCheckReason = health.reason
                    AppLog.w("vision", "Shizuku health check failed: ${health.reason ?: "unknown"}")

                    // 如果 Accessibility 可用，自动降级
                    if (HzzsAccessibilityService.isConnected()) {
                        finalGestureBackend = GestureBackend.ACCESSIBILITY
                        healthCheckReason = "Shizuku 不可用，已自动降级至 ACCESSIBILITY"
                        AppLog.i("vision", "Shizuku fallback to ACCESSIBILITY due to health check")
                    } else {
                        AppLog.w("vision", "No viable fallback for SHIZUKU; issues may manifest later")
                    }
                }
            }

            latestGestureBackend.set(finalGestureBackend)
            val gestureFallbackReason = healthCheckReason ?: gestureResolution.fallbackReason

            AppLog.i(
                "vision",
                "start session gen=$token backend=${backend.name} " +
                    "requested=${resolution.requested.name} scene=${config.selectedScene.name} " +
                    "overlay=${config.overlay.enabled} automation=${config.automation.enabled} " +
                    "gesture=${finalGestureBackend.name}" +
                    (healthCheckReason?.let { " healthNote=$it" } ?: "") +
                    (gestureFallbackReason?.let { " gestureNote=$it" } ?: ""),
            )
            runCatching {
                top.azek431.hzzs.mcp.McpEventBus.append(
                    top.azek431.hzzs.mcp.McpEventBus.Type.ANALYSIS_START,
                    JSONObject()
                        .put("backend", backend.name)
                        .put("scene", config.selectedScene.name)
                        .put("automation", config.automation.enabled),
                )
            }
            resetPipeline()
            AlgorithmRuntimeTrace.resetSession()
            // 启动分析前按已保存 AlgorithmConfig 解析并激活（含 pending）；失败回退内置。
            runCatching {
                algorithmActivation.ensureConfigured(
                    config = config.algorithm,
                    selectedScene = config.selectedScene,
                )
            }.onFailure { error ->
                AppLog.w(
                    "vision",
                    "algorithm ensureConfigured failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            val activation = runCatching { engine.currentActivation() }.getOrNull()
            if (activation != null) {
                AppLog.i(
                    "vision",
                    "active algorithm id=${activation.profile.algorithmId} ver=${activation.profile.version} " +
                        "gen=${activation.generation} fallback=${activation.usingBuiltinFallback}",
                )
            }
            // 同步目录控制器，避免分析中下载/选用半热激活
            algorithmCatalog.setAnalysisRunning(true)
            activeSource = source
            mutableStatus.value = RuntimeStatus(
                running = true,
                activeScene = config.selectedScene,
                activeBackend = backend,
                activeGestureBackend = finalGestureBackend,
            )

            try {
                source.start()
                // 启动 Shizuku 健康监控（仅在自动化开启时有效，由 check 函数内部判断）
                startShizukuHealthMonitoring()
                runtimeJob = scope.launch {
                    runLoop(
                        token = token,
                        source = source,
                        startedBackend = backend,
                    )
                }
                // 分析启停绑定前台服务，降低 OEM 后台杀进程概率；仅 alive 期间提优先级。
                VisionAnalysisForegroundService.start(appContext)
            } catch (error: Throwable) {
                activeSource = null
                runCatching { source.stop() }
                algorithmCatalog.setAnalysisRunning(false)
                AppLog.e("vision", "start capture failed: ${error.message}", error)
                mutableStatus.value = RuntimeStatus(
                    running = false,
                    activeScene = config.selectedScene,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    /** 停止后重新启动；用于截图后端等需要换源的配置变更。 */
    suspend fun restart() {
        stop()
        start()
    }

    /**
     * 停止帧循环并释放截图源。
     *
     * 先递增 [generation] 使进行中的循环/动作 fail-closed，再 cancelAndJoin、隐藏悬浮窗并重置管线。
     */
    suspend fun stop() = lifecycleMutex.withLock {
        val prevGen = generation.get()
        generation.incrementAndGet()
        AppLog.i("vision", "stop session prevGen=$prevGen")
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        val source = activeSource
        activeSource = null
        runCatching { source?.stop() }
        overlay.hide()
        resetPipeline()
        runCatching {
            top.azek431.hzzs.mcp.McpEventBus.append(
                top.azek431.hzzs.mcp.McpEventBus.Type.ANALYSIS_STOP,
                JSONObject().put("prevGen", prevGen),
            )
        }
        algorithmCatalog.setAnalysisRunning(false)
        VisionAnalysisForegroundService.stop(appContext)
        stopShizukuHealthMonitoring() // 新增：停止健康监控
        mutableStatus.value = mutableStatus.value.copy(
            running = false,
            captureReady = false,
            overlayVisible = false,
            overlayBlockReason = null,
            fps = 0f,
        )
    }

    /** 启动持续健康监控；仅本会话原始手势路径选择 Shizuku 时运行。 */
    private fun startShizukuHealthMonitoring() {
        if (!shizukuSessionSelected) return
        shizukuHealthCheckJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                checkShizukuHealthAndMaybeDowngrade()
                delay(SHIZUKU_HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    /** 停止健康监控并清空跨会话状态。 */
    private suspend fun stopShizukuHealthMonitoring() {
        shizukuHealthCheckJob?.cancelAndJoin()
        shizukuHealthCheckJob = null
        shizukuLastHealthState = null
        shizukuSessionSelected = false
        cancelShizukuNotification()
    }

    /**
     * 检查 Shizuku 健康状态，如果不可用且当前使用 SHIZUKU 则降级到 ACCESSIBILITY。
     */
    private suspend fun checkShizukuHealthAndMaybeDowngrade() {
        val currentConfig = latestConfig.get()
        if (!currentConfig.automation.enabled || !shizukuSessionSelected) return

        val health = runCatching { ShizukuHealthCheck.check() }.getOrNull() ?: return

        val isNowHealthy = health.isHealthy
        val wasHealthy = shizukuLastHealthState

        shizukuLastHealthState = isNowHealthy

        if (!isNowHealthy) {
            val reason = health.reason ?: "unknown"
            if (wasHealthy == null || wasHealthy) {
                AppLog.w("vision", "Shizuku became unhealthy during runtime: $reason")
            }

            if (
                latestGestureBackend.get() == GestureBackend.SHIZUKU &&
                HzzsAccessibilityService.isConnected()
            ) {
                val oldBackend = latestGestureBackend.getAndSet(GestureBackend.ACCESSIBILITY)
                mutableStatus.update {
                    it.copy(
                        activeGestureBackend = GestureBackend.ACCESSIBILITY,
                        lastError = "Shizuku 不可用，已自动降级至 ACCESSIBILITY",
                    )
                }
                AppLog.i("vision", "Shizuku runtime fallback to ACCESSIBILITY, was $oldBackend")
                showShizukuIssueNotification("Shizuku 运行时失效，已降级至 ACCESSIBILITY", reason)
            } else if (wasHealthy == null || wasHealthy) {
                showShizukuIssueNotification("Shizuku 不可用，无有效降级方案", reason)
            }
        } else if (wasHealthy != true) {
            AppLog.i("vision", "Shizuku health restored during runtime")
            // 恢复后不自动切回 Shizuku，避免运行中半热切换手势后端。
            cancelShizukuNotification()
        }
    }

    /**
     * 显示 Shizuku 问题通知。
     */
    private fun showShizukuIssueNotification(title: String, detail: String?) {
        val notificationManager = NotificationManagerCompat.from(appContext)

        // 创建通知渠道（API 26+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SHIZUKU_NOTIFICATION_CHANNEL_ID,
                "Shizuku 问题通知",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "当 Shizuku 后端出现异常时显示"
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(appContext, SHIZUKU_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_flame)
            .setContentTitle(title)
            .setContentText(detail ?: "Shizuku 服务出现异常")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)

        try {
            notificationManager.notify(SHIZUKU_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Android 13+ 通知权限可能被用户拒绝；该诊断通知为 best-effort。
        }
    }

    /**
     * 取消 Shizuku 问题通知。
     */
    private fun cancelShizukuNotification() {
        val notificationManager = NotificationManagerCompat.from(appContext)
        notificationManager.cancel(SHIZUKU_NOTIFICATION_ID)
    }

    /** 取消在飞动作与动作协程（fail-closed），不清除帧循环状态。 */
    private fun cancelActions() {
        // 只 cancel job；actionInFlight 由 actionJob 的 finally 清掉。
        // 若此处提前 set(false)，旧 job 仍可能在 arbiter 里注入，下一帧会立刻 plan 叠点。
        actionJob?.cancel()
        actionJob = null
    }

    /**
     * 对外入口：取消在飞自动操作（MCP `cancel_actions`、切换手势后端等）。
     * 与内部 [cancelActions] 同义；不停止帧循环。
     */
    fun cancelPendingActions() {
        cancelActions()
    }

    /**
     * 帧循环主体：在 [token] 与 [generation] 一致期间持续取帧分析。
     *
     * 关键分支：
     * - 截图后端与启动时不一致 → 抛错要求重启；
     * - 场景禁用 → 藏悬浮窗并退避；
     * - 场景或算法 generation 变化 → 安全点：停 [actionJob]、清 tracker/ledger/去重；
     * - 帧在 [frame.use] 内处理完即释放，分析前后再次校验 [generation]。
     */
    private suspend fun runLoop(
        token: Long,
        source: FrameSource,
        startedBackend: CaptureBackend,
    ) {
        var lastSequence = -1L
        var failureCount = 0
        var frameCount = 0
        var fpsWindowStart = SystemClock.elapsedRealtime()
        // Tracker 稳定帧按已分析帧计数，避免 conflated/排空帧让序号跳跃。
        var trackingSequence = -1L
        var pipelineScene: SceneId? = null
        var pipelineSceneConfig: SceneConfig? = null
        var pipelineAlgorithmGeneration = engine.activeAlgorithmGeneration()

        val stateJob = CoroutineScope(currentCoroutineContext()).launch {
            source.state.collectLatest { state ->
                when (state) {
                    CaptureState.Ready -> mutableStatus.update {
                        it.copy(captureReady = true, lastError = null)
                    }
                    CaptureState.Idle,
                    CaptureState.RequestingPermission -> mutableStatus.update { it.copy(captureReady = false) }
                    is CaptureState.Failed -> mutableStatus.update {
                        it.copy(captureReady = false, lastError = state.message)
                    }
                }
            }
        }

        try {
            while (currentCoroutineContext().isActive && generation.get() == token) {
                currentCoroutineContext().ensureActive()
                val config = latestConfig.get()
                if (config.effectiveCaptureBackend() != startedBackend) {
                    throw RuntimeRestartRequired("截图方式已更改，请重新启动视觉分析")
                }

                val sceneConfig = config.scenes[config.selectedScene]
                    ?: throw IllegalStateException("缺少场景配置：${config.selectedScene}")
                if (!sceneConfig.enabled) {
                    cancelActions()
                    overlay.hide()
                    mutableStatus.update {
                        it.copy(
                            overlayVisible = false,
                            overlayBlockReason = OverlayBlockReason.DISABLED,
                            activeScene = config.selectedScene,
                            lastError = "当前主题场景已禁用",
                        )
                    }
                    delay(DISABLED_SCENE_BACKOFF_MS)
                    continue
                }

                val algorithmGeneration = engine.activeAlgorithmGeneration()
                val sceneChanged =
                    pipelineScene != config.selectedScene || pipelineSceneConfig != sceneConfig
                val algorithmChanged = pipelineAlgorithmGeneration != algorithmGeneration
                if (sceneChanged || algorithmChanged) {
                    // 场景或算法切换必须进入安全点：停动作、清 tracker / 去重缓存。
                    // 不允许分析过程中半热切换；algorithm 配置应在帧循环外完成。
                    if (algorithmChanged) {
                        val activation = runCatching { engine.currentActivation() }.getOrNull()
                        AppLog.i(
                            "algorithm",
                            "pipeline safety-point gen $pipelineAlgorithmGeneration→$algorithmGeneration " +
                                "id=${activation?.profile?.algorithmId ?: "?"} " +
                                "ver=${activation?.profile?.version ?: "?"} " +
                                "fallback=${activation?.usingBuiltinFallback}",
                        )
                    }
                    if (sceneChanged) {
                        AppLog.i(
                            "vision",
                            "pipeline scene safety-point ${pipelineScene?.name ?: "-"}→${config.selectedScene.name}",
                        )
                    }
                    actionJob?.cancelAndJoin()
                    actionJob = null
                    actionInFlight.set(false)
                    tracker.reset()
                    trackingSequence = -1L
                    ledger.reset()
                    recentActionTimes.clear()
                    detectedPlayerReference.set(null)
                    lastOverlaySignature = Int.MIN_VALUE
                    pipelineScene = config.selectedScene
                    pipelineSceneConfig = sceneConfig
                    pipelineAlgorithmGeneration = algorithmGeneration
                    failureCount = 0
                    mutableStatus.update {
                        it.copy(activeScene = config.selectedScene, lastError = null)
                    }
                }

                // HZZS_V092_COMPLETION_DRIVEN_CAPTURE
                // 无固定 FPS sleep：上一轮完成后直接等待最新新帧。
                // HUD 已显示时先临时隐身并等待一次显示提交；MediaProjection/AUTO
                // 再排空一张可能含旧合成层的帧，随后取得干净输入缓冲。
                val overlaySuspended =
                    mutableStatus.value.overlayVisible &&
                        config.overlay.enabled &&
                        overlay.suspendForCapture()
                val frame = try {
                    val continuousProjection =
                        startedBackend == CaptureBackend.AUTO ||
                            startedBackend == CaptureBackend.MEDIA_PROJECTION
                    if (overlaySuspended && continuousProjection) {
                        val drained = source.nextFrame(lastSequence)
                        if (drained == null) {
                            null
                        } else {
                            drained.use {
                                if (drained.sequence > lastSequence) lastSequence = drained.sequence
                            }
                            source.nextFrame(lastSequence)
                        }
                    } else {
                        source.nextFrame(lastSequence)
                    }
                } finally {
                    if (overlaySuspended && generation.get() == token) {
                        overlay.resumeAfterCapture()
                    }
                }
                if (frame == null) {
                    when (val state = source.state.value) {
                        is CaptureState.Failed -> throw CaptureUnavailable(state.message)
                        CaptureState.RequestingPermission -> delay(PERMISSION_BACKOFF_MS)
                        CaptureState.Ready -> delay(READY_NULL_FRAME_BACKOFF_MS)
                        CaptureState.Idle -> delay(IDLE_BACKOFF_MS)
                    }
                    continue
                }

                // 帧所有权：仅在 use 块内借用像素；退出后必须已 close，禁止跨帧缓存。
                frame.use {
                    if (frame.sequence <= lastSequence || generation.get() != token) return@use
                    lastSequence = frame.sequence
                    debugFrameRecorder.offer(frame, config.developer)
                    val diagnostics = top.azek431.hzzs.domain.vision.VisionDiagnostics(
                        enableStageTiming = config.developer.enabled && config.developer.enableStageTiming,
                        enableMulticolorDiagnostic =
                            config.developer.enabled && config.developer.enableMulticolorDiagnostic,
                        enableFilterTrace = config.developer.enabled && config.developer.enableFilterTrace,
                    )
                    val result = engine.analyze(
                        VisionFrame(
                            FrameMeta(
                                sequence = frame.sequence,
                                timestampNanos = frame.elapsedRealtimeNanos,
                                sourceWidth = frame.width,
                                sourceHeight = frame.height,
                            ),
                            frame.pixels,
                        ),
                        sceneConfig,
                        config.viewport,
                        diagnostics,
                    )
                    // 分析可能耗时；返回后若会话已停，丢弃结果避免污染新会话。
                    if (generation.get() != token) return@use

                    if (result.error != null) {
                        failureCount++
                        cancelActions()
                        val showGrid = config.developer.enabled && config.developer.showCoordinateGrid
                        val overlayState = publishOverlay(
                            config.overlay, result, showGrid, force = true,
                            runtimeStatus = mutableStatus.value,
                        )
                        mutableStatus.update {
                            it.copy(
                                overlayVisible = overlayState.visible,
                                overlayBlockReason = overlayState.blockReason,
                                lastError = result.error,
                            )
                        }
                        recordFrameTrace(
                            analysisSequence = AlgorithmRuntimeTrace.nextAnalysisSequence(),
                            result = result,
                            tracked = emptyList(),
                            decision = "error",
                        )
                        if (failureCount >= MAX_CONSECUTIVE_VISION_FAILURES) {
                            throw VisionUnavailable("视觉分析连续失败 $failureCount 次：${result.error}")
                        }
                        return@use
                    }
                    failureCount = 0

                    val resultWithReference = result.withPlayerReference(sceneConfig)
                    // Tracker 稳定帧按已分析帧计数，避免 conflated/排空帧让序号跳跃。
                    trackingSequence++
                    val tracked = tracker.update(trackingSequence, resultWithReference.detections)
                    // 算法只产出 Detection；近似轮廓是 App 呈现增强，供 Overlay 绘制，不回写规划几何。
                    val trackedResult = resultWithReference.copy(
                        detections = tracked.map { it.detection.withApproximateDisplayContour() },
                    )
                    mutableLatestResult.value = trackedResult
                    val showGrid = config.developer.enabled && config.developer.showCoordinateGrid
                    val overlayState = publishOverlay(
                        config.overlay, trackedResult, showGrid, force = false,
                        runtimeStatus = mutableStatus.value,
                    )
                    mutableStatus.update {
                        it.copy(
                            overlayVisible = overlayState.visible,
                            overlayBlockReason = overlayState.blockReason,
                            lastError = null,
                            processingMs = trackedResult.processingNanos / 1_000_000f,
                            obstacleCount = trackedResult.detections.size,
                            activeBackend = startedBackend,
                        )
                    }
                    val decision = maybeDispatch(
                        token = token,
                        config = config,
                        result = trackedResult,
                        tracked = tracked,
                        frameTimestampNanos = frame.elapsedRealtimeNanos,
                    )
                    val reviveDecision = maybeAutoRevive(token = token, config = config)
                    val combinedDecision = when {
                        reviveDecision != null && decision.startsWith("plan ") ->
                            "$decision | $reviveDecision"
                        reviveDecision != null &&
                            (decision.startsWith("skip:") || decision.startsWith("error")) ->
                            "$reviveDecision | $decision"
                        reviveDecision != null -> reviveDecision
                        else -> decision
                    }
                    recordFrameTrace(
                        analysisSequence = trackingSequence,
                        result = trackedResult,
                        tracked = tracked,
                        decision = combinedDecision,
                    )

                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - fpsWindowStart
                    if (elapsed >= FPS_WINDOW_MS) {
                        val fps = frameCount * FPS_WINDOW_MS.toFloat() / elapsed.toFloat()
                        mutableStatus.update { it.copy(fps = fps) }
                        frameCount = 0
                        fpsWindowStart = now
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            cancelActions()
            AppLog.e("vision", "frame loop failed: ${error.message}", error)
            mutableStatus.update {
                it.copy(lastError = error.message ?: error.javaClass.simpleName)
            }
        } finally {
            stateJob.cancel()
            runCatching { source.stop() }
            overlay.hide()
            // 仅当仍是本会话 token 时写终态，避免覆盖已启动的新会话状态。
            if (generation.get() == token) {
                activeSource = null
                // 异常退出也必须清 analysisRunning，否则算法切换会永久 pending。
                algorithmCatalog.setAnalysisRunning(false)
                mutableStatus.update {
                    it.copy(
                        running = false,
                        captureReady = false,
                        overlayVisible = false,
                        overlayBlockReason = null,
                        fps = 0f,
                    )
                }
            }
        }
    }

    /**
     * 在帧路径上评估是否派发自动动作。
     *
     * 门控：自动操作开关、场景置信度、帧龄（**不**按赛季硬锁）。
     * 手势后端按 [resolveGestureBackend] 分叉；真正注入在 [actionJob] 中执行。
     *
     * @return 一行决策摘要（skip 原因或 plan 目标），写入 [AlgorithmRuntimeTrace]
     */
    private fun maybeDispatch(
        token: Long,
        config: AppConfig,
        result: VisionResult,
        tracked: List<MultiObjectTracker.TrackedDetection>,
        frameTimestampNanos: Long,
    ): String {
        fun publish(decision: String): String {
            // 规划期 skip/plan 也进决策 ring + INFO，便于与 dispatch_* 时间线对照。
            if (
                decision.startsWith("skip:") ||
                decision.startsWith("plan ") ||
                decision.startsWith("error")
            ) {
                AlgorithmRuntimeTrace.logDecision(decision)
            }
            mutableStatus.update {
                it.copy(
                    lastAutomationDecision = decision,
                    activeGestureBackend = latestGestureBackend.get(),
                )
            }
            return decision
        }

        if (!config.automation.enabled) return publish("skip:automation_off")
        if (result.sceneConfidence < config.automation.minimumSceneConfidence) {
            return publish(
                "skip:scene_conf=${"%.2f".format(result.sceneConfidence)}" +
                    "<${"%.2f".format(config.automation.minimumSceneConfidence)}",
            )
        }

        // 帧龄门控：捕获时刻到决策的排队/处理延迟。完成驱动下分析常 >120ms，
        // 过紧会导致自动操作系统性不触发；与「过期帧」语义对齐到 1s 量级。
        val frameAgeMs = (SystemClock.elapsedRealtimeNanos() - frameTimestampNanos) / 1_000_000L
        if (frameAgeMs < 0L || frameAgeMs > MAX_FRAME_AGE_MS) {
            return publish("skip:frame_age=${frameAgeMs}ms")
        }

        val gestureResolution = resolveGestureBackend(config)
        val gestureEffective = gestureResolution.effective
        latestGestureBackend.set(gestureEffective)

        // CAS 占坑，保证同一时刻最多一个动作任务。
        if (!actionInFlight.compareAndSet(false, true)) {
            return publish("skip:action_in_flight backend=${gestureEffective.name}")
        }

        val sceneConfig = config.scenes.getValue(config.selectedScene)
        val player = result.player
        if (player == null) {
            actionInFlight.set(false)
            return publish("skip:no_player backend=${gestureEffective.name}")
        }
        val playerWidth = player.bounds.width.coerceAtLeast(0.01f)
        val baselineMultiplier = baselineTriggerMultiplier(config, config.selectedScene)
        val effectiveMultiplier = if (config.automation.autoAdjustTriggerDistance) {
            triggerDistanceTuner.effective(config.selectedScene, baselineMultiplier)
        } else {
            baselineMultiplier
        }
        val triggerDistance = effectiveMultiplier * playerWidth

        // 水平门控：只丢掉「整块障碍已完全落在玩家身后」的目标。
        // 旧逻辑要求 left >= player.right - margin，海盐 FIXED 玩家宽约 0.05 时
        // 断崖/沙丘左缘略伸入玩家右侧（gap 为负）会被系统性滤掉 → 永远 no_candidate。
        // 以障碍右缘是否仍越过玩家左缘为准；重叠（gap≤0）仍可触发。
        val behindMargin = sceneConfig.thresholds.behindPlayerMarginRatio
        val candidate = tracked
            .asSequence()
            .filter { it.stableFrames >= sceneConfig.thresholds.stableFrames }
            .filter { it.detection.actionable && !it.detection.diagnosticOnly }
            .filter { it.detection.confidence >= sceneConfig.thresholds.minimumConfidence }
            .filter {
                it.detection.bounds.right > player.bounds.left - behindMargin
            }
            .map { trackedDetection ->
                val gap = trackedDetection.detection.bounds.left - player.bounds.right
                trackedDetection to gap
            }
            // 在触发带内：优先最贴近玩家的目标（|gap| 最小）；允许 gap≤0 的重叠。
            .filter { (_, gap) -> gap <= triggerDistance }
            .minByOrNull { (_, gap) -> kotlin.math.abs(gap) }
            ?.first

        if (candidate == null) {
            actionInFlight.set(false)
            val stable = tracked.count { it.stableFrames >= sceneConfig.thresholds.stableFrames }
            val actionable = tracked.count {
                it.detection.actionable && !it.detection.diagnosticOnly
            }
            val nearestGap = tracked
                .asSequence()
                .filter { it.detection.actionable && !it.detection.diagnosticOnly }
                .map { it.detection.bounds.left - player.bounds.right }
                .filter { it.isFinite() }
                .minOrNull()
            val passedBehind = tracked.count {
                it.detection.actionable &&
                    !it.detection.diagnosticOnly &&
                    it.detection.bounds.right > player.bounds.left - behindMargin
            }
            AlgorithmRuntimeTrace.logCalc(
                "no_candidate scene=${config.selectedScene.name} " +
                    "pw=${"%.3f".format(playerWidth)} mult=${"%.2f".format(effectiveMultiplier)} " +
                    "trigDist=${"%.3f".format(triggerDistance)} " +
                    "player=[${formatBounds(player.bounds)}] " +
                    "nearGap=${nearestGap?.let { "%.3f".format(it) } ?: "-"} " +
                    "stable=$stable act=$actionable behindOk=$passedBehind " +
                    "sc=${"%.2f".format(result.sceneConfidence)} " +
                    "algo=${result.activeAlgorithmId}@${result.activeAlgorithmVersion}" +
                    if (result.usingBuiltinFallback) " builtin" else "",
            )
            if (config.automation.autoAdjustTriggerDistance && actionable > 0) {
                val now = SystemClock.uptimeMillis()
                val adapted = triggerDistanceTuner.onNoCandidate(
                    scene = config.selectedScene,
                    baseline = baselineMultiplier,
                    playerWidth = playerWidth,
                    nearGap = nearestGap,
                    nowMs = now,
                )
                if (adapted != null) {
                    maybePersistTriggerDistance(config.selectedScene, adapted, now)
                    return publish(
                        "skip:no_candidate auto_trig=${"%.2f".format(adapted)} " +
                            "stable=$stable actionable=$actionable behindOk=$passedBehind " +
                            "trigDist=${"%.3f".format(triggerDistance)} " +
                            "pw=${"%.3f".format(playerWidth)} " +
                            "nearGap=${nearestGap?.let { "%.3f".format(it) } ?: "-"} " +
                            "sc=${"%.2f".format(result.sceneConfidence)} " +
                            "backend=${gestureEffective.name}",
                    )
                }
            }
            return publish(
                "skip:no_candidate stable=$stable actionable=$actionable behindOk=$passedBehind " +
                    "trigDist=${"%.3f".format(triggerDistance)} " +
                    "pw=${"%.3f".format(playerWidth)} " +
                    "nearGap=${nearestGap?.let { "%.3f".format(it) } ?: "-"} " +
                    "sc=${"%.2f".format(result.sceneConfidence)} " +
                    "backend=${gestureEffective.name}",
            )
        }

        val gapAtPlan = candidate.detection.bounds.left - player.bounds.right
        if (config.automation.autoAdjustTriggerDistance) {
            val now = SystemClock.uptimeMillis()
            val adapted = triggerDistanceTuner.onPlanSuccess(
                scene = config.selectedScene,
                baseline = baselineMultiplier,
                playerWidth = playerWidth,
                gap = gapAtPlan,
                nowMs = now,
            )
            if (adapted != null) {
                maybePersistTriggerDistance(config.selectedScene, adapted, now)
            }
        }

        val spatialKey = spatialKeyOf(candidate.detection)
        val now = SystemClock.uptimeMillis()
        // 规划期同步预检账本：避免帧环刷屏 plan、而 actionJob 全是 ledger skip。
        // canPlan 为短临界区同步读，无 IO，不在帧路径 runBlocking。
        if (!ledger.canPlan(candidate.trackId, spatialKey, now)) {
            actionInFlight.set(false)
            return publish(
                "skip:ledger track=${candidate.trackId} key=$spatialKey backend=${gestureEffective.name}",
            )
        }
        // 规划期：无障碍可同步快照；Shell（Shizuku/Root）完整 dumpsys 放到 actionJob，避免卡帧。
        val planForegroundClass: String
        if (gestureEffective == GestureBackend.ACCESSIBILITY) {
            if (!gestureCapabilities.isAccessibilityConnected()) {
                actionInFlight.set(false)
                return publish("skip:no_accessibility backend=ACCESSIBILITY")
            }
            val foreground = AccessibilityForegroundProbe.blockingSnapshot()
            if (foreground == null) {
                actionInFlight.set(false)
                return publish(
                    "skip:no_foreground backend=ACCESSIBILITY reason=a11y_unavailable",
                )
            }
            if (foreground.packageName.isBlank()) {
                actionInFlight.set(false)
                return publish("skip:foreground_gate backend=ACCESSIBILITY pkg=")
            }
            if (config.automation.restrictPackages &&
                foreground.packageName !in config.automation.allowedPackages
            ) {
                actionInFlight.set(false)
                return publish(
                    "skip:package_gate backend=ACCESSIBILITY pkg=${foreground.packageName}",
                )
            }
            if (SystemClock.elapsedRealtime() - foreground.observedAtMs > FOREGROUND_MAX_AGE_MS) {
                actionInFlight.set(false)
                return publish("skip:no_foreground backend=ACCESSIBILITY reason=stale")
            }
            planForegroundClass = foreground.className
        } else {
            planForegroundClass = ""
        }

        val planSummary =
            "plan kind=${candidate.detection.kind.name} avoid=${candidate.detection.avoidance.name} " +
                "track=${candidate.trackId} stable=${candidate.stableFrames} " +
                "conf=${"%.2f".format(candidate.detection.confidence)} " +
                "gap=${"%.3f".format(gapAtPlan)} trig=${"%.3f".format(triggerDistance)} " +
                "pw=${"%.3f".format(playerWidth)} " +
                "obs=[${formatBounds(candidate.detection.bounds)}] " +
                "player=[${formatBounds(player.bounds)}] " +
                "backend=${gestureEffective.name} key=$spatialKey " +
                "algo=${result.activeAlgorithmId}" +
                if (result.usingBuiltinFallback) " builtin" else ""
        AlgorithmRuntimeTrace.logCalc(
            "candidate track=${candidate.trackId} kind=${candidate.detection.kind.name} " +
                "avoid=${candidate.detection.avoidance.name} " +
                "gap=${"%.3f".format(gapAtPlan)} trigDist=${"%.3f".format(triggerDistance)} " +
                "jumpXHint=${"%.2f".format(safeJumpX(candidate.detection))} " +
                "restrictPkgs=${config.automation.restrictPackages}",
        )
        actionJob = scope.launch {
            try {
                dispatchPlan(
                    token = token,
                    config = config,
                    gestureBackend = gestureEffective,
                    foregroundClassName = planForegroundClass,
                    candidate = candidate,
                    spatialKey = spatialKey,
                    plannedAt = now,
                )
            } finally {
                actionInFlight.set(false)
            }
        }
        return publish(planSummary)
    }

    /**
     * 写入算法帧轨迹 ring；AppLog 由 [AlgorithmRuntimeTrace] 按状态变化 + 周期节流。
     */
    private fun recordFrameTrace(
        analysisSequence: Long,
        result: VisionResult,
        tracked: List<MultiObjectTracker.TrackedDetection>,
        decision: String?,
    ) {
        val kindHistogram = result.detections
            .groupingBy { it.kind.name }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        val detectionTraces = if (tracked.isNotEmpty()) {
            tracked.map { item ->
                val d = item.detection
                AlgorithmDetectionTrace(
                    kind = d.kind.name,
                    source = d.source.name,
                    confidence = d.confidence,
                    left = d.bounds.left,
                    top = d.bounds.top,
                    right = d.bounds.right,
                    bottom = d.bounds.bottom,
                    avoidance = d.avoidance.name,
                    actionable = d.actionable,
                    diagnosticOnly = d.diagnosticOnly,
                    trackId = item.trackId,
                    stableFrames = item.stableFrames,
                )
            }
        } else {
            result.detections.map { d ->
                AlgorithmDetectionTrace(
                    kind = d.kind.name,
                    source = d.source.name,
                    confidence = d.confidence,
                    left = d.bounds.left,
                    top = d.bounds.top,
                    right = d.bounds.right,
                    bottom = d.bounds.bottom,
                    avoidance = d.avoidance.name,
                    actionable = d.actionable,
                    diagnosticOnly = d.diagnosticOnly,
                )
            }
        }
        val trackSummary = if (tracked.isEmpty()) {
            null
        } else {
            tracked.joinToString(";") { t ->
                "t=${t.trackId}:${t.detection.kind.name}:s=${t.stableFrames}"
            }.take(400)
        }
        val player = result.player
        AlgorithmRuntimeTrace.record(
            AlgorithmFrameTraceEntry(
                epochMs = System.currentTimeMillis(),
                analysisSequence = analysisSequence,
                scene = result.scene.name,
                sceneConfidence = result.sceneConfidence,
                hasPlayer = player != null,
                playerConfidence = player?.confidence,
                playerBounds = player?.let { formatBounds(it.bounds) },
                obstacleCount = result.detections.size,
                actionableCount = result.actionableDetections.size,
                kindHistogram = kindHistogram,
                processingMs = result.processingNanos / 1_000_000f,
                timing = result.timing,
                filteredOutCount = result.filteredOut.size,
                algorithmId = result.activeAlgorithmId,
                algorithmVersion = result.activeAlgorithmVersion,
                generation = result.algorithmGeneration,
                usingBuiltinFallback = result.usingBuiltinFallback,
                frameError = result.error,
                disabledObstaclesDropped = false,
                detections = detectionTraces,
                trackSummary = trackSummary,
                decision = decision,
            ),
        )
    }

    private fun formatBounds(bounds: NormalizedRect): String =
        "%.2f,%.2f-%.2f,%.2f".format(bounds.left, bounds.top, bounds.right, bounds.bottom)

    /**
     * 在 [actionMutex] 下规划并提交手势序列。
     *
     * 再次校验 [generation]、账本去重、前台包/窗口与速率上限；
     * 前台探测与手势注入均按 [gestureBackend] 分叉（无障碍 / Shizuku / Root）。
     * 单次 stroke 失败可按 retryLimit 退避重试。
     */
    private suspend fun dispatchPlan(
        token: Long,
        config: AppConfig,
        gestureBackend: GestureBackend,
        foregroundClassName: String,
        candidate: MultiObjectTracker.TrackedDetection,
        spatialKey: String,
        plannedAt: Long,
    ) {
        if (generation.get() != token) return
        latestGestureBackend.set(gestureBackend)
        actionMutex.withLock {
            if (generation.get() != token) return
            fun note(decision: String) {
                AlgorithmRuntimeTrace.logDecision(decision)
                mutableStatus.update {
                    it.copy(
                        lastAutomationDecision = decision,
                        activeGestureBackend = gestureBackend,
                    )
                }
            }
            // 关闭后不得继续 stroke；与 maybeDispatch 门控对称。
            if (!config.automation.enabled) {
                note("dispatch_abort:automation_off track=${candidate.trackId}")
                return
            }
            if (!ledger.canPlan(candidate.trackId, spatialKey, plannedAt)) {
                note("dispatch_skip:ledger track=${candidate.trackId} key=$spatialKey")
                return
            }

            val foreground = gestureDispatchers.snapshotForeground(gestureBackend)
            if (foreground == null) {
                val reason = when (gestureBackend) {
                    GestureBackend.SHIZUKU,
                    GestureBackend.ROOT,
                    -> "dumpsys_fail"
                    else -> "a11y_unavailable"
                }
                note(
                    "dispatch_skip:no_foreground backend=${gestureBackend.name} " +
                        "reason=$reason track=${candidate.trackId}",
                )
                return@withLock
            }
            if (SystemClock.elapsedRealtime() - foreground.observedAtMs > FOREGROUND_MAX_AGE_MS) {
                note(
                    "dispatch_skip:foreground_stale backend=${gestureBackend.name} " +
                        "track=${candidate.trackId}",
                )
                return@withLock
            }
            if (foreground.packageName.isBlank()) {
                note(
                    "dispatch_skip:foreground_gate backend=${gestureBackend.name} pkg= " +
                        "track=${candidate.trackId}",
                )
                return@withLock
            }
            val packageRestricted = config.automation.restrictPackages
            val packageAllowed = !packageRestricted ||
                foreground.packageName in config.automation.allowedPackages
            if (!packageAllowed) {
                note(
                    "dispatch_skip:package_gate backend=${gestureBackend.name} " +
                        "pkg=${foreground.packageName} " +
                        "restrict=${config.automation.restrictPackages} " +
                        "allow=${
                            if (config.automation.restrictPackages) {
                                config.automation.allowedPackages.sorted().joinToString(",").ifBlank { "-" }
                            } else {
                                "*"
                            }
                        } " +
                        "track=${candidate.trackId}",
                )
                return@withLock
            }
            // class recheck：仅当规划期 className 非空才 startsWith 校验（Shell 规划期常为空）。
            val classStillMatches = foregroundClassName.isBlank() ||
                foreground.className.startsWith(foregroundClassName)
            if (!classStillMatches) {
                note(
                    "dispatch_skip:foreground_recheck backend=${gestureBackend.name} " +
                        "pkg=${foreground.packageName} cls=${foreground.className.ifBlank { "-" }} " +
                        "track=${candidate.trackId}",
                )
                return@withLock
            }

            val now = SystemClock.uptimeMillis()
            while (
                recentActionTimes.isNotEmpty() &&
                now - recentActionTimes.first() >= ACTION_RATE_WINDOW_MS
            ) {
                recentActionTimes.removeFirst()
            }

            val plan = planGestures(config.selectedScene, candidate.detection, now)
            if (plan.isEmpty()) {
                note(
                    "dispatch_skip:empty_plan kind=${candidate.detection.kind.name} " +
                        "avoid=${candidate.detection.avoidance.name} track=${candidate.trackId}",
                )
                return@withLock
            }
            // doublePressDelayMs 的第二次按压仍占用速率配额。
            val planActionCount = plan.sumOf { stroke ->
                val isClick = stroke.gesture.endX == null
                if (isClick && stroke.gesture.doublePressDelayMs > 0L) 2 else 1
            }
            if (recentActionTimes.size + planActionCount > config.automation.maxActionsPerSecond) {
                note(
                    "dispatch_skip:rate_limit need=$planActionCount " +
                        "window=${recentActionTimes.size} track=${candidate.trackId}",
                )
                return@withLock
            }
            note(
                "dispatch_begin strokes=${plan.size} actions=$planActionCount " +
                    "kind=${candidate.detection.kind.name} avoid=${candidate.detection.avoidance.name} " +
                    "backend=${gestureBackend.name} track=${candidate.trackId} " +
                    "fg=${foreground.packageName} " +
                    plan.joinToString(";") { stroke ->
                        val g = stroke.gesture
                        val dbl = if (g.doublePressDelayMs > 0L) " dbl=${g.doublePressDelayMs}" else ""
                        val end = if (g.endX != null) "→${"%.2f".format(g.endX)},${"%.2f".format(g.endY)}" else ""
                        "g=${"%.2f".format(g.startX)},${"%.2f".format(g.startY)}$end d=${g.durationMs}$dbl"
                    }.take(220),
            )

            for ((index, stroke) in plan.withIndex()) {
                if (generation.get() != token) return
                if (index > 0) {
                    val wait = (stroke.dueAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                    if (wait > 0L) delay(wait)
                }
                if (generation.get() != token || !currentCoroutineContext().isActive) return

                var attempt = 0
                var completed = false
                while (attempt <= config.automation.retryLimit && !completed) {
                    if (generation.get() != token || !currentCoroutineContext().isActive) return
                    val created = SystemClock.uptimeMillis()
                    val action = AutomationAction(
                        id = actionIds.getAndIncrement(),
                        trackId = candidate.trackId,
                        avoidance = candidate.detection.avoidance,
                        gesture = stroke.gesture,
                        createdAtUptimeMs = created,
                        expiresAtUptimeMs = created + stroke.ttlMs,
                        // 空集 = 不限制包名（与 AutomationAction.matchesPackage 一致）
                        allowedPackages = if (config.automation.restrictPackages) {
                            config.automation.allowedPackages
                        } else {
                            emptySet()
                        },
                        requiredWindowClassPrefixes = emptySet(),
                        retryCount = attempt,
                    )
                    val receipt = arbiter.dispatch(action)
                    when (receipt.outcome) {
                        DispatchOutcome.COMPLETED -> {
                            ledger.commit(
                                receipt,
                                spatialKey,
                                completedAtUptimeMs = SystemClock.uptimeMillis(),
                            )
                            val completedAt = SystemClock.uptimeMillis()
                            recentActionTimes.addLast(completedAt)
                            if (stroke.gesture.endX == null && stroke.gesture.doublePressDelayMs > 0L) {
                                recentActionTimes.addLast(completedAt)
                            }
                            completed = true
                            note(
                                "dispatch_ok action=${action.id} track=${candidate.trackId} " +
                                    "backend=${gestureBackend.name} stroke=$index attempt=$attempt " +
                                    "detail=${receipt.detail?.take(180) ?: "-"}",
                            )
                        }
                        DispatchOutcome.EXPIRED,
                        DispatchOutcome.CANCELLED,
                        DispatchOutcome.REJECTED,
                        -> {
                            attempt++
                            note(
                                "dispatch_fail outcome=${receipt.outcome.name} " +
                                    "backend=${gestureBackend.name} detail=${receipt.detail ?: "-"} " +
                                    "track=${candidate.trackId} stroke=$index attempt=$attempt",
                            )
                            if (attempt > config.automation.retryLimit) {
                                return@withLock
                            }
                            delay(RETRY_BACKOFF_MS)
                        }
                    }
                }
                if (!completed) return
            }
        }
    }

    /**
     * 发布悬浮窗；非 force 时用签名跳过无变化刷新。
     * 悬浮窗绘制侧负责将归一化坐标转为像素。
     *
     * @param runtimeStatus 当前运行时状态；透传给 HUD 用于展示「前台包 / 自动操作门控原因」，
     *   null 时 HUD 不绘制该附加行。
     */
    private suspend fun publishOverlay(
        overlayConfig: top.azek431.hzzs.core.model.OverlayConfig,
        result: VisionResult?,
        showCoordinateGrid: Boolean,
        force: Boolean,
        runtimeStatus: RuntimeStatus? = null,
    ): OverlayPublishState {
        val signature = overlaySignature(overlayConfig, result, showCoordinateGrid)
        val blockedNeedsRetry = mutableStatus.value.overlayBlockReason.let { reason ->
            reason == OverlayBlockReason.PERMISSION || reason == OverlayBlockReason.ADD_VIEW_FAILED
        }
        // 权限刚授予或 add 瞬时失败时签名可能不变，必须强制重试 show，否则悬浮窗永久不出现。
        if (!force && !blockedNeedsRetry && signature == lastOverlaySignature) {
            val current = mutableStatus.value
            return OverlayPublishState(current.overlayVisible, current.overlayBlockReason)
        }
        val showResult = overlay.show(
            config = overlayConfig,
            result = result,
            showCoordinateGrid = showCoordinateGrid,
            runtimeStatus = runtimeStatus,
        )
        lastOverlaySignature = signature
        return OverlayPublishState(showResult.visible, showResult.blockReason)
    }

    private data class OverlayPublishState(
        val visible: Boolean,
        val blockReason: OverlayBlockReason?,
    )

    /** 粗粒度签名：用于抑制重复 overlay 刷新，非密码学哈希。 */
    private fun overlaySignature(
        config: top.azek431.hzzs.core.model.OverlayConfig,
        result: VisionResult?,
        showCoordinateGrid: Boolean,
    ): Int {
        var hash = config.hashCode()
        hash = 31 * hash + showCoordinateGrid.hashCode()
        hash = 31 * hash + (result?.detections?.size ?: -1)
        hash = 31 * hash + ((result?.sceneConfidence ?: -1f) * 1000f).toInt()
        result?.detections?.forEach { detection ->
            hash = 31 * hash + detection.kind.hashCode()
            hash = 31 * hash + detection.id.hashCode()
            hash = 31 * hash + (detection.bounds.left * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.top * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.right * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.bottom * 1000f).toInt()
            hash = 31 * hash + (detection.confidence * 100f).toInt()
            hash = 31 * hash + detection.actionable.hashCode()
        }
        hash = 31 * hash + (result?.error?.hashCode() ?: 0)
        return hash
    }

    /** 空间去重键：按 kind + 量化中心，配合 trackId 防止重复提交同一障碍。 */
    private fun spatialKeyOf(detection: Detection): String {
        val cx = ((detection.bounds.left + detection.bounds.right) * 0.5f * 20f).toInt()
        val cy = ((detection.bounds.top + detection.bounds.bottom) * 0.5f * 20f).toInt()
        return "${detection.kind.name}:$cx:$cy"
    }

    /**
     * 跳跃落点 X：默认 0.82（历史主路径安全区）。
     * 若障碍框水平覆盖默认点，则落到障碍左缘外侧一点，减少「点进坑」。
     */
    private fun safeJumpX(detection: Detection): Float {
        val preferred = 0.82f
        val left = detection.bounds.left
        val right = detection.bounds.right
        return if (preferred in left..right) {
            (left - 0.04f).coerceIn(0.55f, 0.90f)
        } else {
            preferred
        }
    }

    private fun baselineTriggerMultiplier(config: AppConfig, scene: SceneId): Float =
        when (scene) {
            SceneId.SWEET_FACTORY -> config.automation.sweetTriggerDistancePlayerWidths
            SceneId.BAMBOO_BOOKSTORE -> config.automation.bambooTriggerDistancePlayerWidths
            SceneId.SEA_SALT_LIVING_ROOM -> config.automation.seaSaltTriggerDistancePlayerWidths
        }

    /**
     * 将自调后的触发倍数写回**已保存**配置（节流）；失败只打日志，不打断帧循环。
     *
     * 经 [SettingsRepository.updateSavedPreservingPreview] 写盘，**不**清空设置页 preview 草稿；
     * 若有草稿，会把触发距离字段合并进 preview，避免用户「保存并应用」时盖回旧值。
     */
    private fun maybePersistTriggerDistance(scene: SceneId, multiplier: Float, nowMs: Long) {
        if (!triggerDistanceTuner.shouldPersist(nowMs)) return
        scope.launch {
            runCatching {
                val clamped = multiplier.coerceIn(0.5f, 8f)
                val saved = settingsRepository.updateSavedPreservingPreview { snap ->
                    val auto = snap.automation
                    if (!auto.autoAdjustTriggerDistance) return@updateSavedPreservingPreview snap
                    val nextAuto = when (scene) {
                        SceneId.SWEET_FACTORY ->
                            if (kotlin.math.abs(auto.sweetTriggerDistancePlayerWidths - clamped) < 0.03f) {
                                return@updateSavedPreservingPreview snap
                            } else {
                                auto.copy(sweetTriggerDistancePlayerWidths = clamped)
                            }
                        SceneId.BAMBOO_BOOKSTORE ->
                            if (kotlin.math.abs(auto.bambooTriggerDistancePlayerWidths - clamped) < 0.03f) {
                                return@updateSavedPreservingPreview snap
                            } else {
                                auto.copy(bambooTriggerDistancePlayerWidths = clamped)
                            }
                        SceneId.SEA_SALT_LIVING_ROOM ->
                            if (kotlin.math.abs(auto.seaSaltTriggerDistancePlayerWidths - clamped) < 0.03f) {
                                return@updateSavedPreservingPreview snap
                            } else {
                                auto.copy(seaSaltTriggerDistancePlayerWidths = clamped)
                            }
                    }
                    snap.copy(automation = nextAuto)
                }
                val persisted = when (scene) {
                    SceneId.SWEET_FACTORY -> saved.automation.sweetTriggerDistancePlayerWidths
                    SceneId.BAMBOO_BOOKSTORE -> saved.automation.bambooTriggerDistancePlayerWidths
                    SceneId.SEA_SALT_LIVING_ROOM -> saved.automation.seaSaltTriggerDistancePlayerWidths
                }
                if (kotlin.math.abs(persisted - clamped) < 0.03f) {
                    AppLog.i(
                        "automation",
                        "auto trigger distance scene=${scene.name} → ${"%.2f".format(clamped)}",
                    )
                }
            }.onFailure { error ->
                AppLog.w(
                    "automation",
                    "persist trigger distance failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private data class PlannedStroke(
        val gesture: GestureSpec,
        val dueAt: Long,
        val ttlMs: Long,
    )

    /**
     * 按避障类型规划归一化手势时序。
     *
     * - 地面大障碍 / 宽坑：点按（单跳/双跳），落点取安全区（勿点到障碍框内）；
     * - 头顶障碍：下滑，TTL 更长；
     * - PRESS / SWIPE_UP：落点取 [Detection.bounds] 中心（几何真相源）；
     * - 手势坐标为全屏归一化 [0,1]，由手势分发层转像素。
     */
    private fun planGestures(
        scene: SceneId,
        detection: Detection,
        now: Long,
    ): List<PlannedStroke> {
        // 跳点：优先固定安全点；若障碍框盖住该点则左移到障碍左缘外侧，避免点进坑。
        val jumpX = safeJumpX(detection)
        val jumpY = 0.72f
        val jump = GestureSpec(jumpX, jumpY, durationMs = 24L)
        val slide = GestureSpec(jumpX, 0.68f, jumpX, 0.88f, 220L)
        val centerX = ((detection.bounds.left + detection.bounds.right) * 0.5f).coerceIn(0f, 1f)
        val centerY = ((detection.bounds.top + detection.bounds.bottom) * 0.5f).coerceIn(0f, 1f)
        return when (detection.avoidance) {
            Avoidance.NONE -> emptyList()
            Avoidance.JUMP -> listOf(PlannedStroke(jump, now, ACTION_TTL_MS))
            Avoidance.DOUBLE_JUMP -> {
                // 海盐酱油脚本约 60ms 双击间隔；竹影/甜品沿用既有常量。
                val gap = when (scene) {
                    SceneId.SWEET_FACTORY -> DOUBLE_JUMP_GAP_SWEET_MS
                    SceneId.BAMBOO_BOOKSTORE -> DOUBLE_JUMP_GAP_BAMBOO_MS
                    SceneId.SEA_SALT_LIVING_ROOM -> DOUBLE_JUMP_GAP_SEA_MS
                }
                // 单规格携带 doublePressDelayMs，由分发层真正消费第二次按压间隔。
                listOf(
                    PlannedStroke(
                        GestureSpec(
                            startX = jump.startX,
                            startY = jump.startY,
                            durationMs = jump.durationMs,
                            doublePressDelayMs = gap,
                        ),
                        now,
                        ACTION_TTL_MS + gap,
                    ),
                )
            }
            Avoidance.SLIDE -> {
                val ttl = when (scene) {
                    SceneId.SWEET_FACTORY -> SLIDE_TTL_SWEET_MS
                    SceneId.BAMBOO_BOOKSTORE,
                    SceneId.SEA_SALT_LIVING_ROOM,
                    -> SLIDE_TTL_BAMBOO_MS
                }
                listOf(PlannedStroke(slide, now, ttl))
            }
            /** 单次按键（如复活按钮），取障碍中心作为归一化坐标。 */
            Avoidance.PRESS -> listOf(
                PlannedStroke(
                    GestureSpec(centerX, centerY, durationMs = 24L),
                    now,
                    ACTION_TTL_MS,
                ),
            )
            /**
             * 上滑：从障碍中心上滑约 10% 视口高度。
             * 酱油海盐船锚脚本为「向下滑」→ 已映射 Avoidance.SLIDE，不走本分支。
             */
            Avoidance.SWIPE_UP -> listOf(
                PlannedStroke(
                    GestureSpec(
                        startX = centerX,
                        startY = centerY,
                        endX = centerX,
                        endY = (centerY - 0.10f).coerceIn(0f, 1f),
                        durationMs = 100L,
                    ),
                    now,
                    ACTION_TTL_MS,
                ),
            )
        }
    }

    /**
     * 自动复活：与障碍自动操作独立。
     *
     * 无障碍节点树按精确文案匹配「原地复活」「重新冒险」，点可点祖先屏幕中心。
     * 冷却 [AUTO_REVIVE_COOLDOWN_MS]；需无障碍连接。返回非 null 决策串写入帧轨迹。
     */
    private fun maybeAutoRevive(token: Long, config: AppConfig): String? {
        if (!config.automation.autoReviveEnabled) return null
        if (!HzzsAccessibilityService.isConnected()) {
            return "skip:revive_no_a11y"
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastAutoReviveAtMs.get()
        if (last > 0L && now - last < AUTO_REVIVE_COOLDOWN_MS) {
            return null
        }
        // 与障碍动作串行：有在飞手势时不抢。
        if (actionInFlight.get()) return null
        val center = HzzsAccessibilityService.findClickableCenterByExactTexts(
            labels = AUTO_REVIVE_LABELS,
            preferVisible = true,
        ) ?: return null
        if (!actionInFlight.compareAndSet(false, true)) return null
        lastAutoReviveAtMs.set(now)
        val (cx, cy) = center
        val plannedAt = SystemClock.uptimeMillis()
        actionJob = scope.launch {
            try {
                dispatchAutoReviveClick(
                    token = token,
                    config = config,
                    centerX = cx,
                    centerY = cy,
                    plannedAt = plannedAt,
                )
            } finally {
                actionInFlight.set(false)
            }
        }
        return "plan revive press@${"%.2f".format(cx)},${"%.2f".format(cy)}"
    }

    private suspend fun dispatchAutoReviveClick(
        token: Long,
        config: AppConfig,
        centerX: Float,
        centerY: Float,
        plannedAt: Long,
    ) {
        if (generation.get() != token) return
        actionMutex.withLock {
            if (generation.get() != token) return
            if (!config.automation.autoReviveEnabled) {
                AlgorithmRuntimeTrace.logDecision("dispatch_abort:revive_off")
                return
            }
            val gestureBackend = resolveGestureBackend(config).effective
            latestGestureBackend.set(gestureBackend)
            val action = AutomationAction(
                id = actionIds.getAndIncrement(),
                trackId = AUTO_REVIVE_TRACK_ID,
                avoidance = Avoidance.PRESS,
                gesture = GestureSpec(
                    startX = centerX.coerceIn(0f, 1f),
                    startY = centerY.coerceIn(0f, 1f),
                    durationMs = 24L,
                ),
                createdAtUptimeMs = plannedAt,
                expiresAtUptimeMs = plannedAt + ACTION_TTL_MS,
                allowedPackages = emptySet(),
                requiredWindowClassPrefixes = emptySet(),
                retryCount = 0,
            )
            AlgorithmRuntimeTrace.logDecision(
                "dispatch_begin revive action=${action.id} backend=${gestureBackend.name} " +
                    "xy=${"%.3f".format(centerX)},${"%.3f".format(centerY)}",
            )
            val receipt = arbiter.dispatch(action)
            AlgorithmRuntimeTrace.logDecision(
                "dispatch_${if (receipt.outcome == DispatchOutcome.COMPLETED) "ok" else "fail"} " +
                    "revive outcome=${receipt.outcome.name} " +
                    "detail=${receipt.detail?.take(80) ?: "-"}",
            )
        }
    }

    /**
     * 解析玩家参考框，不必每帧都跑玩家检测。
     *
     * CONTINUOUS / DETECT_ONCE / FIXED_RATIO 三种模式；FIXED_RATIO 直接合成归一化框。
     */
    private fun VisionResult.withPlayerReference(sceneConfig: SceneConfig): VisionResult {
        val thresholds = sceneConfig.thresholds
        val reference = when (thresholds.playerReferenceMode) {
            PlayerReferenceMode.CONTINUOUS -> player
            PlayerReferenceMode.DETECT_ONCE -> {
                player?.also { detectedPlayerReference.compareAndSet(null, it) }
                detectedPlayerReference.get()
            }
            PlayerReferenceMode.FIXED_RATIO -> fixedPlayerReference(thresholds.fixedPlayerXRatio)
        }
        return copy(player = reference)
    }

    private fun fixedPlayerReference(xRatio: Float): Detection {
        val center = xRatio.coerceIn(0.05f, 0.45f)
        val halfWidth = 0.025f
        return Detection(
            id = Long.MAX_VALUE,
            kind = ObjectKind.PLAYER,
            bounds = NormalizedRect(
                left = (center - halfWidth).coerceAtLeast(0f),
                top = 0.72f,
                right = (center + halfWidth).coerceAtMost(1f),
                bottom = 0.94f,
            ),
            confidence = 1f,
            actionable = false,
        )
    }

    /**
     * 清空运行时管线状态：取消动作、重置 tracker/ledger/引擎分析侧。
     * 不切换算法 profile；算法回退由引擎 [VisionEngine.configureAlgorithm] 负责。
     */
    private suspend fun resetPipeline() {
        actionJob?.cancelAndJoin()
        actionJob = null
        actionInFlight.set(false)
        tracker.reset()
        ledger.reset()
        engine.reset()
        mutableLatestResult.value = null
        detectedPlayerReference.set(null)
        recentActionTimes.clear()
        lastOverlaySignature = Int.MIN_VALUE
    }

    private class CaptureUnavailable(message: String) : IllegalStateException(message)
    private class VisionUnavailable(message: String) : IllegalStateException(message)
    private class RuntimeRestartRequired(message: String) : IllegalStateException(message)

    /** 开发者强制优先，并对本机不支持的后端 fail-soft 回退。 */
    private fun AppConfig.resolveCaptureBackend(): CaptureBackendResolution =
        resolveEffectiveCaptureBackend(
            captureBackend = captureBackend,
            developerEnabled = developer.enabled,
            forceCaptureBackend = developer.forceCaptureBackend,
        )

    private fun AppConfig.effectiveCaptureBackend(): CaptureBackend =
        resolveCaptureBackend().effective

    /** 解析有效手势后端（AUTO → 无障碍 / 条件 Shizuku；永不 Root）。 */
    private fun resolveGestureBackend(config: AppConfig) =
        resolveEffectiveGestureBackend(
            gestureBackend = config.automation.gestureBackend,
            accessibilityConnected = gestureCapabilities.isAccessibilityConnected(),
            shizukuReady = gestureCapabilities.isShizukuReady(),
        )

    /**
     * 安全门控字段强制取 [saved]：自动操作与截图后端不得随设置草稿生效。
     * 主题/悬浮窗/检测阈值等仍可用 preview 即时预览。
     */
    private fun AppConfig.withSavedSafetyGates(saved: AppConfig): AppConfig = copy(
        automation = saved.automation,
        captureBackend = saved.captureBackend,
        developer = developer.copy(
            forceCaptureBackend = if (saved.developer.enabled) {
                saved.developer.forceCaptureBackend
            } else {
                null
            },
        ),
    )

    private companion object {
        const val FOREGROUND_MAX_AGE_MS = 1_500L
        const val MAX_CONSECUTIVE_VISION_FAILURES = 5
        const val FPS_WINDOW_MS = 1_000L
        const val ACTION_RATE_WINDOW_MS = 1_000L
        const val ACTION_TTL_MS = 650L
        const val DOUBLE_JUMP_GAP_SWEET_MS = 75L
        const val DOUBLE_JUMP_GAP_BAMBOO_MS = 80L
        /** 海盐酱油脚本双击间隔约 60ms。 */
        const val DOUBLE_JUMP_GAP_SEA_MS = 60L
        const val SLIDE_TTL_SWEET_MS = 650L
        const val SLIDE_TTL_BAMBOO_MS = 600L
        /** 捕获时间戳到动作决策的最大允许延迟（含分析耗时）。 */
        const val MAX_FRAME_AGE_MS = 1_000L
        /** 手势失败后的退避；过长会叠加 actionInFlight 占锁，体感更卡。 */
        const val RETRY_BACKOFF_MS = 20L
        const val PERMISSION_BACKOFF_MS = 80L
        const val READY_NULL_FRAME_BACKOFF_MS = 12L
        const val IDLE_BACKOFF_MS = 80L
        const val DISABLED_SCENE_BACKOFF_MS = 250L
        /** 自动复活连点冷却（与用户确认 0.3s 对齐）。 */
        const val AUTO_REVIVE_COOLDOWN_MS = 300L
        const val SHIZUKU_HEALTH_CHECK_INTERVAL_MS = 30_000L
        const val SHIZUKU_NOTIFICATION_CHANNEL_ID = "shizuku_health_channel"
        const val SHIZUKU_NOTIFICATION_ID = 1001
        /** 账本用固定 track，避免与障碍 track 冲突。 */
        const val AUTO_REVIVE_TRACK_ID = Long.MAX_VALUE - 7L
        val AUTO_REVIVE_LABELS: List<String> = listOf("原地复活", "重新冒险")
    }
}
