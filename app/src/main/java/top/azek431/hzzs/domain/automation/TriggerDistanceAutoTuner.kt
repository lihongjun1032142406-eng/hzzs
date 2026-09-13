package top.azek431.hzzs.domain.automation

import top.azek431.hzzs.core.model.SceneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 触发距离（玩家宽度倍数）的运行时自调。
 *
 * - **升**：`no_candidate` 且存在可行动障碍、`nearGap` 略大于当前触发带时，向「刚好够到」缓升。
 * - **降**：成功规划且间隙远小于触发带时，向用户基线缓降（不低于基线）。
 * - 有冷却与单步上限，避免帧路径抖动；不写磁盘（由调用方节流落盘）。
 *
 * 范围与 [top.azek431.hzzs.core.preferences.validated] 中触发距离 clamp 一致：0.5…8。
 */
class TriggerDistanceAutoTuner(
    private val minMultiplier: Float = 0.5f,
    private val maxMultiplier: Float = 8f,
    private val minIntervalMs: Long = 1_500L,
    private val maxStepUp: Float = 0.45f,
    private val maxStepDown: Float = 0.20f,
) {
    private val adapted = ConcurrentHashMap<SceneId, Float>()
    private val lastAdjustAtMs = AtomicLong(0L)
    private val lastPersistHintAtMs = AtomicLong(0L)

    /** 当前生效倍数：已自调值优先，否则 [baseline]。 */
    fun effective(scene: SceneId, baseline: Float): Float {
        val base = baseline.coerceIn(minMultiplier, maxMultiplier)
        return adapted[scene]?.coerceIn(minMultiplier, maxMultiplier) ?: base
    }

    /** 配置流变更时同步：用户改滑条则重置该赛季自调缓存。 */
    fun onBaselineChanged(scene: SceneId, baseline: Float) {
        val base = baseline.coerceIn(minMultiplier, maxMultiplier)
        val current = adapted[scene] ?: return
        // 用户明显改动基线（>0.08）时丢弃自调，避免与手动冲突。
        if (kotlin.math.abs(current - base) > 0.08f && base != current) {
            // 若基线变大超过自调值，直接用基线；若用户调小，也以用户为准。
            adapted.remove(scene)
        }
    }

    fun clear(scene: SceneId? = null) {
        if (scene == null) adapted.clear() else adapted.remove(scene)
    }

    /**
     * @return 新的倍数；无需调整时 null
     */
    fun onNoCandidate(
        scene: SceneId,
        baseline: Float,
        playerWidth: Float,
        nearGap: Float?,
        nowMs: Long,
    ): Float? {
        if (nearGap == null || !nearGap.isFinite() || playerWidth <= 0.01f) return null
        if (nearGap <= 0f) return null
        val base = baseline.coerceIn(minMultiplier, maxMultiplier)
        val current = effective(scene, base)
        val currentDist = current * playerWidth
        // 间隙已在触发带内：被其它门控挡住，不升距离。
        if (nearGap <= currentDist + 0.005f) return null
        val needed = (nearGap / playerWidth) * 1.10f
        if (needed <= current + 0.04f) return null
        if (!cooldownOk(nowMs)) return null
        val stepped = min(current + maxStepUp, needed)
        val next = stepped.coerceIn(minMultiplier, maxMultiplier)
        if (next <= current + 0.02f) return null
        adapted[scene] = next
        lastAdjustAtMs.set(nowMs)
        return next
    }

    /**
     * 规划成功后，若实际间隙远小于触发带，向 [baseline] 缓降（不低于基线）。
     * @return 新的倍数；无需调整时 null
     */
    fun onPlanSuccess(
        scene: SceneId,
        baseline: Float,
        playerWidth: Float,
        gap: Float,
        nowMs: Long,
    ): Float? {
        if (!gap.isFinite() || playerWidth <= 0.01f) return null
        val base = baseline.coerceIn(minMultiplier, maxMultiplier)
        val current = effective(scene, base)
        if (current <= base + 0.05f) {
            // 已贴近用户基线，清缓存
            if (adapted.containsKey(scene) && current <= base + 0.02f) {
                adapted.remove(scene)
            }
            return null
        }
        val currentDist = current * playerWidth
        // 仅当用了不到 55% 的触发带时才收缩
        if (gap > currentDist * 0.55f) return null
        if (!cooldownOk(nowMs)) return null
        val next = max(base, current - maxStepDown).coerceIn(minMultiplier, maxMultiplier)
        if (next >= current - 0.02f) return null
        if (next <= base + 0.02f) {
            adapted.remove(scene)
        } else {
            adapted[scene] = next
        }
        lastAdjustAtMs.set(nowMs)
        return if (next <= base + 0.02f) base else next
    }

    /** 落盘节流：同一自调结果至少间隔 [persistIntervalMs] 再写盘。 */
    fun shouldPersist(nowMs: Long, persistIntervalMs: Long = 4_000L): Boolean {
        val last = lastPersistHintAtMs.get()
        if (nowMs - last < persistIntervalMs) return false
        lastPersistHintAtMs.set(nowMs)
        return true
    }

    fun snapshot(): Map<SceneId, Float> = adapted.toMap()

    private fun cooldownOk(nowMs: Long): Boolean {
        val last = lastAdjustAtMs.get()
        // 0 表示尚未发生过调整；第一次合法调整不应被启动后的冷却窗口误挡。
        return last == 0L || nowMs - last >= minIntervalMs
    }
}
