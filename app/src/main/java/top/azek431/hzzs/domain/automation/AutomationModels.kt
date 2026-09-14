package top.azek431.hzzs.domain.automation

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自动操作领域模型（纯 Kotlin）。
 *
 * 职责：
 * - 描述归一化手势与动作任务
 * - 串行仲裁系统手势，防止并发 `dispatchGesture`
 * - 记录已提交 track，做空间短时去重
 *
 * 安全：本包只做规则与门控数据结构，不直接调用无障碍 API。
 * 真正注入手势由 `service.automation` 完成。
 */

/**
 * 归一化手势规格。
 *
 * 坐标为全屏 `[0, 1]`。`endX/endY` 同时为空表示点击；
 * 同时有值表示滑动。时长限制在 10ms..1000ms。
 */
data class GestureSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 30L,
    /**
     * 非滑动手势时，额外的双击延迟（毫秒）。
     * 用于海盐脚本中"press 两次间隔 60ms"的模式。
     */
    val doublePressDelayMs: Long = 0L,
) {
    init {
        require(startX in 0f..1f && startY in 0f..1f)
        require(endX == null || endX in 0f..1f)
        require(endY == null || endY in 0f..1f)
        require((endX == null) == (endY == null))
        require(durationMs in 10L..1_000L)
        require(doublePressDelayMs in 0L..2_000L)
    }
}

/**
 * 一次待执行的自动操作任务。
 *
 * @property id 动作唯一 ID（用于回执匹配）
 * @property trackId Tracker 稳定 ID；成功提交后进入账本，避免重复规划
 * @property createdAtUptimeMs / expiresAtUptimeMs 基于 `SystemClock.uptimeMillis` 的 TTL
 * @property allowedPackages 包名白名单；**空集表示不限制包名**。分发前仍须再校验前台包（若非空）
 * @property requiredWindowClassPrefixes 可选窗口类前缀约束
 * @property retryCount 已重试次数（由运行时递增）
 */
data class AutomationAction(
    val id: Long,
    val trackId: Long,
    val gesture: GestureSpec,
    val createdAtUptimeMs: Long,
    val expiresAtUptimeMs: Long,
    val allowedPackages: Set<String>,
    val requiredWindowClassPrefixes: Set<String> = emptySet(),
    val retryCount: Int = 0,
) {
    init {
        require(id > 0)
        require(trackId > 0)
        require(createdAtUptimeMs <= expiresAtUptimeMs)
        require(retryCount >= 0)
    }

    /** 空集合表示不限制前台包；否则必须命中白名单。 */
    fun matchesPackage(packageName: String): Boolean =
        allowedPackages.isEmpty() || packageName in allowedPackages

    /** 空前缀集合表示不限制窗口类；否则任一前缀匹配即可。 */
    fun matchesWindow(className: String): Boolean =
        requiredWindowClassPrefixes.isEmpty() || requiredWindowClassPrefixes.any(className::startsWith)
}

/** 手势分发终态。 */
enum class DispatchOutcome {
    /** 系统确认完成 */
    COMPLETED,
    /** 超时或系统取消 */
    CANCELLED,
    /** 前置条件失败或回执不匹配 */
    REJECTED,
    /** 到达过期时间未发出 */
    EXPIRED,
}

/**
 * 分发回执。
 *
 * 仲裁器要求回执中的 action id / trackId 与请求一致，防止串单。
 */
data class DispatchReceipt(
    val action: AutomationAction,
    val outcome: DispatchOutcome,
    val detail: String? = null,
)

/** 平台手势注入抽象；测试可替换为假实现。 */
fun interface GestureDispatcher {
    suspend fun dispatch(action: AutomationAction): DispatchReceipt
}

/**
 * 系统手势的唯一串行闸门。
 *
 * 线程：`dispatch` 在持锁期间等待系统回执或超时，
 * 保证同一时刻最多一个 `dispatchGesture` 在飞。
 *
 * @param clock 单调时钟（通常 `SystemClock.uptimeMillis`）
 * @param dispatcher 真正注入手势的平台适配
 * @param dispatchTimeoutMs 等待回执超时，超时记为 [DispatchOutcome.CANCELLED]
 */
class GestureArbiter(
    private val clock: () -> Long,
    private val dispatcher: GestureDispatcher,
    private val dispatchTimeoutMs: Long = 2_000L,
) {
    init { require(dispatchTimeoutMs in 100L..10_000L) }

    private val mutex = Mutex()

    /**
     * 串行分发动作。
     *
     * 顺序：过期检查 → 调用 dispatcher（带超时）→ 校验回执身份。
     */
    suspend fun dispatch(action: AutomationAction): DispatchReceipt = mutex.withLock {
        if (clock() >= action.expiresAtUptimeMs) {
            return@withLock DispatchReceipt(action, DispatchOutcome.EXPIRED, "动作已过期")
        }
        // 预算覆盖：双击间隔 + 每按 shell/无障碍注入余量 + 冷启动多候选试探。
        // Shell 首次可能串行试 /system/bin/input、cmd input、input；非首选 fail-fast ~380ms，
        // 但首选/末条可到 duration+900。DOUBLE_JUMP 两按 + 间隔时旧预算易误报「手势回调超时」。
        val isClick = action.gesture.endX == null && action.gesture.endY == null
        val pressCount = if (isClick && action.gesture.doublePressDelayMs > 0L) 2 else 1
        val perPressBudgetMs = action.gesture.durationMs + PER_PRESS_SHELL_OVERHEAD_MS
        val coldStartProbeMs = FAIL_FAST_CANDIDATE_BUDGET_MS * 2
        val neededMs = perPressBudgetMs * pressCount +
            action.gesture.doublePressDelayMs +
            coldStartProbeMs +
            ARBITER_SLACK_MS
        val timeoutMs = maxOf(dispatchTimeoutMs, neededMs).coerceAtMost(MAX_ARBITER_TIMEOUT_MS)
        val startedAt = clock()
        // 超时后**仍持锁**再排空一小段，避免系统手势/shell 仍在飞时放行下一单。
        val receipt = runCatching {
            coroutineScope {
                val job = async {
                    runCatching { dispatcher.dispatch(action) }
                        .getOrElse { error ->
                            DispatchReceipt(
                                action,
                                DispatchOutcome.REJECTED,
                                error.message ?: error.javaClass.simpleName,
                            )
                        }
                }
                val primary = withTimeoutOrNull(timeoutMs) { job.await() }
                if (primary != null) {
                    primary
                } else {
                    // 超时：继续持锁排空，防止叠点；排空后再记 CANCELLED。
                    val drained = withTimeoutOrNull(POST_TIMEOUT_DRAIN_MS) { job.await() }
                    if (drained == null) {
                        job.cancel()
                    }
                    drained ?: DispatchReceipt(
                        action,
                        DispatchOutcome.CANCELLED,
                        "手势回调超时 budget=${timeoutMs}ms presses=$pressCount " +
                            "dur=${action.gesture.durationMs} dbl=${action.gesture.doublePressDelayMs} " +
                            "waited=${clock() - startedAt}ms drain=${POST_TIMEOUT_DRAIN_MS}ms",
                    )
                }
            }
        }.getOrElse { error ->
            DispatchReceipt(action, DispatchOutcome.REJECTED, error.message ?: error.javaClass.simpleName)
        }
        if (receipt.action.id != action.id || receipt.action.trackId != action.trackId) {
            return@withLock DispatchReceipt(action, DispatchOutcome.REJECTED, "手势回执与请求不匹配")
        }
        // 成功路径也带上耗时，便于对照 shell 冷启动 vs 热路径。
        if (receipt.outcome == DispatchOutcome.COMPLETED && receipt.detail?.contains("arbiterMs=") != true) {
            val elapsed = clock() - startedAt
            return@withLock receipt.copy(
                detail = listOfNotNull(receipt.detail, "arbiterMs=$elapsed", "budget=${timeoutMs}ms")
                    .joinToString(" "),
            )
        }
        receipt
    }

    private companion object {
        /** 单次 shell/无障碍按压在 duration 之外的注入与进程开销预算。 */
        const val PER_PRESS_SHELL_OVERHEAD_MS = 1_600L
        /** 约两次非首选 input 候选 fail-fast（与 ShellInputGestureDispatcher 对齐量级）。 */
        const val FAIL_FAST_CANDIDATE_BUDGET_MS = 400L
        const val ARBITER_SLACK_MS = 500L
        const val MAX_ARBITER_TIMEOUT_MS = 12_000L
        /**
         * 主超时后额外持锁等待在飞注入结束的上限。
         * 防止「超时即空闲」导致系统手势与下一单叠加。
         */
        const val POST_TIMEOUT_DRAIN_MS = 1_500L
    }
}

/**
 * 动作提交账本：跨帧去重。
 *
 * - track 维度：成功完成后短冷却，**非永久**封禁（无尽跑同 track 会持续靠近，需可再动）
 * - 空间维度：同一空间键在冷却窗口内不重复规划
 *
 * 场景 / 算法切换时应 [reset]。
 */
class ActionCommitLedger {
    private val mutex = Mutex()
    /** trackId → 最近成功提交的 uptimeMs。 */
    private val completedTracks = mutableMapOf<Long, Long>()
    /** 空间去重键 → 最近成功时间；短时间内同位置不再规划。 */
    private val recentSpatialKeys = mutableMapOf<String, Long>()

    /**
     * 是否允许为该 track / 空间位置规划新动作。
     *
     * @param spatialKey 可空；为空时只检查 track
     * @param nowMs 与写入时同一时钟基准（通常 [android.os.SystemClock.uptimeMillis]）
     *
     * 实现为同步快照读：冷却表仅在 [commit]/[reset] 与本方法内受 [mutex] 保护，
     * 帧路径可直接调用，避免 `runBlocking` 嵌套。
     */
    fun canPlan(trackId: Long, spatialKey: String? = null, nowMs: Long = 0L): Boolean {
        if (trackId <= 0) return false
        // 与 commit 互斥：短临界区读+ prune，无 IO。
        // 使用 tryLock 失败时 fail-closed（视为不可规划），避免帧环阻塞。
        if (!mutex.tryLock()) return false
        return try {
            pruneLocked(nowMs)
            val trackAt = completedTracks[trackId]
            if (trackAt != null && nowMs - trackAt < TRACK_COOLDOWN_MS) return false
            if (spatialKey != null) {
                val previous = recentSpatialKeys[spatialKey]
                if (previous != null && nowMs - previous < SPATIAL_COOLDOWN_MS) return false
            }
            true
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 根据回执提交。仅 [DispatchOutcome.COMPLETED] 写入去重集合。
     * @param completedAtUptimeMs 完成时刻；默认用动作创建时间（兼容旧调用）。
     */
    suspend fun commit(
        receipt: DispatchReceipt,
        spatialKey: String? = null,
        completedAtUptimeMs: Long = receipt.action.createdAtUptimeMs,
    ) = mutex.withLock {
        if (receipt.outcome == DispatchOutcome.COMPLETED) {
            val at = completedAtUptimeMs
            completedTracks[receipt.action.trackId] = at
            if (spatialKey != null) {
                recentSpatialKeys[spatialKey] = at
            }
        }
    }

    /** 清空全部去重状态。 */
    suspend fun reset() = mutex.withLock {
        completedTracks.clear()
        recentSpatialKeys.clear()
    }

    private fun pruneLocked(nowMs: Long) {
        if (nowMs <= 0L) return
        val trackIter = completedTracks.entries.iterator()
        while (trackIter.hasNext()) {
            if (nowMs - trackIter.next().value >= TRACK_COOLDOWN_MS) trackIter.remove()
        }
        val spatialIter = recentSpatialKeys.entries.iterator()
        while (spatialIter.hasNext()) {
            if (nowMs - spatialIter.next().value >= SPATIAL_COOLDOWN_MS * 4) spatialIter.remove()
        }
    }

    private companion object {
        /**
         * 同 track 成功动作后的冷却。须短于「障碍仍在视野」的典型时长，
         * 否则一次 dispatch_ok 会让后续帧全部 ledger skip（用户观感=不再自动操作）。
         */
        const val TRACK_COOLDOWN_MS = 900L
        /** 同位置成功动作后的冷却毫秒数。 */
        const val SPATIAL_COOLDOWN_MS = 700L
    }
}
