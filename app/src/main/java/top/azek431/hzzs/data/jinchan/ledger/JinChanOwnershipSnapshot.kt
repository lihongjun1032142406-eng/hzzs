package top.azek431.hzzs.data.jinchan.ledger

/** Immutable H5-C projection of one currently ACTIVE ledger unit. */
data class OwnedUnit(
    val uid: Long,
    val heroKey: String?,
    val starLevel: Int?,
    val equivalentCopies: Int?,
    val location: UnitLocation,
    val source: String,
    val createdRevision: Long,
    val lastRevision: Long,
)

/** Ownership totals for one known hero; unknown stars contribute a unit but never fabricated copies. */
data class KnownHeroOwnership(
    val heroKey: String,
    val unitCount: Int,
    val confirmedEquivalentCopies: Int,
    val unresolvedStarCount: Int,
    val uids: List<Long>,
)

/**
 * Read-only H5-C state for future decision inputs.
 *
 * All collections are detached from the source snapshot and deterministically ordered by UID or hero key.
 */
data class JinChanOwnershipSnapshot(
    val sourceLedgerRevision: Long,
    val activeUnits: List<OwnedUnit>,
    val knownHeroes: List<KnownHeroOwnership>,
    val totalActiveUnitCount: Int,
    val boardActiveCount: Int,
    val benchActiveCount: Int,
    val unresolvedIdentityCount: Int,
    val unresolvedStarCount: Int,
    val unresolvedLocationCount: Int,
)

/** Pure H5-C projector. It performs no perception lookup and never mutates the source ledger. */
object JinChanOwnershipProjector {
    /** Reads [ledger] exactly once before performing the pure projection. */
    fun project(ledger: JinChanUnitLedger): JinChanOwnershipSnapshot = project(ledger.snapshot())

    fun project(snapshot: UnitLedgerSnapshot): JinChanOwnershipSnapshot {
        val activeUnits = snapshot.activeUnits
            .asSequence()
            .filter { it.state == UnitLifecycleState.ACTIVE }
            .sortedBy { it.uid }
            .map { unit ->
                OwnedUnit(
                    uid = unit.uid,
                    heroKey = unit.heroKey,
                    starLevel = unit.starLevel,
                    equivalentCopies = unit.equivalentCopies,
                    location = unit.location,
                    source = unit.source,
                    createdRevision = unit.createdRevision,
                    lastRevision = unit.lastRevision,
                )
            }
            .toList()

        val knownHeroes = activeUnits
            .filter { !it.heroKey.isNullOrBlank() }
            .groupBy { requireNotNull(it.heroKey) }
            .toSortedMap()
            .map { (heroKey, units) ->
                val orderedUnits = units.sortedBy { it.uid }
                KnownHeroOwnership(
                    heroKey = heroKey,
                    unitCount = orderedUnits.size,
                    confirmedEquivalentCopies = orderedUnits.mapNotNull { it.equivalentCopies }.sum(),
                    unresolvedStarCount = orderedUnits.count { it.equivalentCopies == null },
                    uids = orderedUnits.map { it.uid },
                )
            }

        return JinChanOwnershipSnapshot(
            sourceLedgerRevision = snapshot.revision,
            activeUnits = activeUnits,
            knownHeroes = knownHeroes,
            totalActiveUnitCount = activeUnits.size,
            boardActiveCount = activeUnits.count { it.location is UnitLocation.Board },
            benchActiveCount = activeUnits.count { it.location is UnitLocation.Bench },
            unresolvedIdentityCount = activeUnits.count { it.heroKey.isNullOrBlank() },
            unresolvedStarCount = activeUnits.count { it.equivalentCopies == null },
            unresolvedLocationCount = activeUnits.count {
                it.location == UnitLocation.Unknown || it.location == UnitLocation.None
            },
        )
    }
}
