package top.azek431.hzzs.data.jinchan.perception

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame

enum class BoardSnapshotDecision { SET, HOLD, CLEAR }
enum class BoardObservationStatus { AVAILABLE, UNAVAILABLE, INVALID }

data class BoardCellObservation(
    val row: Int,
    val col: Int,
    val cellId: String,
    val occupied: Boolean?,
    val probability: Double?,
)

data class BoardOccupancyTiming(val totalNs: Long)

data class BoardOccupancyObservation(
    val status: BoardObservationStatus,
    val frameSeq: Long,
    val decision: BoardSnapshotDecision,
    val reason: String,
    val geometry: String = JinChanBoardV8Model.GEOMETRY,
    val model: String = JinChanBoardV8Model.MODEL,
    val occupiedCount: Int = 0,
    val cells: List<BoardCellObservation> = emptyList(),
    val timing: BoardOccupancyTiming? = null,
)

data class BoardSceneEvidence(val combatRatio: Double?, val boardDarkRatio: Double?, val shopSlotRatio: Double?)
data class BoardSceneGateResult(val allowed: Boolean, val reason: String)

object JinChanBoardSceneTruthGate {
    const val COMBAT_MIN = 0.10
    const val PANEL_DARK_MIN = 0.25
    const val PLANNING_SHOP_MIN = 0.03

    fun evaluate(e: BoardSceneEvidence): BoardSceneGateResult {
        val values = listOf(e.combatRatio, e.boardDarkRatio, e.shopSlotRatio)
        if (values.any { it == null || !it.isFinite() }) return BoardSceneGateResult(false, "EVIDENCE_MISSING")
        if (e.combatRatio!! >= COMBAT_MIN) return BoardSceneGateResult(false, "COMBAT_LIKE")
        if (e.boardDarkRatio!! >= PANEL_DARK_MIN) return BoardSceneGateResult(false, "PANEL_LIKE")
        return BoardSceneGateResult(true, if (e.shopSlotRatio!! < PLANNING_SHOP_MIN) "PLANNING_CANDIDATE_LOW_SHOP_SIGNAL" else "PLANNING_CANDIDATE")
    }

    fun decide(inGame: Boolean?, sceneAllowed: Boolean, producerAvailable: Boolean): BoardSnapshotDecision = when {
        inGame == false -> BoardSnapshotDecision.CLEAR
        !sceneAllowed -> BoardSnapshotDecision.HOLD
        producerAvailable -> BoardSnapshotDecision.SET
        else -> BoardSnapshotDecision.HOLD
    }
}

/** Frozen 4x7 logical geometry, ordered R1C1..R1C7 through R4C7. */
object JinChanBoardGeometry {
    const val ROWS = 4
    const val COLS = 7
    const val CELL_WIDTH = 110
    const val CELL_HEIGHT = 80
    const val ODD_ROWS_X0 = 1066.6153132070312
    const val EVEN_ROWS_X0 = 968.20135319375
    const val Y0 = 700.1169451667137
    const val X_PITCH = 196.8279200265625
    const val Y_PITCH = 124.39295845641749

    data class Anchor(val row: Int, val col: Int, val cellId: String, val x: Int, val y: Int)

    val anchors: List<Anchor> = buildList {
        for (row in 1..ROWS) for (col in 1..COLS) {
            val x0 = if (row % 2 == 1) ODD_ROWS_X0 else EVEN_ROWS_X0
            val cx = jsRound(x0 + (col - 1) * X_PITCH)
            val cy = jsRound(Y0 + (row - 1) * Y_PITCH)
            add(Anchor(row, col, "R${row}C$col", cx - 55, cy - 40))
        }
    }

    private fun jsRound(value: Double) = floor(value + .5).toInt()
}

/** Same-frame, pixel-borrowing H4-B producer. No pixel data escapes [evaluate]. */
object JinChanBoardOccupancyProducer {
    const val BANNER_X = 850
    const val BANNER_Y = 830
    const val BANNER_WIDTH = 1450
    const val BANNER_HEIGHT = 15
    const val BANNER_EDGE_MIN = 100.0
    const val BANNER_COVERAGE_MIN = .70
    const val BANNER_PIXEL_EDGE_MIN = 30.0
    const val DAMAGE_X = 2760
    const val DAMAGE_Y = 150
    const val DAMAGE_WIDTH = 360
    const val DAMAGE_HEIGHT = 900
    const val DAMAGE_MEAN_MAX = 120.0
    const val DAMAGE_STD_MAX = 45.0

    fun evaluate(frame: JinChanCanonicalFrame, stable: JinChanStableState): BoardOccupancyObservation {
        val startedNs = System.nanoTime()
        val unavailable = { reason: String -> BoardOccupancyObservation(BoardObservationStatus.UNAVAILABLE, frame.sourceSequence, BoardSnapshotDecision.HOLD, reason) }
        if (stable.inGame != true || stable.uiState != JinChanStableUiState.BOARD_OR_COMBAT) return unavailable("SCENE_NOT_ELIGIBLE")
        if (frame.sourceWidth != 3120 || frame.sourceHeight != 1440 || frame.sourceRotationDegrees != 0) return unavailable("UNSUPPORTED_FRAME_DIMENSIONS")
        return try {
            val evidence = sceneEvidence(frame.pixels, frame.sourceWidth, frame.sourceHeight)
            val gate = JinChanBoardSceneTruthGate.evaluate(evidence)
            if (!gate.allowed) return unavailable(gate.reason)
            val banner = bannerDetected(frame.pixels, frame.sourceWidth)
            if (banner == null) return unavailable("BANNER_GUARD_FAILED")
            if (banner) return unavailable("BOARD_BANNER_LIKE")
            val damage = damagePanelDetected(frame.pixels, frame.sourceWidth)
            if (damage == null) return unavailable("DAMAGE_GUARD_FAILED")
            if (damage) return unavailable("DAMAGE_PANEL_LIKE")
            val cells = JinChanBoardGeometry.anchors.map { anchor ->
                val features = FrozenV8Extractor.extract(frame.pixels, frame.sourceWidth, anchor.x, anchor.y)
                val probability = JinChanBoardV8Model.probability(features)
                BoardCellObservation(anchor.row, anchor.col, anchor.cellId, JinChanBoardV8Model.occupied(probability), probability)
            }
            if (cells.any { it.probability == null }) return BoardOccupancyObservation(BoardObservationStatus.INVALID, frame.sourceSequence, BoardSnapshotDecision.HOLD, "MALFORMED_MODEL_INPUT")
            BoardOccupancyObservation(
                BoardObservationStatus.AVAILABLE,
                frame.sourceSequence,
                BoardSnapshotDecision.SET,
                gate.reason,
                occupiedCount = cells.count { it.occupied == true },
                cells = cells,
                timing = BoardOccupancyTiming((System.nanoTime() - startedNs).coerceAtLeast(0L)),
            )
        } catch (_: RuntimeException) {
            BoardOccupancyObservation(BoardObservationStatus.INVALID, frame.sourceSequence, BoardSnapshotDecision.HOLD, "PRODUCER_EXCEPTION")
        }
    }

    fun sceneEvidence(pixels: IntArray, width: Int, height: Int): BoardSceneEvidence {
        if (width != 3120 || height != 1440 || pixels.size != width * height) return BoardSceneEvidence(null, null, null)
        val bx0 = floor(.17 * width).toInt(); val by0 = floor(.10 * height).toInt()
        val bx1 = floor((.17 + .66) * width).toInt(); val by1 = floor((.10 + .70) * height).toInt()
        var combat = 0L; var dark = 0L
        for (y in by0 until by1) for (x in bx0 until bx1) {
            val p = pixels[y * width + x]
            val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
            if (r in 200..255 && g in 200..255 && b in 150..255) combat++
            if (r in 0..80 && g in 0..80 && b in 0..110) dark++
        }
        val boardArea = ((bx1 - bx0).toLong() * (by1 - by0))
        val sx0 = floor(.15 * width).toInt(); val sy0 = floor(.55 * height).toInt()
        val sx1 = floor((.15 + .68) * width).toInt(); val sy1 = floor((.55 + .40) * height).toInt()
        var shop = 0L
        for (y in sy0 until sy1) for (x in sx0 until sx1) {
            val p = pixels[y * width + x]
            val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
            if (r in 40..100 && g in 40..100 && b in 60..120) shop++
        }
        val shopArea = ((sx1 - sx0).toLong() * (sy1 - sy0))
        return BoardSceneEvidence(
            combatRatio = combat.toDouble() / boardArea,
            boardDarkRatio = dark.toDouble() / boardArea,
            shopSlotRatio = shop.toDouble() / shopArea,
        )
    }

    fun bannerDetected(pixels: IntArray, width: Int): Boolean? {
        if (width != 3120 || pixels.size != width * 1440) return null
        var peak = Double.NEGATIVE_INFINITY
        var peakEdges = 0
        for (localY in 0 until BANNER_HEIGHT) {
            val ym = reflect101(localY - 1, BANNER_HEIGHT)
            val yp = reflect101(localY + 1, BANNER_HEIGHT)
            var sum = 0L
            var edges = 0
            for (x in 0 until BANNER_WIDTH) {
                val xm = reflect101(x - 1, BANNER_WIDTH)
                val xp = reflect101(x + 1, BANNER_WIDTH)
                fun sample(localX: Int, localY: Int) = gray(pixels[(BANNER_Y + localY) * width + BANNER_X + localX])
                val gy = -sample(xm, ym) - 2 * sample(x, ym) - sample(xp, ym) +
                    sample(xm, yp) + 2 * sample(x, yp) + sample(xp, yp)
                val absolute = kotlin.math.abs(gy).coerceAtMost(255)
                sum += absolute
                if (absolute > BANNER_PIXEL_EDGE_MIN) edges++
            }
            val mean = sum.toDouble() / BANNER_WIDTH
            if (mean > peak) { peak = mean; peakEdges = edges }
        }
        return peak >= BANNER_EDGE_MIN && peakEdges.toDouble() / BANNER_WIDTH >= BANNER_COVERAGE_MIN
    }

    fun damagePanelDetected(pixels: IntArray, width: Int): Boolean? {
        if (width != 3120 || pixels.size != width * 1440) return null
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in DAMAGE_Y until DAMAGE_Y + DAMAGE_HEIGHT) for (x in DAMAGE_X until DAMAGE_X + DAMAGE_WIDTH) {
            val v = gray(pixels[y * width + x]).toDouble(); sum += v; sumSq += v * v; n++
        }
        val mean = sum / n
        val std = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        return mean < DAMAGE_MEAN_MAX && std < DAMAGE_STD_MAX
    }

    internal fun gray(argb: Int): Int {
        val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
        return (r * 4899 + g * 9617 + b * 1868 + 8192) shr 14
    }

    internal fun reflect101(index: Int, length: Int): Int = when {
        index < 0 -> -index
        index >= length -> length * 2 - index - 2
        else -> index
    }
}

private object FrozenV8Extractor {
    private data class Region(val name: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private val regions = listOf(
        Region("all", 5, 5, 105, 79), Region("mid", 20, 15, 90, 60), Region("lower", 15, 30, 95, 79),
        Region("center", 30, 25, 80, 75), Region("feet", 30, 45, 80, 79), Region("corefeet", 38, 50, 72, 79),
    )

    fun extract(pixels: IntArray, stride: Int, left: Int, top: Int): DoubleArray {
        val size = 110 * 80
        val gray = DoubleArray(size)
        for (y in 0 until 80) for (x in 0 until 110) gray[y * 110 + x] = JinChanBoardOccupancyProducer.gray(pixels[(top + y) * stride + left + x]).toDouble()
        val gx = DoubleArray(size); val gy = DoubleArray(size); val mag = DoubleArray(size); val lap = DoubleArray(size)
        fun at(x: Int, y: Int) = gray[JinChanBoardOccupancyProducer.reflect101(y, 80) * 110 + JinChanBoardOccupancyProducer.reflect101(x, 110)]
        for (y in 0 until 80) for (x in 0 until 110) {
            val i = y * 110 + x
            gx[i] = -at(x - 1, y - 1) + at(x + 1, y - 1) - 2 * at(x - 1, y) + 2 * at(x + 1, y) - at(x - 1, y + 1) + at(x + 1, y + 1)
            gy[i] = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) + at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
            mag[i] = hypot(gx[i], gy[i])
            lap[i] = 2 * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) - 8 * at(x, y)
        }
        val values = HashMap<String, Double>(120)
        regions.forEach { r ->
            fun slice(source: DoubleArray, absolute: Boolean = false): DoubleArray = DoubleArray((r.x2-r.x1)*(r.y2-r.y1)).also { out ->
                var i=0; for(y in r.y1 until r.y2) for(x in r.x1 until r.x2) out[i++] = if (absolute) kotlin.math.abs(source[y*110+x]) else source[y*110+x]
            }
            val gr=slice(gray); val mr=slice(mag); val gxr=slice(gx,true); val gyr=slice(gy,true); val lr=slice(lap,true)
            values["${r.name}_std"] = std(gr)
            listOf(50,75,85,90,95).forEach { values["${r.name}_g$it"] = percentile(mr,it) }
            listOf(15,25,40,60).forEach { t -> values["${r.name}_e$t"] = mr.count { it > t }.toDouble()/mr.size }
            values["${r.name}_gx75"]=percentile(gxr,75); values["${r.name}_gy75"]=percentile(gyr,75); values["${r.name}_lap75"]=percentile(lr,75)
        }
        fun ratio(a:String,b:String)=(values.getValue(a)+1e-4)/(values.getValue(b)+1e-4)
        listOf(15,25,40,60).forEach { t -> values["feet_to_lower_e$t"]=ratio("feet_e$t","lower_e$t"); values["corefeet_to_lower_e$t"]=ratio("corefeet_e$t","lower_e$t") }
        listOf(75,90,95).forEach { q -> values["feet_to_lower_g$q"]=ratio("feet_g$q","lower_g$q") }
        listOf(15,25,40,60).forEach { t -> listOf("center","feet","corefeet").forEach { s -> values["${s}_to_all_e$t"]=ratio("${s}_e$t","all_e$t") }; values["corefeet_to_center_e$t"]=ratio("corefeet_e$t","center_e$t") }
        listOf(75,90,95).forEach { q -> listOf("center","feet","corefeet").forEach { s -> values["${s}_to_all_g$q"]=ratio("${s}_g$q","all_g$q") }; values["corefeet_to_center_g$q"]=ratio("corefeet_g$q","center_g$q") }
        listOf("center","feet","corefeet").forEach { values["${it}_to_all_std"]=ratio("${it}_std","all_std") }
        return JinChanBoardV8Model.featureOrder.map { values[it] ?: Double.NaN }.toDoubleArray()
    }

    private fun std(a:DoubleArray):Double { val m=a.average(); return sqrt(a.sumOf { (it-m)*(it-m) }/a.size) }
    private fun percentile(a:DoubleArray,q:Int):Double { a.sort(); val rank=(a.size-1)*q/100.0; val i=floor(rank).toInt(); val f=rank-i; return if(i+1<a.size) a[i]+(a[i+1]-a[i])*f else a[i] }
}
