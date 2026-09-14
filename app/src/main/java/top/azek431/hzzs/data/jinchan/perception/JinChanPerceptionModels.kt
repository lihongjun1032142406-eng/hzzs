package top.azek431.hzzs.data.jinchan.perception

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame

enum class JinChanOcrStatus { VALID, EMPTY, NOT_AVAILABLE, PARTIAL, ERROR }

data class JinChanOcrResult(
    val status: JinChanOcrStatus,
    val text: String? = null,
    val confidence: Double? = null,
)

/** OCR boundary. Implementations may copy only [roi], and must not retain the borrowed frame. */
fun interface JinChanOcrReader {
    fun read(frame: JinChanCanonicalFrame, roi: FrameRect): JinChanOcrResult
}

object UnavailableJinChanOcrReader : JinChanOcrReader {
    override fun read(frame: JinChanCanonicalFrame, roi: FrameRect) =
        JinChanOcrResult(JinChanOcrStatus.NOT_AVAILABLE)
}

enum class HudObservationStatus { UNAVAILABLE, NOT_VISIBLE, INVALID, AVAILABLE }
enum class GoldTrust { UNAVAILABLE, RAW_VALID, UNTRUSTED, TRUSTED }
enum class GoldPurity { UNKNOWN, CLEAN, SUSPECT }

data class LevelObservation(val status: HudObservationStatus, val value: Int? = null, val reason: String? = null)
data class ExpObservation(val status: HudObservationStatus, val current: Int? = null, val max: Int? = null, val reason: String? = null)
data class GoldObservation(
    val status: HudObservationStatus,
    val trust: GoldTrust,
    val value: Int? = null,
    val rawValue: Int? = null,
    val purity: GoldPurity = GoldPurity.UNKNOWN,
    val reason: String? = null,
)

data class JinChanHudObservation(val frameSeq: Long, val level: LevelObservation, val exp: ExpObservation, val gold: GoldObservation)

enum class ShopContentType { HERO_CARD, EMPTY, NON_HERO, UNKNOWN }
enum class ShopObservationStatus { UNAVAILABLE, AVAILABLE }
enum class JinChanStableUiState { SHOP_OPEN, BOARD_OR_COMBAT, OTHER, UNKNOWN }
data class JinChanStableState(val inGame: Boolean?, val uiState: JinChanStableUiState)
data class ResolvedHero(val canonicalId: String, val canonicalName: String)

/** Exact canonical/alias lookup only. A null result is deliberately unmatched. */
fun interface JinChanHeroIdentityResolver { fun resolveExact(normalizedName: String): ResolvedHero? }

data class ShopSlotObservation(
    val position: Int,
    val contentType: ShopContentType,
    val normalizedName: String? = null,
    val hero: ResolvedHero? = null,
    val matched: Boolean = false,
)

data class ShopObservation(
    val status: ShopObservationStatus,
    val frameSeq: Long,
    val slots: List<ShopSlotObservation> = emptyList(),
    val reason: String? = null,
)

