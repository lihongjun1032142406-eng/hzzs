package top.azek431.hzzs.data.jinchan.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanOwnershipSnapshotTest {
    @Test
    fun emptyLedgerProjectsAnEmptySnapshot() {
        val ownership = JinChanOwnershipProjector.project(JinChanUnitLedger())

        assertEquals(0L, ownership.sourceLedgerRevision)
        assertTrue(ownership.activeUnits.isEmpty())
        assertTrue(ownership.knownHeroes.isEmpty())
        assertEquals(0, ownership.totalActiveUnitCount)
        assertEquals(0, ownership.boardActiveCount)
        assertEquals(0, ownership.benchActiveCount)
        assertEquals(0, ownership.unresolvedIdentityCount)
        assertEquals(0, ownership.unresolvedStarCount)
        assertEquals(0, ownership.unresolvedLocationCount)
    }

    @Test
    fun boardAndBenchUnitsPreserveStableUidLocationAndSource() {
        val ledger = JinChanUnitLedger()
        ledger.createWithUid(20, "garen", 2, UnitLocation.Board(2, 4), "BOARD_CONFIRMED")
        ledger.createWithUid(5, "ahri", 1, UnitLocation.Bench(7), "BENCH_CONFIRMED")

        val ownership = JinChanOwnershipProjector.project(ledger)

        assertEquals(listOf(5L, 20L), ownership.activeUnits.map { it.uid })
        assertEquals(listOf(UnitLocation.Bench(7), UnitLocation.Board(2, 4)), ownership.activeUnits.map { it.location })
        assertEquals(listOf("BENCH_CONFIRMED", "BOARD_CONFIRMED"), ownership.activeUnits.map { it.source })
        assertEquals(1, ownership.boardActiveCount)
        assertEquals(1, ownership.benchActiveCount)
    }

    @Test
    fun knownHeroesAggregateAllUnitsAndExactOneThreeNineCopies() {
        val ledger = JinChanUnitLedger()
        ledger.createWithUid(9, "garen", 3, UnitLocation.Board(1, 1))
        ledger.createWithUid(2, "garen", 1, UnitLocation.Bench(0))
        ledger.createWithUid(7, "garen", 2, UnitLocation.Bench(1))
        ledger.createWithUid(4, "ahri", 1, UnitLocation.Board(1, 2))

        val heroes = JinChanOwnershipProjector.project(ledger).knownHeroes

        assertEquals(listOf("ahri", "garen"), heroes.map { it.heroKey })
        assertEquals(4, heroes.sumOf { it.unitCount })
        assertEquals(14, heroes.sumOf { it.confirmedEquivalentCopies })
        val garen = heroes.single { it.heroKey == "garen" }
        assertEquals(13, garen.confirmedEquivalentCopies)
        assertEquals(listOf(2L, 7L, 9L), garen.uids)
    }

    @Test
    fun consumedMergeSourcesAreNotCurrentOwnership() {
        val ledger = JinChanUnitLedger()
        val sources = (0..2).map { slot ->
            requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(slot)).uid)
        }
        ledger.merge(sources, "garen", 2, UnitLocation.Bench(0), resultUid = 50)

        val ownership = JinChanOwnershipProjector.project(ledger)

        assertEquals(listOf(50L), ownership.activeUnits.map { it.uid })
        assertEquals(1, ownership.totalActiveUnitCount)
        assertEquals(3, ownership.knownHeroes.single().confirmedEquivalentCopies)
    }

    @Test
    fun unknownIdentityAndStarRemainExplicitWithoutFabricatedAggregate() {
        val ledger = JinChanUnitLedger()
        ledger.createWithUid(1, null, 1, UnitLocation.Board(1, 1))
        ledger.createWithUid(2, "garen", null, UnitLocation.Bench(0))
        ledger.createWithUid(3, "garen", 2, UnitLocation.Bench(1))

        val ownership = JinChanOwnershipProjector.project(ledger)

        assertNull(ownership.activeUnits.first().heroKey)
        assertEquals(1, ownership.unresolvedIdentityCount)
        assertEquals(1, ownership.unresolvedStarCount)
        val garen = ownership.knownHeroes.single()
        assertEquals(2, garen.unitCount)
        assertEquals(3, garen.confirmedEquivalentCopies)
        assertEquals(1, garen.unresolvedStarCount)
    }

    @Test
    fun unknownAndNoneLocationsStayUnresolvedAndOutsideBoardBenchCounts() {
        val ledger = JinChanUnitLedger()
        ledger.create(location = UnitLocation.Unknown)
        ledger.create(location = UnitLocation.None)

        val ownership = JinChanOwnershipProjector.project(ledger)

        assertEquals(listOf(UnitLocation.Unknown, UnitLocation.None), ownership.activeUnits.map { it.location })
        assertEquals(2, ownership.unresolvedLocationCount)
        assertEquals(0, ownership.boardActiveCount)
        assertEquals(0, ownership.benchActiveCount)
    }

    @Test
    fun projectionOrderingDoesNotDependOnSourceListOrderAndFiltersNonActiveEntries() {
        val records = listOf(
            record(30, "jinx", UnitLifecycleState.ACTIVE),
            record(5, "ahri", UnitLifecycleState.ACTIVE),
            record(10, "garen", UnitLifecycleState.CONSUMED),
            record(20, "jinx", UnitLifecycleState.ACTIVE),
        )
        val forward = JinChanOwnershipProjector.project(snapshot(records))
        val reversed = JinChanOwnershipProjector.project(snapshot(records.reversed()))

        assertEquals(forward, reversed)
        assertEquals(listOf(5L, 20L, 30L), forward.activeUnits.map { it.uid })
        assertEquals(listOf("ahri", "jinx"), forward.knownHeroes.map { it.heroKey })
    }

    @Test
    fun projectionDoesNotMutateLedgerRevisionOrState() {
        val ledger = JinChanUnitLedger()
        ledger.create("garen", 1, UnitLocation.Bench(0))
        val before = ledger.snapshot()

        JinChanOwnershipProjector.project(ledger)

        assertEquals(before.revision, ledger.currentRevision())
        assertEquals(before, ledger.snapshot())
    }

    @Test
    fun ownershipGraphHasNoFramePixelAndroidPerceptionOrActionReferences() {
        val forbidden = setOf(IntArray::class.java, CapturedFrame::class.java, JinChanCanonicalFrame::class.java)
        val classes = listOf(
            JinChanOwnershipProjector::class.java,
            JinChanOwnershipSnapshot::class.java,
            OwnedUnit::class.java,
            KnownHeroOwnership::class.java,
        )
        classes.forEach { type -> assertFalse(type.declaredFields.any { it.type in forbidden }) }
        val names = classes.flatMap { type -> type.declaredFields.map { it.type.name } }
        assertFalse(names.any { name ->
            listOf("Bitmap", "Mat", "IntArray", "CapturedFrame", "Context", "Activity", "Perception", "Action", "Gesture")
                .any { name.contains(it, ignoreCase = true) }
        })
    }

    private fun record(uid: Long, heroKey: String?, state: UnitLifecycleState) = UnitRecord(
        uid = uid,
        heroKey = heroKey,
        starLevel = 1,
        location = UnitLocation.Unknown,
        state = state,
        createdRevision = uid,
        lastRevision = uid,
    )

    private fun snapshot(records: List<UnitRecord>) = UnitLedgerSnapshot(
        revision = 99,
        units = records,
        activeUnits = records,
        unresolvedIdentityCount = 0,
        boardUnits = emptyMap(),
        benchUnits = emptyMap(),
        heroes = emptyList(),
    )
}
