package top.azek431.hzzs.data.jinchan.bridge

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinedStateSnapshot
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot
import top.azek431.hzzs.data.jinchan.ledger.OwnedUnit
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState

/** Transport-safe, immutable projection of the already joined game state. */
data class RikkaObservationV1(
    val schemaVersion: Int = 1,
    val provenance: RikkaProvenance,
    val state: RikkaState,
    val ownership: RikkaOwnership,
    val quality: RikkaQuality,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("provenance", provenance.toJson())
        .put("state", state.toJson())
        .put("ownership", ownership.toJson())
        .put("quality", quality.toJson())
}

data class RikkaProvenance(
    val sessionId: Long,
    val evidenceSequence: Long,
    val ownershipRevision: Long,
    val captureTimestamp: Long,
) {
    fun toJson() = JSONObject()
        .put("sessionId", sessionId)
        .put("evidenceSequence", evidenceSequence)
        .put("ownershipRevision", ownershipRevision)
        .put("captureTimestamp", captureTimestamp)
}

/** State values remain the existing typed observations; status and unknown/invalid values are not normalized away. */
data class RikkaState(val shadow: JinChanShadowState) {
    fun toJson() = JSONObject()
        .put("shop", typed(shadow.shop.status.name, shadow.shop.value?.let { value -> JSONObject()
            .put("status", value.status.name).put("frameSeq", value.frameSeq).put("reason", value.reason)
            .put("slots", JSONArray(value.slots.map { JSONObject().put("position", it.position).put("contentType", it.contentType.name).put("heroKey", it.hero?.canonicalId ?: JSONObject.NULL).put("matched", it.matched) })) }))
        .put("gold", typed(shadow.gold.status.name, shadow.gold.value?.let { JSONObject().put("status", it.status.name).put("trust", it.trust.name).put("value", it.value ?: JSONObject.NULL).put("rawValue", it.rawValue ?: JSONObject.NULL).put("purity", it.purity.name).put("reason", it.reason) }))
        .put("level", typed(shadow.level.status.name, shadow.level.value?.let { JSONObject().put("status", it.status.name).put("value", it.value ?: JSONObject.NULL).put("reason", it.reason) }))
        .put("exp", typed(shadow.exp.status.name, shadow.exp.value?.let { JSONObject().put("status", it.status.name).put("current", it.current ?: JSONObject.NULL).put("max", it.max ?: JSONObject.NULL).put("reason", it.reason) }))
        .put("board", typed(shadow.board.status.name, shadow.board.value?.let { board -> JSONObject().put("status", board.status.name).put("frameSeq", board.frameSeq).put("decision", board.decision.name).put("reason", board.reason).put("geometry", board.geometry).put("model", board.model).put("occupiedCount", board.occupiedCount).put("cells", JSONArray(board.cells.map { JSONObject().put("row", it.row).put("col", it.col).put("cellId", it.cellId).put("occupied", it.occupied ?: JSONObject.NULL).put("probability", it.probability ?: JSONObject.NULL) })) }))
        .put("bench", typed(shadow.bench.status.name, shadow.bench.value?.let { bench -> JSONObject().put("status", bench.status.name).put("frameSeq", bench.frameSeq).put("reason", bench.reason).put("slots", JSONArray(bench.slots.map { JSONObject().put("slotIndex", it.slotIndex).put("state", it.state.name).put("heroKey", it.hero?.canonicalId ?: JSONObject.NULL).put("identity", it.identity ?: JSONObject.NULL).put("starLevel", it.starLevel ?: JSONObject.NULL) })) }))
        .put("orientation", JSONObject().put("sourceWidth", shadow.orientation.sourceWidth).put("sourceHeight", shadow.orientation.sourceHeight).put("rotationDegrees", shadow.orientation.rotationDegrees).put("canonicalWidth", shadow.orientation.canonicalWidth).put("canonicalHeight", shadow.orientation.canonicalHeight))

    private fun typed(status: String, value: JSONObject?) = JSONObject()
        .put("fieldStatus", status)
        .put("value", value ?: JSONObject.NULL)
}

data class RikkaOwnership(val activeUnits: List<OwnedUnit>, val knownHeroes: List<top.azek431.hzzs.data.jinchan.ledger.KnownHeroOwnership>) {
    fun toJson() = JSONObject()
        .put("activeUnits", JSONArray(activeUnits.map { unit -> JSONObject().put("uid", unit.uid).put("heroKey", unit.heroKey ?: JSONObject.NULL).put("starLevel", unit.starLevel ?: JSONObject.NULL).put("equivalentCopies", unit.equivalentCopies ?: JSONObject.NULL).put("location", unit.location.toJson()).put("source", unit.source).put("createdRevision", unit.createdRevision).put("lastRevision", unit.lastRevision) }))
        .put("knownHeroes", JSONArray(knownHeroes.map { hero -> JSONObject().put("heroKey", hero.heroKey).put("unitCount", hero.unitCount).put("confirmedEquivalentCopies", hero.confirmedEquivalentCopies).put("unresolvedStarCount", hero.unresolvedStarCount).put("uids", JSONArray(hero.uids)) }))
}

data class RikkaQuality(val unresolvedIdentityCount: Int, val unresolvedStarCount: Int, val unresolvedLocationCount: Int) {
    fun toJson() = JSONObject().put("unresolvedIdentityCount", unresolvedIdentityCount).put("unresolvedStarCount", unresolvedStarCount).put("unresolvedLocationCount", unresolvedLocationCount)
}

object RikkaObservationMapper {
    fun from(joined: JinChanJoinedStateSnapshot): RikkaObservationV1 {
        val ownership = joined.ownershipSnapshot
        return RikkaObservationV1(
            provenance = RikkaProvenance(joined.sessionId.value, joined.evidenceSequence, joined.ownershipRevision, joined.shadowState.timing.captureTimestamp),
            state = RikkaState(joined.shadowState),
            ownership = RikkaOwnership(ownership.activeUnits.toList(), ownership.knownHeroes.toList()),
            quality = RikkaQuality(ownership.unresolvedIdentityCount, ownership.unresolvedStarCount, ownership.unresolvedLocationCount),
        )
    }
}

sealed interface RikkaDecisionV1 {
    val provenance: RikkaDecisionProvenance
    data class Action(override val provenance: RikkaDecisionProvenance, val intent: JinChanActionIntent) : RikkaDecisionV1
    data class NoAction(override val provenance: RikkaDecisionProvenance, val reason: String?) : RikkaDecisionV1
    data class Blocked(override val provenance: RikkaDecisionProvenance, val reason: String) : RikkaDecisionV1
}

data class RikkaDecisionProvenance(val sessionId: Long, val evidenceSequence: Long, val ownershipRevision: Long)

enum class RikkaDecisionRejection { NO_PAIRED_OBSERVATION, PROVENANCE_MISMATCH, UID_NOT_UNIQUE_ACTIVE, INVALID_ACTION }
sealed interface RikkaDecisionValidationResult {
    data class ValidatedAction(val intent: JinChanActionIntent, val sourceLocation: UnitLocation, val provenance: RikkaDecisionProvenance) : RikkaDecisionValidationResult
    data class Terminated(val kind: String, val provenance: RikkaDecisionProvenance) : RikkaDecisionValidationResult
    data class Rejected(val reason: RikkaDecisionRejection) : RikkaDecisionValidationResult
}

data class PairedRikkaEvidence(val observation: RikkaObservationV1, val joinedState: JinChanJoinedStateSnapshot)

/** Latest-only bridge store. Validation exclusively reads the immutable snapshot paired at publication. */
@Singleton
class RikkaBridgeStore @Inject constructor() {
    @Volatile private var latest: PairedRikkaEvidence? = null

    @Synchronized fun publish(joined: JinChanJoinedStateSnapshot): PairedRikkaEvidence =
        PairedRikkaEvidence(RikkaObservationMapper.from(joined), joined).also { latest = it }

    fun latest(): PairedRikkaEvidence? = latest

    fun validate(decision: RikkaDecisionV1): RikkaDecisionValidationResult {
        val paired = latest ?: return RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.NO_PAIRED_OBSERVATION)
        val expected = paired.observation.provenance
        if (decision.provenance != RikkaDecisionProvenance(expected.sessionId, expected.evidenceSequence, expected.ownershipRevision)) {
            return RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.PROVENANCE_MISMATCH)
        }
        return when (decision) {
            is RikkaDecisionV1.NoAction -> RikkaDecisionValidationResult.Terminated("NO_ACTION", decision.provenance)
            is RikkaDecisionV1.Blocked -> RikkaDecisionValidationResult.Terminated("BLOCKED", decision.provenance)
            is RikkaDecisionV1.Action -> {
                val uid = when (val intent = decision.intent) {
                    is JinChanActionIntent.MoveUnit -> {
                        if (!top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger.validLocation(intent.destination)) {
                            return RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.INVALID_ACTION)
                        }
                        intent.uid
                    }
                    is JinChanActionIntent.SellUnit -> intent.uid
                    else -> return RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.INVALID_ACTION)
                }
                if (uid <= 0) return RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.INVALID_ACTION)
                val matches = paired.joinedState.ownershipSnapshot.activeUnits.filter { it.uid == uid }
                if (matches.size != 1) RikkaDecisionValidationResult.Rejected(RikkaDecisionRejection.UID_NOT_UNIQUE_ACTIVE)
                else RikkaDecisionValidationResult.ValidatedAction(decision.intent, matches.single().location, decision.provenance)
            }
        }
    }
}

fun UnitLocation.toJson(): JSONObject = when (this) {
    is UnitLocation.Board -> JSONObject().put("type", "BOARD").put("row", row).put("col", col)
    is UnitLocation.Bench -> JSONObject().put("type", "BENCH").put("slot", slot)
    UnitLocation.Unknown -> JSONObject().put("type", "UNKNOWN")
    UnitLocation.None -> JSONObject().put("type", "NONE")
}

fun parseRikkaDecision(json: JSONObject): RikkaDecisionV1 {
    val provenanceJson = json.getJSONObject("provenance")
    val provenance = RikkaDecisionProvenance(provenanceJson.getLong("sessionId"), provenanceJson.getLong("evidenceSequence"), provenanceJson.getLong("ownershipRevision"))
    return when (json.getString("kind")) {
        "NoAction" -> RikkaDecisionV1.NoAction(provenance, json.optString("reason").takeIf { it.isNotBlank() })
        "Blocked" -> RikkaDecisionV1.Blocked(provenance, json.optString("reason", "RIKKA_BLOCKED"))
        "Action" -> {
            val action = json.getJSONObject("action")
            val intent = when (action.getString("type")) {
                "Move" -> RikkaDecisionV1.Action(provenance, JinChanActionIntent.MoveUnit(action.getLong("uid"), parseLocation(action.getJSONObject("destination"))))
                "Sell" -> RikkaDecisionV1.Action(provenance, JinChanActionIntent.SellUnit(action.getLong("uid")))
                else -> throw IllegalArgumentException("unsupported Rikka action")
            }
            intent
        }
        else -> throw IllegalArgumentException("unsupported Rikka decision kind")
    }
}

private fun parseLocation(json: JSONObject): UnitLocation = when (json.getString("type")) {
    "BOARD" -> UnitLocation.Board(json.getInt("row"), json.getInt("col"))
    "BENCH" -> UnitLocation.Bench(json.getInt("slot"))
    else -> throw IllegalArgumentException("invalid destination")
}
