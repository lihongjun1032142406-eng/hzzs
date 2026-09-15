package top.azek431.hzzs.data.vision

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.azek431.hzzs.R
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.decision.FailClosedJinChanDecisionEngine
import top.azek431.hzzs.data.jinchan.decision.JinChanDecisionInput
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinResult
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceAssembler
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceFacts
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.execution.JinChanExecutionCoordinator
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.state.JinChanShadowStatePublisher
import top.azek431.hzzs.platform.compat.CaptureBackendResolution
import top.azek431.hzzs.platform.compat.GestureCapabilityResolver
import top.azek431.hzzs.platform.compat.ShizukuHealthCheck
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.capture.CaptureState
import top.azek431.hzzs.service.capture.FrameSource
import top.azek431.hzzs.service.capture.FrameSourceFactory
import top.azek431.hzzs.service.overlay.OverlayController
import top.azek431.hzzs.service.vision.VisionAnalysisForegroundService
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Clean Base 运行时控制器：帧循环的唯一所有者。
 *
 * **JinChanAI Clean Base 阶段职责（capture-only）**：
 * - 串行编排「截图 → 悬浮窗状态 HUD → 可选自动操作门控」；
 * - 持有截图源生命周期、Shizuku 健康监控与前台上报，保证单会话独占截图源；
 * - 通过 [generation] 令牌丢弃已停止会话的陈旧帧。
 *
 * **本阶段不做的事（已清退）**：
 * - 不加载任何识别引擎 / 原生视觉库；
 * - 不做跨帧追踪（Tracker）；
 * - 不解析算法包、不激活算法、不下载算法、不安装捆绑算法；
 * - 不产生任何检测结果（检测模型已随算法层清退）；
 * - 不派发任何真实手势 / 点击 / 按键。
 *
 * 线程与所有权：
 * - 生命周期（start/stop/restart）在 [lifecycleMutex] 下串行；
 * - 帧循环运行于 [scope]（Default）；
 * - [top.azek431.hzzs.service.capture.CapturedFrame] 仅在 `frame.use { }` 内借用，
 *   循环不跨帧持有像素缓冲。
 *
 * 安全不变量：
 * - 真实动作总闸恒为 [AppConfig.ACTION_ENABLED]（Clean Base = false），
 *   任何自动操作决策都 fail-closed；
 * - 场景 / 算法 generation 变化不再存在；截图后端变化要求重启；
 * - 设置收集器只替换不可变配置快照。
 *
 * 坐标：本阶段不再产生检测框，悬浮窗仅展示运行时状态。
 */
@Singleton
class VisionRuntimeController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val sources: FrameSourceFactory,
    private val overlay: OverlayController,
    private val debugFrameRecorder: DebugFrameRecorder,
    private val jinChanExecutionCoordinator: JinChanExecutionCoordinator,
    private val gestureCapabilities: GestureCapabilityResolver,
    private val jinChanShadowStatePublisher: JinChanShadowStatePublisher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    /** 会话代数：stop/start 递增，用于 fail-closed 丢弃陈旧帧。 */
    private val generation = AtomicLong(0)
    private val latestConfig = AtomicReference(AppConfig())

    /**
     * 手势串行闸门。具体 [top.azek431.hzzs.domain.automation.GestureDispatcher]
     * 按动作上挂的 [latestGestureBackend] 在 dispatch 时再解析，避免绑死无障碍。
     *
     * Clean Base：本阶段没有任何动作来源，仅保留基础设施与状态上报。
     */
    private val latestGestureBackend = AtomicReference(GestureBackend.ACCESSIBILITY)
    private val mutableStatus = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()

    @Volatile
    private var runtimeJob: Job? = null

    @Volatile
    private var activeSource: FrameSource? = null

    @Volatile
    private var jinChanSessionId: JinChanFrameSessionId? = null

    // Shizuku 持续监控相关字段
    private var shizukuHealthCheckJob: Job? = null
    private var shizukuLastHealthState: Boolean? = null
    private var shizukuSessionSelected = false

    init {
        scope.launch {
            // 主题/悬浮窗可跟 preview；自动操作与截图后端只跟已落盘 saved，
            // 避免设置草稿未「保存并应用」就换源。
            combine(
                settingsRepository.savedConfig,
                settingsRepository.config,
            ) { saved, previewOrSaved ->
                previewOrSaved.withSavedSafetyGates(saved)
            }.collect { next ->
                val previous = latestConfig.getAndSet(next)
                val previousBackend = previous.effectiveCaptureBackend()
                val nextBackend = next.effectiveCaptureBackend()
                mutableStatus.update {
                    it.copy(activeGestureBackend = resolveGestureBackend(next).effective)
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
     * 输入：当前设置中的截图后端；输出：更新 [status]。
     * 在 [lifecycleMutex] 内推进 [generation]，保证单会话独占截图源。
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

            // Shizuku 健康检查与自动降级
            var finalGestureBackend = gestureResolution.effective
            var healthCheckReason: String? = null

            // 新会话重新建立健康基线，避免上一会话状态抑制本次降级。
            shizukuLastHealthState = null
            shizukuSessionSelected = finalGestureBackend == GestureBackend.SHIZUKU
            if (shizukuSessionSelected) {
                val health = withContext(Dispatchers.IO) {
                    runCatching { ShizukuHealthCheck.check() }.getOrNull()
                }

                if (health != null && !health.isHealthy) {
                    healthCheckReason = health.reason
                    AppLog.w("vision", "Shizuku health check failed: ${health.reason ?: "unknown"}")

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
            jinChanExecutionCoordinator.startSession(config.automation, finalGestureBackend)
            val gestureFallbackReason = healthCheckReason ?: gestureResolution.fallbackReason

            AppLog.i(
                "vision",
                "start session gen=$token backend=${backend.name} " +
                    "requested=${resolution.requested.name} " +
                    "overlay=${config.overlay.enabled} automation=${config.automation.enabled} " +
                    "gesture=${finalGestureBackend.name}" +
                    (healthCheckReason?.let { " healthNote=$it" } ?: "") +
                    (gestureFallbackReason?.let { " gestureNote=$it" } ?: ""),
            )
            if (!AppConfig.ACTION_ENABLED) {
                AppLog.i("vision", "clean-base: real actions disabled (ACTION_ENABLED=false)")
            }
            runCatching {
                top.azek431.hzzs.mcp.McpEventBus.append(
                    top.azek431.hzzs.mcp.McpEventBus.Type.ANALYSIS_START,
                    JSONObject()
                        .put("backend", backend.name)
                        .put("automation", config.automation.enabled)
                        .put("cleanBase", AppConfig.JINCHAN_CLEAN_BASE),
                )
            }
            activeSource = source
            val jinChanSession = jinChanShadowStatePublisher.startSession()
            jinChanSessionId = jinChanSession
            mutableStatus.value = RuntimeStatus(
                running = true,
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
                        jinChanSession = jinChanSession,
                    )
                }
                // 分析启停绑定前台服务，降低 OEM 后台杀进程概率；仅 alive 期间提优先级。
                VisionAnalysisForegroundService.start(appContext)
            } catch (error: Throwable) {
                jinChanExecutionCoordinator.stopSession()
                jinChanShadowStatePublisher.stopSession(jinChanSession)
                jinChanSessionId = null
                activeSource = null
                runCatching { source.stop() }
                AppLog.e("vision", "start capture failed: ${error.message}", error)
                mutableStatus.value = RuntimeStatus(
                    running = false,
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
     * 先递增 [generation] 使进行中的循环 fail-closed，再 cancelAndJoin、隐藏悬浮窗。
     */
    suspend fun stop() = lifecycleMutex.withLock {
        val prevGen = generation.get()
        generation.incrementAndGet()
        jinChanExecutionCoordinator.stopSession()
        AppLog.i("vision", "stop session prevGen=$prevGen")
        jinChanSessionId?.let(jinChanShadowStatePublisher::stopSession)
        jinChanSessionId = null
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        val source = activeSource
        activeSource = null
        runCatching { source?.stop() }
        overlay.hide()
        runCatching {
            top.azek431.hzzs.mcp.McpEventBus.append(
                top.azek431.hzzs.mcp.McpEventBus.Type.ANALYSIS_STOP,
                JSONObject().put("prevGen", prevGen),
            )
        }
        VisionAnalysisForegroundService.stop(appContext)
        stopShizukuHealthMonitoring()
        mutableStatus.value = mutableStatus.value.copy(
            running = false,
            captureReady = false,
            overlayVisible = false,
            overlayBlockReason = null,
            fps = 0f,
        )
    }

    /**
     * 对外入口：取消在飞自动操作（MCP `cancel_actions`、切换手势后端等）。
     *
     * Clean Base：本阶段不存在任何自动操作，恒为空操作（fail-closed）。
     */
    fun cancelPendingActions() {
        jinChanExecutionCoordinator.cancelPending()
        AppLog.i("vision", "cancelPendingActions requested (best-effort; no hard system cancellation)")
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

    /** 显示 Shizuku 问题通知。 */
    private fun showShizukuIssueNotification(title: String, detail: String?) {
        val notificationManager = NotificationManagerCompat.from(appContext)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SHIZUKU_NOTIFICATION_CHANNEL_ID,
                "Shizuku 问题通知",
                NotificationManager.IMPORTANCE_LOW,
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

    /** 取消 Shizuku 问题通知。 */
    private fun cancelShizukuNotification() {
        val notificationManager = NotificationManagerCompat.from(appContext)
        notificationManager.cancel(SHIZUKU_NOTIFICATION_ID)
    }

    /**
     * 帧循环主体：在 [token] 与 [generation] 一致期间持续取帧并更新状态。
     *
     * Clean Base 不消费像素做任何识别：帧只用于
     * 1) 维持截图会话存活与 FPS / captureReady 状态；
     * 2) 开发者调试帧录制（[DebugFrameRecorder]）。
     *
     * 关键分支：
     * - 截图后端与启动时不一致 → 抛错要求重启；
     * - 帧在 `frame.use` 内处理完即释放，处理前后再次校验 [generation]。
     */
    private suspend fun runLoop(
        token: Long,
        source: FrameSource,
        startedBackend: CaptureBackend,
        jinChanSession: JinChanFrameSessionId,
    ) {
        var lastSequence = -1L
        var frameCount = 0
        var fpsWindowStart = SystemClock.elapsedRealtime()

        val stateJob = CoroutineScope(currentCoroutineContext()).launch {
            source.state.collectLatest { state ->
                when (state) {
                    CaptureState.Ready -> mutableStatus.update {
                        it.copy(captureReady = true, lastError = null)
                    }
                    CaptureState.Idle,
                    CaptureState.RequestingPermission -> mutableStatus.update {
                        it.copy(captureReady = false)
                    }
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
                    if (overlaySuspended) overlay.resumeAfterCapture()
                }

                if (frame == null) {
                    val state = source.state.value
                    when (state) {
                        CaptureState.Ready -> delay(READY_NULL_FRAME_BACKOFF_MS)
                        is CaptureState.Failed -> {
                            AppLog.w("vision", "capture failed: ${state.message}")
                            delay(PERMISSION_BACKOFF_MS)
                        }
                        CaptureState.RequestingPermission -> delay(PERMISSION_BACKOFF_MS)
                        CaptureState.Idle -> delay(IDLE_BACKOFF_MS)
                    }
                    continue
                }

                frame.use { lease ->
                    if (generation.get() != token) return@use
                    if (lease.sequence <= lastSequence) return@use
                    lastSequence = lease.sequence
                    val shadowState = jinChanShadowStatePublisher.publishFrame(
                        sessionId = jinChanSession,
                        source = lease,
                        nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        staleTimeoutNanos = JINCHAN_FRAME_STALE_TIMEOUT_NS,
                    )
                    if (shadowState != null) {
                        // C4C stops at a fail-closed decision skeleton. Production ownership joining
                        // is not available yet, and must never be repaired from a later frame/ledger.
                        val facts = JinChanSameEvidenceFacts(
                            shadowState = shadowState,
                            ownershipSnapshot = null,
                            actionContext = JinChanActionContext(
                                sessionId = shadowState.sessionId,
                                evidenceSessionId = shadowState.sessionId,
                                currentSequence = shadowState.frameSeq,
                                evidenceSequence = shadowState.frameSeq,
                                maximumSequenceAge = 0L,
                                stableState = JinChanStableState(null, JinChanStableUiState.UNKNOWN),
                            ),
                        )
                        val joined = JinChanSameEvidenceAssembler.assemble(facts)
                        if (joined is JinChanJoinResult.Assembled) {
                            FailClosedJinChanDecisionEngine.decide(JinChanDecisionInput(joined.snapshot))
                        }
                    }
                    if (generation.get() != token || jinChanSessionId != jinChanSession) return@use
                    frameCount++
                    if (config.developer.enabled) {
                        debugFrameRecorder.offer(lease, config.developer)
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - fpsWindowStart
                if (elapsed >= FPS_WINDOW_MS) {
                    val fps = frameCount * 1000f / elapsed.toFloat()
                    frameCount = 0
                    fpsWindowStart = now
                    mutableStatus.update { it.copy(fps = fps) }
                }

                // 悬浮窗只呈现状态；Clean Base 不绘制任何检测框。
                val showResult = overlay.show(
                    config = config.overlay,
                    runtimeStatus = mutableStatus.value,
                )
                mutableStatus.update {
                    it.copy(
                        overlayVisible = showResult.visible,
                        overlayBlockReason = showResult.blockReason,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.e("vision", "run loop failed: ${error.message}", error)
            mutableStatus.update {
                it.copy(
                    running = false,
                    captureReady = false,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
        } finally {
            stateJob.cancelAndJoin()
            overlay.hide()
        }
    }

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
     * 主题/悬浮窗等仍可用 preview 即时预览。
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
        const val FPS_WINDOW_MS = 1_000L
        const val PERMISSION_BACKOFF_MS = 80L
        const val READY_NULL_FRAME_BACKOFF_MS = 12L
        const val IDLE_BACKOFF_MS = 80L
        const val SHIZUKU_HEALTH_CHECK_INTERVAL_MS = 30_000L
        // Reuses the former runtime action frame-age safety bound; H3 does not tune game thresholds.
        const val JINCHAN_FRAME_STALE_TIMEOUT_NS = 1_000_000_000L
        const val SHIZUKU_NOTIFICATION_CHANNEL_ID = "shizuku_health_channel"
        const val SHIZUKU_NOTIFICATION_ID = 1001
    }
}
