package top.azek431.hzzs.data.jinchan.state

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.perception.BoardOccupancyObservation
import top.azek431.hzzs.data.jinchan.perception.BenchObservation
import top.azek431.hzzs.data.jinchan.perception.ExpObservation
import top.azek431.hzzs.data.jinchan.perception.GoldObservation
import top.azek431.hzzs.data.jinchan.perception.LevelObservation
import top.azek431.hzzs.data.jinchan.perception.ShopObservation

/** Availability of a perception field. UNKNOWN is expected until H4 supplies a producer. */
enum class ShadowFieldStatus { UNKNOWN, AVAILABLE, INVALID }

data class JinChanShadowObservation(
    val status: ShadowFieldStatus,
    val canonicalRoi: FrameRect? = null,
    val sourceRoi: FrameRect? = null,
    val reason: String? = null,
)

data class JinChanTypedShadowObservation<T>(
    val status: ShadowFieldStatus,
    val value: T? = null,
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
    val shop: JinChanTypedShadowObservation<ShopObservation>,
    val gold: JinChanTypedShadowObservation<GoldObservation>,
    val level: JinChanTypedShadowObservation<LevelObservation>,
    val exp: JinChanTypedShadowObservation<ExpObservation>,
    val board: JinChanTypedShadowObservation<BoardOccupancyObservation>,
    val bench: JinChanTypedShadowObservation<BenchObservation>,
    val timing: JinChanShadowTiming,
)
