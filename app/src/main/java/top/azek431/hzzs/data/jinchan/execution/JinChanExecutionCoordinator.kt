package top.azek431.hzzs.data.jinchan.execution

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AutomationConfig
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureArbiter
import top.azek431.hzzs.domain.automation.GestureDispatcher
import top.azek431.hzzs.service.automation.GestureDispatcherFactory

/** H6-C2 的唯一执行边界；不负责决策、坐标解析或 ledger 提交。 */
@Singleton
class JinChanExecutionCoordinator internal constructor(
    private val dispatcherFactory: GestureDispatcherFactory,
    private val clock: () -> Long,
    private val productionActionEnabled: () -> Boolean,
) {
    private data class RuntimeGate(
        val active: Boolean = false,
        val automation: AutomationConfig = AutomationConfig(),
        val backend: GestureBackend = GestureBackend.ACCESSIBILITY,
    )

    private val runtimeGate = AtomicReference(RuntimeGate())
    private val actionSequence = AtomicLong(0L)
    private val inFlightJob = AtomicReference<Job?>(null)

    /**
     * 全进程唯一 arbiter。dispatcher 只会在 [execute] 通过全部运行时门控后被调用。
     * backend 取当前 session 快照；factory 与平台 transport 不在本层重复实现。
     */
    private val arbiter = GestureArbiter(
        clock = clock,
        dispatcher = GestureDispatcher { action ->
            val gate = runtimeGate.get()
            val disabled = disabledReason(gate)
            when {
                disabled != null -> DispatchReceipt(
                    action,
                    DispatchOutcome.REJECTED,
                    "execution gate closed: $disabled",
                )
                action.allowedPackages != setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE) ->
                    DispatchReceipt(action, DispatchOutcome.REJECTED, "JinChan package invariant failed")
                else -> dispatcherFactory.dispatcher(gate.backend).dispatch(action)
            }
        },
    )

    /** 由外层 runtime 在 session 启动完成后设置已保存的安全配置与有效 backend。 */
    fun startSession(automation: AutomationConfig, backend: GestureBackend) {
        runtimeGate.set(RuntimeGate(active = true, automation = automation, backend = backend))
    }

    /** 先关闭新提交；不声称能够撤销已被系统接受的手势。 */
    fun stopSession() {
        runtimeGate.updateAndGet { it.copy(active = false) }
        cancelPending()
    }

    /** best-effort 取消等待中的协程；底层已接受的系统手势没有硬取消保证。 */
    fun cancelPending() {
        inFlightJob.getAndSet(null)?.cancel()
    }

    /**
     * 为 H6-B Resolved 补齐 H6-C1 metadata，并只通过唯一 arbiter 分发。
     * trackId 必须由调用方提供；TTL 与 actionId 由本 coordinator 唯一生成。
     */
    suspend fun execute(
        resolved: JinChanGestureResolveResult.Resolved,
        trackId: Long,
    ): JinChanExecutionResult {
        if (trackId <= 0L) return JinChanExecutionResult.InvalidTrackId

        val gate = runtimeGate.get()
        disabledReason(gate)?.let { return JinChanExecutionResult.Disabled(it) }

        val createdAt = clock()
        val actionId = nextActionId()
        val adapted = JinChanExecutionAdapter.adapt(
            JinChanExecutionAdapterInput(
                resolved = resolved,
                actionId = actionId,
                trackId = trackId,
                createdAtUptimeMs = createdAt,
                expiresAtUptimeMs = createdAt + ACTION_TTL_MS,
            ),
        )
        val ready = adapted as? JinChanExecutionAdapterResult.Ready
            ?: return JinChanExecutionResult.AdapterRejected(
                (adapted as JinChanExecutionAdapterResult.Rejected).reason,
            )

        val action = ready.automationAction
        if (action.allowedPackages != setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE)) {
            return JinChanExecutionResult.PackageInvariantRejected
        }
        if (clock() >= action.expiresAtUptimeMs) return JinChanExecutionResult.Expired(null)

        // 关键安全边界：紧邻 arbiter/factory 前再次读取 gate，避免 session/config 切换竞态。
        disabledReason(runtimeGate.get())?.let { return JinChanExecutionResult.Disabled(it) }

        val job = currentCoroutineContext()[Job]
        inFlightJob.set(job)
        val receipt = try {
            arbiter.dispatch(action)
        } finally {
            inFlightJob.compareAndSet(job, null)
        }
        return receipt.toJinChanResult()
    }

    private fun disabledReason(gate: RuntimeGate): JinChanExecutionDisabledReason? = when {
        !gate.active -> JinChanExecutionDisabledReason.RUNTIME_INACTIVE
        !gate.automation.enabled -> JinChanExecutionDisabledReason.AUTOMATION_DISABLED
        gate.automation.disclaimerAcceptedVersion < AppConfig.DISCLAIMER_VERSION ->
            JinChanExecutionDisabledReason.DISCLAIMER_REQUIRED
        !productionActionEnabled() -> JinChanExecutionDisabledReason.PRODUCTION_ACTION_DISABLED
        else -> null
    }

    private fun nextActionId(): Long {
        while (true) {
            val current = actionSequence.get()
            val next = if (current == Long.MAX_VALUE) 1L else current + 1L
            if (actionSequence.compareAndSet(current, next)) return next
        }
    }

    private fun DispatchReceipt.toJinChanResult(): JinChanExecutionResult = when (outcome) {
        DispatchOutcome.COMPLETED -> JinChanExecutionResult.Completed(this)
        DispatchOutcome.REJECTED -> JinChanExecutionResult.Rejected(this)
        DispatchOutcome.CANCELLED -> JinChanExecutionResult.Cancelled(this)
        DispatchOutcome.EXPIRED -> JinChanExecutionResult.Expired(this)
    }

    companion object {
        const val ACTION_TTL_MS = 2_000L
    }
}

enum class JinChanExecutionDisabledReason {
    RUNTIME_INACTIVE,
    AUTOMATION_DISABLED,
    DISCLAIMER_REQUIRED,
    PRODUCTION_ACTION_DISABLED,
}

sealed interface JinChanExecutionResult {
    data class Disabled(val reason: JinChanExecutionDisabledReason) : JinChanExecutionResult
    data object InvalidTrackId : JinChanExecutionResult
    data class AdapterRejected(val reason: JinChanExecutionAdapterRejectionReason) : JinChanExecutionResult
    data object PackageInvariantRejected : JinChanExecutionResult
    data class Completed(val receipt: DispatchReceipt) : JinChanExecutionResult
    data class Rejected(val receipt: DispatchReceipt) : JinChanExecutionResult
    data class Cancelled(val receipt: DispatchReceipt) : JinChanExecutionResult
    data class Expired(val receipt: DispatchReceipt?) : JinChanExecutionResult
}

@Module
@InstallIn(SingletonComponent::class)
object JinChanExecutionModule {
    @Provides
    @Singleton
    fun provideJinChanExecutionCoordinator(
        dispatcherFactory: GestureDispatcherFactory,
    ): JinChanExecutionCoordinator = JinChanExecutionCoordinator(
        dispatcherFactory = dispatcherFactory,
        clock = SystemClock::uptimeMillis,
        productionActionEnabled = { AppConfig.ACTION_ENABLED },
    )
}
