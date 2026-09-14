package top.azek431.hzzs.data.jinchan.state

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridgeResult
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameRuntime
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameRuntimeStatus
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiId
import top.azek431.hzzs.data.jinchan.roi.JinChanRoiRegistry
import top.azek431.hzzs.data.jinchan.perception.*
import top.azek431.hzzs.service.capture.CapturedFrame

/** Session-scoped same-frame H3 publisher. Calls are synchronous and must stay inside frame.use. */
@Singleton
class JinChanShadowStatePublisher @Inject constructor() {
    private val runtime = JinChanFrameRuntime()
    private val mutableState = MutableStateFlow<JinChanShadowState?>(null)
    private var activeSessionId: JinChanFrameSessionId? = null
    val state: StateFlow<JinChanShadowState?> = mutableState.asStateFlow()

    @Synchronized
    fun startSession(): JinChanFrameSessionId = runtime.startSession().also {
        activeSessionId = it
        mutableState.value = null
    }

    @Synchronized
    fun stopSession(sessionId: JinChanFrameSessionId) {
        if (sessionId != activeSessionId) return
        runtime.stopSession(sessionId)
        activeSessionId = null
        mutableState.value = null
    }

    /** Adapts exactly once and retains only immutable metadata after this call returns. */
    @Synchronized
    fun publishFrame(
        sessionId: JinChanFrameSessionId,
        source: CapturedFrame,
        nowElapsedRealtimeNanos: Long,
        staleTimeoutNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
        roiResolver: (JinChanRoiId, JinChanCanonicalFrame) -> Pair<top.azek431.hzzs.data.jinchan.frame.FrameRect, top.azek431.hzzs.data.jinchan.frame.FrameRect?> =
            { id, frame -> JinChanRoiRegistry.resolveCanonical(id) to JinChanRoiRegistry.resolveSource(id, frame) },
        ocrReader: JinChanOcrReader = UnavailableJinChanOcrReader,
        stableState: JinChanStableState = JinChanStableState(null, JinChanStableUiState.UNKNOWN),
        heroResolver: JinChanHeroIdentityResolver? = null,
    ): JinChanShadowState? {
        val totalStart = nanoTime()
        val bridgeStart = nanoTime()
        val canonical = when (val result = JinChanFrameBridge.adapt(source)) {
            is JinChanFrameBridgeResult.Valid -> result.frame
            is JinChanFrameBridgeResult.Rejected -> return null
        }
        val bridgeNs = elapsed(bridgeStart, nanoTime())
        val accepted = runtime.acceptCanonical(sessionId, canonical, nowElapsedRealtimeNanos, staleTimeoutNanos)
        if (accepted.status != JinChanFrameRuntimeStatus.VALID) return null
        val metadata = requireNotNull(accepted.metadata)

        val roiStart = nanoTime()
        val observations = REQUIRED_ROIS.associateWith { id ->
            val (canonicalRoi, sourceRoi) = roiResolver(id, canonical)
            if (sourceRoi == null) {
                JinChanShadowObservation(ShadowFieldStatus.INVALID, canonicalRoi, reason = "ROI_${id.name}_UNMAPPABLE")
            } else {
                JinChanShadowObservation(ShadowFieldStatus.UNKNOWN, canonicalRoi, sourceRoi)
            }
        }
        val roiNs = elapsed(roiStart, nanoTime())
        val hud = JinChanHudPerception.observe(canonical, ocrReader)
        val shopValue = JinChanShopPerception.observe(canonical, stableState, ocrReader, heroResolver)
        fun <T> typed(id: JinChanRoiId, value: T?, available: Boolean, reason: String? = null): JinChanTypedShadowObservation<T> {
            val roi = observations.getValue(id)
            if (roi.status == ShadowFieldStatus.INVALID) return JinChanTypedShadowObservation(ShadowFieldStatus.INVALID, canonicalRoi = roi.canonicalRoi, reason = roi.reason)
            return JinChanTypedShadowObservation(if (available) ShadowFieldStatus.AVAILABLE else ShadowFieldStatus.UNKNOWN, value, roi.canonicalRoi, roi.sourceRoi, reason)
        }
        val publishStart = nanoTime()
        // Building the immutable snapshot is the measurable publication work; StateFlow assignment
        // itself is deliberately performed exactly once so observers never see a partial timing.
        val publishNs = elapsed(publishStart, nanoTime())
        val state = JinChanShadowState(
            sessionId = metadata.sessionId,
            frameSeq = metadata.frameId,
            timestampElapsedRealtimeNanos = metadata.timestampElapsedRealtimeNanos,
            orientation = JinChanOrientation(metadata.sourceWidth, metadata.sourceHeight, metadata.sourceRotationDegrees, metadata.canonicalWidth, metadata.canonicalHeight),
            shop = typed(JinChanRoiId.SHOP, shopValue.takeIf { it.status == ShopObservationStatus.AVAILABLE }, shopValue.status == ShopObservationStatus.AVAILABLE, shopValue.reason),
            gold = typed(JinChanRoiId.GOLD, hud.gold, hud.gold.status != HudObservationStatus.UNAVAILABLE && hud.gold.status != HudObservationStatus.NOT_VISIBLE, hud.gold.reason),
            level = typed(JinChanRoiId.LEVEL_EXP, hud.level, hud.level.status == HudObservationStatus.AVAILABLE, hud.level.reason),
            exp = typed(JinChanRoiId.LEVEL_EXP, hud.exp, hud.exp.status == HudObservationStatus.AVAILABLE, hud.exp.reason),
            board = observations.getValue(JinChanRoiId.BOARD),
            bench = observations.getValue(JinChanRoiId.BENCH),
            timing = JinChanShadowTiming(
                source.elapsedRealtimeNanos,
                bridgeNs,
                roiNs,
                publishNs,
                elapsed(totalStart, nanoTime()),
            ),
        )
        mutableState.value = state
        AppLog.d("jinchan-shadow", "session=${sessionId.value} frame=${source.sequence} fields=${observations.values.joinToString { it.status.name }}")
        return state
    }

    private fun elapsed(start: Long, end: Long): Long = (end - start).coerceAtLeast(0L)

    private companion object {
        val REQUIRED_ROIS = listOf(JinChanRoiId.SHOP, JinChanRoiId.GOLD, JinChanRoiId.LEVEL_EXP, JinChanRoiId.BOARD, JinChanRoiId.BENCH)
    }
}
