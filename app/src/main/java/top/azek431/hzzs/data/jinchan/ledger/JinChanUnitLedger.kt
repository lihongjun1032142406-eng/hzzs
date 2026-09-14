package top.azek431.hzzs.data.jinchan.ledger

/** H5-A durable unit state. No frame, pixel, Android, or action references are retained. */
enum class UnitLifecycleState { ACTIVE, CONSUMED, UNKNOWN }

sealed interface UnitLocation {
    data class Board(val row: Int, val col: Int) : UnitLocation
    data class Bench(val slot: Int) : UnitLocation
    data object Unknown : UnitLocation
    data object None : UnitLocation
}

data class UnitRecord(
    val uid: Long,
    val heroKey: String?,
    val starLevel: Int?,
    val location: UnitLocation,
    val state: UnitLifecycleState,
    val source: String = "UNKNOWN",
    val createdRevision: Long,
    val lastRevision: Long,
) {
    val equivalentCopies: Int?
        get() = equivalentCopiesFor(starLevel)
}

enum class LedgerOperationStatus { APPLIED, REJECTED }

data class LedgerOperationResult(
    val status: LedgerOperationStatus,
    val revision: Long,
    val reason: String? = null,
    val uid: Long? = null,
) {
    val applied: Boolean get() = status == LedgerOperationStatus.APPLIED
}

data class HeroUnitAggregate(
    val heroKey: String,
    val equivalentCopies: Int,
    val unitCount: Int,
    val maxStar: Int,
    val boardUnits: Int,
    val benchUnits: Int,
    val uids: List<Long>,
)

data class UnitLedgerSnapshot(
    val revision: Long,
    val units: List<UnitRecord>,
    val activeUnits: List<UnitRecord>,
    val unresolvedIdentityCount: Int,
    val boardUnits: Map<UnitLocation.Board, UnitRecord>,
    val benchUnits: Map<UnitLocation.Bench, UnitRecord>,
    val heroes: List<HeroUnitAggregate>,
)

/**
 * Pure Kotlin H5-A event/revision core migrated from UNIT_LEDGER_V1 semantics.
 *
 * The mutable implementation is intentionally private to this ledger instance; all exposed records and snapshots are
 * immutable values. H5-A consumes no Shop/Board/Bench observations and exposes no action-confirmation APIs.
 */
class JinChanUnitLedger {
    private val units = linkedMapOf<Long, UnitRecord>()
    private var revision: Long = 0
    private var nextUid: Long = 1

    fun currentRevision(): Long = revision

    fun create(
        heroKey: String? = null,
        starLevel: Int? = null,
        location: UnitLocation = UnitLocation.Unknown,
        source: String = "UNKNOWN",
    ): LedgerOperationResult {
        if (!validStar(starLevel)) return reject("INVALID_STAR")
        if (!validLocation(location)) return reject("INVALID_LOCATION")
        val uid = nextUid
        return createWithUid(uid, heroKey, starLevel, location, source, advanceGeneratedUid = true)
    }

    /** Explicit UID seam for migration/replay. Duplicate or non-positive UIDs fail closed. */
    fun createWithUid(
        uid: Long,
        heroKey: String? = null,
        starLevel: Int? = null,
        location: UnitLocation = UnitLocation.Unknown,
        source: String = "UNKNOWN",
    ): LedgerOperationResult = createWithUid(uid, heroKey, starLevel, location, source, advanceGeneratedUid = false)

    fun move(uid: Long, to: UnitLocation): LedgerOperationResult {
        if (!validLocation(to)) return reject("INVALID_LOCATION")
        val current = units[uid] ?: return reject("UNIT_NOT_FOUND")
        if (current.state != UnitLifecycleState.ACTIVE) return reject("UNIT_NOT_ACTIVE")
        val newRevision = revision + 1
        units[uid] = current.copy(location = to, lastRevision = newRevision)
        revision = newRevision
        return applied(uid)
    }

    /**
     * Applies only an explicitly supplied merge. No star arithmetic or observation is used to infer a merge.
     * Source records are atomically marked CONSUMED/NONE and a new ACTIVE result record is created.
     */
    fun merge(
        sourceUids: List<Long>,
        heroKey: String?,
        starLevel: Int,
        location: UnitLocation,
        source: String = "MERGE",
        resultUid: Long? = null,
    ): LedgerOperationResult {
        if (sourceUids.size < 2) return reject("MERGE_SOURCES_INSUFFICIENT")
        if (sourceUids.distinct().size != sourceUids.size) return reject("MERGE_SOURCES_DUPLICATE")
        if (!validStar(starLevel)) return reject("INVALID_STAR")
        if (!validLocation(location)) return reject("INVALID_LOCATION")

        val sources = sourceUids.map { uid -> units[uid] ?: return reject("MERGE_SOURCE_NOT_FOUND") }
        if (sources.any { it.state != UnitLifecycleState.ACTIVE }) return reject("MERGE_SOURCE_NOT_ACTIVE")

        val resultCopies = equivalentCopiesFor(starLevel) ?: return reject("INVALID_STAR")
        val sourceCopies = sources.map { it.equivalentCopies }
        if (sourceCopies.any { it == null }) return reject("MERGE_STAR_AMBIGUOUS")
        if (sourceCopies.sumOf { requireNotNull(it) } != resultCopies) {
            return reject("MERGE_EQUIVALENT_COPIES_MISMATCH")
        }

        val normalizedHero = normalizeHeroKey(heroKey)
        val knownSourceHeroes = sources.mapNotNull { normalizeHeroKey(it.heroKey) }.distinct()
        if (normalizedHero != null && knownSourceHeroes.any { it != normalizedHero }) return reject("MERGE_HERO_MISMATCH")
        if (normalizedHero == null && knownSourceHeroes.size > 1) return reject("MERGE_HERO_AMBIGUOUS")
        val resolvedHero = normalizedHero ?: knownSourceHeroes.singleOrNull()

        val uid = resultUid ?: nextUid
        if (uid <= 0L) return reject("INVALID_UID")
        if (uid in sourceUids) return reject("MERGE_RESULT_IS_SOURCE")
        if (units.containsKey(uid)) return reject("DUPLICATE_UID")

        val newRevision = revision + 1
        sourceUids.forEach { sourceUid ->
            val record = requireNotNull(units[sourceUid])
            units[sourceUid] = record.copy(
                location = UnitLocation.None,
                state = UnitLifecycleState.CONSUMED,
                lastRevision = newRevision,
            )
        }
        units[uid] = UnitRecord(
            uid = uid,
            heroKey = resolvedHero,
            starLevel = starLevel,
            location = location,
            state = UnitLifecycleState.ACTIVE,
            source = source.ifBlank { "MERGE" },
            createdRevision = newRevision,
            lastRevision = newRevision,
        )
        revision = newRevision
        if (resultUid == null || uid >= nextUid) nextUid = uid + 1
        return applied(uid)
    }

    fun snapshot(): UnitLedgerSnapshot {
        val ordered = units.values.sortedBy { it.uid }
        val active = ordered.filter { it.state == UnitLifecycleState.ACTIVE }
        return UnitLedgerSnapshot(
            revision = revision,
            units = ordered,
            activeUnits = active,
            unresolvedIdentityCount = active.count { normalizeHeroKey(it.heroKey) == null },
            boardUnits = uniqueLocationMap(active) { it.location as? UnitLocation.Board },
            benchUnits = uniqueLocationMap(active) { it.location as? UnitLocation.Bench },
            heroes = aggregate(active),
        )
    }

    private fun createWithUid(
        uid: Long,
        heroKey: String?,
        starLevel: Int?,
        location: UnitLocation,
        source: String,
        advanceGeneratedUid: Boolean,
    ): LedgerOperationResult {
        if (uid <= 0L) return reject("INVALID_UID")
        if (units.containsKey(uid)) return reject("DUPLICATE_UID")
        if (!validStar(starLevel)) return reject("INVALID_STAR")
        if (!validLocation(location)) return reject("INVALID_LOCATION")
        val newRevision = revision + 1
        units[uid] = UnitRecord(
            uid = uid,
            heroKey = normalizeHeroKey(heroKey),
            starLevel = starLevel,
            location = location,
            state = UnitLifecycleState.ACTIVE,
            source = source.ifBlank { "UNKNOWN" },
            createdRevision = newRevision,
            lastRevision = newRevision,
        )
        revision = newRevision
        if (advanceGeneratedUid || uid >= nextUid) nextUid = uid + 1
        return applied(uid)
    }

    private fun aggregate(active: List<UnitRecord>): List<HeroUnitAggregate> = active
        .mapNotNull { unit ->
            val hero = normalizeHeroKey(unit.heroKey) ?: return@mapNotNull null
            val eq = unit.equivalentCopies ?: return@mapNotNull null
            Triple(hero, eq, unit)
        }
        .groupBy { it.first }
        .toSortedMap()
        .map { (hero, entries) ->
            val records = entries.map { it.third }.sortedBy { it.uid }
            HeroUnitAggregate(
                heroKey = hero,
                equivalentCopies = entries.sumOf { it.second },
                unitCount = records.size,
                maxStar = records.maxOf { requireNotNull(it.starLevel) },
                boardUnits = records.count { it.location is UnitLocation.Board },
                benchUnits = records.count { it.location is UnitLocation.Bench },
                uids = records.map { it.uid },
            )
        }

    private fun <L : UnitLocation> uniqueLocationMap(
        active: List<UnitRecord>,
        locationOf: (UnitRecord) -> L?,
    ): Map<L, UnitRecord> {
        val grouped = active.mapNotNull { unit -> locationOf(unit)?.let { it to unit } }.groupBy({ it.first }, { it.second })
        return grouped.entries
            .filter { it.value.size == 1 }
            .sortedBy { locationSortKey(it.key) }
            .associate { it.key to it.value.single() }
    }

    private fun locationSortKey(location: UnitLocation): String = when (location) {
        is UnitLocation.Board -> "B%02d%02d".format(location.row, location.col)
        is UnitLocation.Bench -> "E%02d".format(location.slot)
        UnitLocation.Unknown -> "U"
        UnitLocation.None -> "N"
    }

    private fun applied(uid: Long? = null) = LedgerOperationResult(LedgerOperationStatus.APPLIED, revision, uid = uid)
    private fun reject(reason: String) = LedgerOperationResult(LedgerOperationStatus.REJECTED, revision, reason = reason)

    companion object {
        fun equivalentCopiesFor(starLevel: Int?): Int? = when (starLevel) {
            1 -> 1
            2 -> 3
            3 -> 9
            else -> null
        }

        fun validStar(starLevel: Int?): Boolean = starLevel == null || starLevel in 1..3

        fun validLocation(location: UnitLocation): Boolean = when (location) {
            is UnitLocation.Board -> location.row in 1..4 && location.col in 1..7
            is UnitLocation.Bench -> location.slot in 0..8
            UnitLocation.Unknown, UnitLocation.None -> true
        }

        private fun normalizeHeroKey(heroKey: String?): String? = heroKey?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun equivalentCopiesFor(starLevel: Int?): Int? = JinChanUnitLedger.equivalentCopiesFor(starLevel)
