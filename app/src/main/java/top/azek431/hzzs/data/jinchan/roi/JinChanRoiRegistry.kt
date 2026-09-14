package top.azek431.hzzs.data.jinchan.roi

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameBridge

/** Strongly typed identifiers for the frozen JinChanAI ROI baseline. */
enum class JinChanRoiId {
    STAGE,
    LEVEL_EXP,
    GOLD,
    BOARD,
    SHOP,
    PLAYER_LIST,
    PANEL,
    SPECIAL,
    BENCH,
    PLAY_BTN,
}

/** Immutable normalized rectangle. Invalid geometry is rejected rather than clamped. */
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite())
        require(x >= 0.0 && y >= 0.0 && width > 0.0 && height > 0.0)
        require(x <= 1.0 && y <= 1.0 && width <= 1.0 && height <= 1.0)
        require(x + width <= 1.0 && y + height <= 1.0)
    }
}

/**
 * The sole authority for normalized JinChan ROIs.
 *
 * Version [VERSION] is migrated verbatim from the frozen JinChanAI normalized ROI baseline; these
 * values are not HZZS algorithm data. This registry contains no frames or pixels and performs no
 * recognition. H1 [JinChanCanonicalFrame] remains the sole source/canonical rotation and scaling
 * authority.
 */
object JinChanRoiRegistry {
    const val VERSION = "JINCHAN_ROI_V1"

    private val definitions = listOf(
        JinChanRoiId.STAGE to NormalizedRect(0.30, 0.00, 0.40, 0.10),
        JinChanRoiId.LEVEL_EXP to NormalizedRect(0.00, 0.68, 0.16, 0.32),
        JinChanRoiId.GOLD to NormalizedRect(0.86, 0.70, 0.14, 0.30),
        JinChanRoiId.BOARD to NormalizedRect(0.17, 0.10, 0.66, 0.70),
        JinChanRoiId.SHOP to NormalizedRect(0.15, 0.55, 0.68, 0.40),
        JinChanRoiId.PLAYER_LIST to NormalizedRect(0.84, 0.04, 0.16, 0.76),
        JinChanRoiId.PANEL to NormalizedRect(0.68, 0.10, 0.30, 0.72),
        JinChanRoiId.SPECIAL to NormalizedRect(0.18, 0.12, 0.64, 0.72),
        JinChanRoiId.BENCH to NormalizedRect(0.20, 0.78, 0.62, 0.20),
        JinChanRoiId.PLAY_BTN to NormalizedRect(0.82, 0.72, 0.16, 0.26),
    )

    init {
        require(definitions.map { it.first }.distinct().size == definitions.size) {
            "Duplicate JinChan ROI id"
        }
        require(definitions.map { it.first }.toSet() == JinChanRoiId.entries.toSet()) {
            "Missing JinChan ROI definition"
        }
    }

    private val byId: Map<JinChanRoiId, NormalizedRect> = definitions.toMap()

    /** IDs in stable baseline order. */
    val ids: List<JinChanRoiId> = definitions.map { it.first }

    fun getNormalized(id: JinChanRoiId): NormalizedRect =
        checkNotNull(byId[id]) { "Missing JinChan ROI: $id" }

    fun resolveCanonical(id: JinChanRoiId): FrameRect {
        val roi = getNormalized(id)
        return FrameRect(
            left = roi.x * JinChanFrameBridge.CANONICAL_WIDTH,
            top = roi.y * JinChanFrameBridge.CANONICAL_HEIGHT,
            right = (roi.x + roi.width) * JinChanFrameBridge.CANONICAL_WIDTH,
            bottom = (roi.y + roi.height) * JinChanFrameBridge.CANONICAL_HEIGHT,
        )
    }

    /** Delegates all canonical-to-source rotation/scaling to the frozen H1 coordinate layer. */
    fun resolveSource(id: JinChanRoiId, canonicalFrame: JinChanCanonicalFrame): FrameRect? =
        canonicalFrame.canonicalToSource(resolveCanonical(id))
}
