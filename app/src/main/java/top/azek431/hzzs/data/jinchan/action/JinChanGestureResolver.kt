package top.azek431.hzzs.data.jinchan.action

import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.domain.automation.GestureSpec

/** A full-screen normalized point backed only by explicit calibration evidence. */
data class JinChanNormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && x in 0f..1f) { "x must be finite and in [0,1]" }
        require(y.isFinite() && y in 0f..1f) { "y must be finite and in [0,1]" }
    }
}

/** Immutable calibration data. Null entries deliberately mean "not calibrated". */
data class JinChanCoordinateProfile(
    val profileId: String,
    val shopSlots: List<JinChanNormalizedPoint?> = List(SHOP_SLOT_COUNT) { null },
    val boardCells: Map<UnitLocation.Board, JinChanNormalizedPoint?> = emptyMap(),
    val benchSlots: List<JinChanNormalizedPoint?> = List(BENCH_SLOT_COUNT) { null },
    val refreshShop: JinChanNormalizedPoint? = null,
    val buyXp: JinChanNormalizedPoint? = null,
    val sellTarget: JinChanNormalizedPoint? = null,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(shopSlots.size == SHOP_SLOT_COUNT) { "shopSlots must contain exactly $SHOP_SLOT_COUNT entries" }
        require(benchSlots.size == BENCH_SLOT_COUNT) { "benchSlots must contain exactly $BENCH_SLOT_COUNT entries" }
    }

    companion object {
        const val SHOP_SLOT_COUNT = 5
        const val BENCH_SLOT_COUNT = 9
    }
}

data class JinChanGestureResolverInput(
    val action: ApprovedJinChanAction,
    val profile: JinChanCoordinateProfile?,
    val sourceLocation: UnitLocation? = null,
)

enum class JinChanGestureBlockedReason {
    PROFILE_UNAVAILABLE, TARGET_UNCALIBRATED, SOURCE_LOCATION_UNAVAILABLE,
    DESTINATION_UNCALIBRATED, SELL_TARGET_UNCALIBRATED, INVALID_LOGICAL_LOCATION,
    UNSUPPORTED_ACTION_MAPPING,
}

data class JinChanGestureResolverProvenance(val resolverId: String, val profileId: String)

sealed interface JinChanGestureResolveResult {
    val action: ApprovedJinChanAction
    data class Resolved(
        override val action: ApprovedJinChanAction,
        val gesture: GestureSpec,
        val provenance: JinChanGestureResolverProvenance,
    ) : JinChanGestureResolveResult
    data class Blocked(
        override val action: ApprovedJinChanAction,
        val reason: JinChanGestureBlockedReason,
    ) : JinChanGestureResolveResult
}

object JinChanGestureResolver {
    const val RESOLVER_ID = "jinchan-h6b-v1"
    const val DRAG_DURATION_MS = 300L

    fun resolve(input: JinChanGestureResolverInput): JinChanGestureResolveResult {
        val profile = input.profile ?: return blocked(input, JinChanGestureBlockedReason.PROFILE_UNAVAILABLE)
        val gesture = when (val intent = input.action.intent) {
            is JinChanActionIntent.BuyShopSlot -> {
                if (intent.slot !in 0 until JinChanCoordinateProfile.SHOP_SLOT_COUNT) return blocked(input, JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION)
                click(profile.shopSlots[intent.slot] ?: return blocked(input, JinChanGestureBlockedReason.TARGET_UNCALIBRATED))
            }
            JinChanActionIntent.RefreshShop -> click(profile.refreshShop ?: return blocked(input, JinChanGestureBlockedReason.TARGET_UNCALIBRATED))
            JinChanActionIntent.BuyXp -> click(profile.buyXp ?: return blocked(input, JinChanGestureBlockedReason.TARGET_UNCALIBRATED))
            is JinChanActionIntent.MoveUnit -> {
                val source = resolveSource(input, profile) ?: return sourceFailure(input)
                val destination = pointFor(intent.destination, profile) ?: return blocked(input, if (isValidLocation(intent.destination)) JinChanGestureBlockedReason.DESTINATION_UNCALIBRATED else JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION)
                drag(source, destination)
            }
            is JinChanActionIntent.SellUnit -> {
                val source = resolveSource(input, profile) ?: return sourceFailure(input)
                val destination = profile.sellTarget ?: return blocked(input, JinChanGestureBlockedReason.SELL_TARGET_UNCALIBRATED)
                drag(source, destination)
            }
        }
        return JinChanGestureResolveResult.Resolved(input.action, gesture, JinChanGestureResolverProvenance(RESOLVER_ID, profile.profileId))
    }

    private fun resolveSource(input: JinChanGestureResolverInput, profile: JinChanCoordinateProfile): JinChanNormalizedPoint? = input.sourceLocation?.let { pointFor(it, profile) }
    private fun sourceFailure(input: JinChanGestureResolverInput): JinChanGestureResolveResult.Blocked {
        val location = input.sourceLocation ?: return blocked(input, JinChanGestureBlockedReason.SOURCE_LOCATION_UNAVAILABLE)
        return blocked(input, if (isValidLocation(location)) JinChanGestureBlockedReason.SOURCE_LOCATION_UNAVAILABLE else JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION)
    }
    private fun pointFor(location: UnitLocation, profile: JinChanCoordinateProfile): JinChanNormalizedPoint? = when (location) {
        is UnitLocation.Board -> if (isValidLocation(location)) profile.boardCells[location] else null
        is UnitLocation.Bench -> if (isValidLocation(location)) profile.benchSlots[location.slot] else null
        UnitLocation.Unknown, UnitLocation.None -> null
    }
    private fun isValidLocation(location: UnitLocation): Boolean = when (location) {
        is UnitLocation.Board -> location.row in 1..4 && location.col in 1..7
        is UnitLocation.Bench -> location.slot in 0 until JinChanCoordinateProfile.BENCH_SLOT_COUNT
        UnitLocation.Unknown, UnitLocation.None -> false
    }
    private fun click(point: JinChanNormalizedPoint) = GestureSpec(point.x, point.y)
    private fun drag(source: JinChanNormalizedPoint, destination: JinChanNormalizedPoint) = GestureSpec(source.x, source.y, destination.x, destination.y, DRAG_DURATION_MS)
    private fun blocked(input: JinChanGestureResolverInput, reason: JinChanGestureBlockedReason) = JinChanGestureResolveResult.Blocked(input.action, reason)
}
