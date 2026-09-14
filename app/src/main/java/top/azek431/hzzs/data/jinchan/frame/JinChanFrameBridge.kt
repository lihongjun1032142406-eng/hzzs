package top.azek431.hzzs.data.jinchan.frame

import top.azek431.hzzs.service.capture.CapturedFrame

/** Pixel-edge coordinate in either the source or JinChan canonical coordinate space. */
data class FramePoint(val x: Double, val y: Double)

/** Axis-aligned pixel-edge rectangle. Concrete ROI registration belongs to H2, not this type. */
data class FrameRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

enum class JinChanFrameStatus {
    VALID,
    INVALID_DIMENSIONS,
    UNSUPPORTED_ROTATION,
    UNSUPPORTED_ORIENTATION,
}

sealed interface JinChanFrameBridgeResult {
    data class Valid(val frame: JinChanCanonicalFrame) : JinChanFrameBridgeResult
    data class Rejected(val status: JinChanFrameStatus) : JinChanFrameBridgeResult
}

/**
 * Read-only view of one borrowed [CapturedFrame] in JinChan's 3120 x 1440 landscape space.
 *
 * This object neither owns nor copies [pixels]. It and every coordinate operation are valid only
 * while the source frame's owner retains its lease (normally inside `CapturedFrame.use`). Multiple
 * future ROI readers may share this view during that lease. [sourceToCanonical] and
 * [canonicalToSource] are the authoritative conversion functions for all JinChan consumers.
 */
class JinChanCanonicalFrame internal constructor(
    val sourceSequence: Long,
    val sourceElapsedRealtimeNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceRotationDegrees: Int,
    val pixels: IntArray,
) {
    val canonicalWidth: Int = JinChanFrameBridge.CANONICAL_WIDTH
    val canonicalHeight: Int = JinChanFrameBridge.CANONICAL_HEIGHT

    private val orientedWidth = if (sourceRotationDegrees % 180 == 0) sourceWidth else sourceHeight
    private val orientedHeight = if (sourceRotationDegrees % 180 == 0) sourceHeight else sourceWidth

    fun sourceToCanonical(point: FramePoint): FramePoint? {
        if (!point.inBounds(sourceWidth, sourceHeight)) return null
        val oriented = when (sourceRotationDegrees) {
            0 -> point
            90 -> FramePoint(sourceHeight - point.y, point.x)
            180 -> FramePoint(sourceWidth - point.x, sourceHeight - point.y)
            270 -> FramePoint(point.y, sourceWidth - point.x)
            else -> return null
        }
        return FramePoint(
            x = oriented.x * canonicalWidth / orientedWidth,
            y = oriented.y * canonicalHeight / orientedHeight,
        )
    }

    fun canonicalToSource(point: FramePoint): FramePoint? {
        if (!point.inBounds(canonicalWidth, canonicalHeight)) return null
        val oriented = FramePoint(
            x = point.x * orientedWidth / canonicalWidth,
            y = point.y * orientedHeight / canonicalHeight,
        )
        return when (sourceRotationDegrees) {
            0 -> oriented
            90 -> FramePoint(oriented.y, sourceHeight - oriented.x)
            180 -> FramePoint(sourceWidth - oriented.x, sourceHeight - oriented.y)
            270 -> FramePoint(sourceWidth - oriented.y, oriented.x)
            else -> null
        }
    }

    fun sourceToCanonical(rect: FrameRect): FrameRect? =
        rect.mapCorners(::sourceToCanonical, sourceWidth, sourceHeight)

    fun canonicalToSource(rect: FrameRect): FrameRect? =
        rect.mapCorners(::canonicalToSource, canonicalWidth, canonicalHeight)
}

/** Creates zero-copy, fail-closed JinChan frame views from HZZS capture leases. */
object JinChanFrameBridge {
    const val CANONICAL_WIDTH = 3_120
    const val CANONICAL_HEIGHT = 1_440

    fun adapt(source: CapturedFrame): JinChanFrameBridgeResult {
        if (source.width <= 0 || source.height <= 0 || source.pixels.size != source.width * source.height) {
            return JinChanFrameBridgeResult.Rejected(JinChanFrameStatus.INVALID_DIMENSIONS)
        }
        if (source.rotationDegrees !in setOf(0, 90, 180, 270)) {
            return JinChanFrameBridgeResult.Rejected(JinChanFrameStatus.UNSUPPORTED_ROTATION)
        }
        val orientedWidth = if (source.rotationDegrees % 180 == 0) source.width else source.height
        val orientedHeight = if (source.rotationDegrees % 180 == 0) source.height else source.width
        if (orientedWidth <= orientedHeight) {
            return JinChanFrameBridgeResult.Rejected(JinChanFrameStatus.UNSUPPORTED_ORIENTATION)
        }
        return JinChanFrameBridgeResult.Valid(
            JinChanCanonicalFrame(
                sourceSequence = source.sequence,
                sourceElapsedRealtimeNanos = source.elapsedRealtimeNanos,
                sourceWidth = source.width,
                sourceHeight = source.height,
                sourceRotationDegrees = source.rotationDegrees,
                pixels = source.pixels,
            ),
        )
    }
}

private fun FramePoint.inBounds(width: Int, height: Int): Boolean =
    x.isFinite() && y.isFinite() && x >= 0.0 && y >= 0.0 && x <= width && y <= height

private fun FrameRect.mapCorners(
    transform: (FramePoint) -> FramePoint?,
    width: Int,
    height: Int,
): FrameRect? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        left < 0.0 || top < 0.0 || right > width || bottom > height || left > right || top > bottom
    ) return null
    val points = listOf(
        FramePoint(left, top),
        FramePoint(right, top),
        FramePoint(left, bottom),
        FramePoint(right, bottom),
    ).map { transform(it) ?: return null }
    return FrameRect(
        left = points.minOf(FramePoint::x),
        top = points.minOf(FramePoint::y),
        right = points.maxOf(FramePoint::x),
        bottom = points.maxOf(FramePoint::y),
    )
}
