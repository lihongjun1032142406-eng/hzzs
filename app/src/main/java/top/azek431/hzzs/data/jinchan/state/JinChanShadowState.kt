package top.azek431.hzzs.data.jinchan.state

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId

/** Availability of a perception field. UNKNOWN is expected until H4 supplies a producer. */
enum class ShadowFieldStatus { UNKNOWN, AVAILABLE, INVALID }

data class JinChanShadowObservation(
    val status: ShadowFieldStatus,
    val canonicalRoi: FrameRect? = null,
    val sourceRoi: FrameRect? = null,
    val reason: String? = null,
)

data class JinChanOrientation(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
)

data class JinChanShadowTiming(
    val captureTimestamp: Long,
    val bridgeNs: Long,
    val roiResolveNs: Long,
    val shadowPublishNs: Long,
    val totalShadowNs: Long,
)

/** Latest-only, immutable same-frame state. It deliberately contains no frame or pixel lease. */
data class JinChanShadowState(
    val sessionId: JinChanFrameSessionId,
    val frameSeq: Long,
    val timestampElapsedRealtimeNanos: Long,
    val orientation: JinChanOrientation,
    val shop: JinChanShadowObservation,
    val gold: JinChanShadowObservation,
    val level: JinChanShadowObservation,
    val board: JinChanShadowObservation,
    val bench: JinChanShadowObservation,
    val timing: JinChanShadowTiming,
)
