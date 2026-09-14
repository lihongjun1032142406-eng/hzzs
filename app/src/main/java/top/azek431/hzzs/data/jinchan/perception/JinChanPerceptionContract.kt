package top.azek431.hzzs.data.jinchan.perception

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId

/** Unambiguous outcome shared by every JinChan perception producer. */
enum class JinChanPerceptionStatus { AVAILABLE, UNKNOWN, INVALID, NOT_APPLICABLE }

/** Pixel-free identity that ties an observation to the capture lease that produced it. */
data class JinChanSourceFrame(
    val sessionId: JinChanFrameSessionId,
    val frameSeq: Long,
    val timestampElapsedRealtimeNanos: Long,
)

/** A producer result; only [Available] can carry a validated payload. */
sealed interface JinChanPerceptionResult<out T> {
    val source: JinChanSourceFrame
    val status: JinChanPerceptionStatus

    data class Available<T>(
        val payload: T,
        val confidence: Double,
        override val source: JinChanSourceFrame,
    ) : JinChanPerceptionResult<T> {
        init { require(confidence.isFinite() && confidence in 0.0..1.0) }
        override val status = JinChanPerceptionStatus.AVAILABLE
    }

    data class Unknown(override val source: JinChanSourceFrame, val reason: String) : JinChanPerceptionResult<Nothing> {
        override val status = JinChanPerceptionStatus.UNKNOWN
    }

    data class Invalid(override val source: JinChanSourceFrame, val reason: String) : JinChanPerceptionResult<Nothing> {
        override val status = JinChanPerceptionStatus.INVALID
    }

    data class NotApplicable(override val source: JinChanSourceFrame, val reason: String) : JinChanPerceptionResult<Nothing> {
        override val status = JinChanPerceptionStatus.NOT_APPLICABLE
    }
}

data class LevelObservation(val level: Int)
data class ExpObservation(val current: Int, val max: Int)
data class GoldObservation(val gold: Int)

enum class JinChanShopSlotStatus { AVAILABLE, UNKNOWN, EMPTY }
data class JinChanShopSlot(val index: Int, val status: JinChanShopSlotStatus, val heroKey: String? = null) {
    init {
        require(index >= 0)
        require((status == JinChanShopSlotStatus.AVAILABLE) == !heroKey.isNullOrBlank()) {
            "Only an exact-resolved AVAILABLE slot may carry heroKey"
        }
    }
}
data class ShopObservation(val slots: List<JinChanShopSlot>)

data class JinChanHudResult(
    val level: JinChanPerceptionResult<LevelObservation>,
    val exp: JinChanPerceptionResult<ExpObservation>,
    val gold: JinChanPerceptionResult<GoldObservation>,
)

/** Frozen scene-state input. Null means that H4-A has no authority to decide the gate. */
data class JinChanShopGateInput(val inGame: Boolean?, val uiState: String?)

fun interface JinChanHudProducer {
    fun produce(
        frame: JinChanCanonicalFrame,
        source: JinChanSourceFrame,
        levelExpRoi: FrameRect,
        goldRoi: FrameRect,
    ): JinChanHudResult
}

fun interface JinChanShopProducer {
    fun produce(frame: JinChanCanonicalFrame, source: JinChanSourceFrame, shopRoi: FrameRect): JinChanPerceptionResult<ShopObservation>
}

fun interface JinChanShopGate {
    fun current(frame: JinChanCanonicalFrame, source: JinChanSourceFrame): JinChanShopGateInput
}
