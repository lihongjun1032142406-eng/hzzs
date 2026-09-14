package top.azek431.hzzs.data.jinchan.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame
import top.azek431.hzzs.service.capture.CapturedFrame

class JinChanUnitLedgerTest {
    @Test
    fun createGeneratesStableUidAndRevisionAndRejectsDuplicateWithoutMutation() {
        val ledger = JinChanUnitLedger()
        val first = ledger.create("garen", 1, UnitLocation.Bench(0), "BENCH")
        assertTrue(first.applied)
        assertEquals(1L, first.uid)
        assertEquals(1L, first.revision)

        val second = ledger.create("ahri", 2, UnitLocation.Board(1, 1), "BOARD")
        assertEquals(2L, second.uid)
        assertEquals(2L, ledger.currentRevision())

        val before = ledger.snapshot()
        val duplicate = ledger.createWithUid(1, "jinx", 1, UnitLocation.Bench(1))
        assertFalse(duplicate.applied)
        assertEquals("DUPLICATE_UID", duplicate.reason)
        assertEquals(before, ledger.snapshot())
    }

    @Test
    fun validatesBoardBenchAndStarsFailClosed() {
        val ledger = JinChanUnitLedger()
        assertTrue(ledger.create(location = UnitLocation.Board(1, 1)).applied)
        assertTrue(ledger.create(location = UnitLocation.Board(4, 7)).applied)
        assertTrue(ledger.create(location = UnitLocation.Bench(0)).applied)
        assertTrue(ledger.create(location = UnitLocation.Bench(8)).applied)
        val revision = ledger.currentRevision()

        listOf(
            UnitLocation.Board(0, 1), UnitLocation.Board(5, 1), UnitLocation.Board(1, 0), UnitLocation.Board(1, 8),
            UnitLocation.Bench(-1), UnitLocation.Bench(9),
        ).forEach { location ->
            val result = ledger.create(location = location)
            assertFalse(result.applied)
            assertEquals("INVALID_LOCATION", result.reason)
        }
        listOf(0, 4).forEach { star ->
            val result = ledger.create(starLevel = star)
            assertFalse(result.applied)
            assertEquals("INVALID_STAR", result.reason)
        }
        assertEquals(revision, ledger.currentRevision())
    }

    @Test
    fun movePreservesUidAndOnlyAdvancesOnValidExistingActiveUnit() {
        val ledger = JinChanUnitLedger()
        val created = ledger.create("garen", 2, UnitLocation.Bench(2))
        val uid = requireNotNull(created.uid)
        val moved = ledger.move(uid, UnitLocation.Board(3, 4))
        assertTrue(moved.applied)
        assertEquals(2L, moved.revision)
        val unit = ledger.snapshot().activeUnits.single()
        assertEquals(uid, unit.uid)
        assertEquals("garen", unit.heroKey)
        assertEquals(2, unit.starLevel)
        assertEquals(UnitLocation.Board(3, 4), unit.location)
        assertEquals(1L, unit.createdRevision)
        assertEquals(2L, unit.lastRevision)

        val before = ledger.snapshot()
        val missing = ledger.move(999, UnitLocation.Bench(1))
        assertFalse(missing.applied)
        assertEquals("UNIT_NOT_FOUND", missing.reason)
        assertEquals(before, ledger.snapshot())
    }

    @Test
    fun equivalentCopiesAreExactlyOneThreeNineOrUnknown() {
        assertEquals(1, JinChanUnitLedger.equivalentCopiesFor(1))
        assertEquals(3, JinChanUnitLedger.equivalentCopiesFor(2))
        assertEquals(9, JinChanUnitLedger.equivalentCopiesFor(3))
        assertNull(JinChanUnitLedger.equivalentCopiesFor(null))
        assertNull(JinChanUnitLedger.equivalentCopiesFor(4))
    }

    @Test
    fun unknownIdentityAndLocationRemainFirstClassAndAreNotAggregated() {
        val ledger = JinChanUnitLedger()
        ledger.create(heroKey = null, starLevel = null, location = UnitLocation.Unknown)
        ledger.create(heroKey = "", starLevel = 1, location = UnitLocation.None)
        ledger.create(heroKey = "garen", starLevel = null, location = UnitLocation.Bench(0))
        ledger.create(heroKey = "garen", starLevel = 2, location = UnitLocation.Bench(1))

        val snapshot = ledger.snapshot()
        assertEquals(2, snapshot.unresolvedIdentityCount)
        assertEquals(UnitLocation.Unknown, snapshot.units[0].location)
        assertEquals(UnitLocation.None, snapshot.units[1].location)
        assertEquals(1, snapshot.heroes.size)
        assertEquals("garen", snapshot.heroes.single().heroKey)
        assertEquals(3, snapshot.heroes.single().equivalentCopies)
        assertEquals(1, snapshot.heroes.single().unitCount)
    }

    @Test
    fun mergeIsExplicitAtomicAndConsumesOnlySuppliedSources() {
        val ledger = JinChanUnitLedger()
        val a = requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(0)).uid)
        val b = requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(1)).uid)
        val c = requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(2)).uid)

        val merged = ledger.merge(listOf(a, b, c), "garen", 2, UnitLocation.Bench(0))
        assertTrue(merged.applied)
        assertEquals(4L, merged.revision)
        val snapshot = ledger.snapshot()
        assertEquals(4, snapshot.units.size)
        assertEquals(3, snapshot.units.count { it.state == UnitLifecycleState.CONSUMED && it.location == UnitLocation.None })
        val result = snapshot.activeUnits.single()
        assertEquals(2, result.starLevel)
        assertEquals(3, result.equivalentCopies)
        assertEquals("garen", result.heroKey)
    }

    @Test
    fun invalidMergeNeverMutatesLedger() {
        fun assertRejected(operation: (JinChanUnitLedger, Long, Long) -> LedgerOperationResult, reason: String) {
            val ledger = JinChanUnitLedger()
            val a = requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(0)).uid)
            val b = requireNotNull(ledger.create("garen", 1, UnitLocation.Bench(1)).uid)
            val before = ledger.snapshot()
            val result = operation(ledger, a, b)
            assertFalse(result.applied)
            assertEquals(reason, result.reason)
            assertEquals(before, ledger.snapshot())
        }

        assertRejected({ l, a, _ -> l.merge(listOf(a, a), "garen", 2, UnitLocation.Bench(0)) }, "MERGE_SOURCES_DUPLICATE")
        assertRejected({ l, a, _ -> l.merge(listOf(a, 999), "garen", 2, UnitLocation.Bench(0)) }, "MERGE_SOURCE_NOT_FOUND")
        assertRejected({ l, a, b -> l.merge(listOf(a, b), "garen", 2, UnitLocation.Bench(0), resultUid = a) }, "MERGE_RESULT_IS_SOURCE")
        assertRejected({ l, a, b -> l.merge(listOf(a, b), "garen", 4, UnitLocation.Bench(0)) }, "INVALID_STAR")
    }

    @Test
    fun conflictingKnownHeroesMakeMergeAmbiguousOrMismatch() {
        val ledger = JinChanUnitLedger()
        val a = requireNotNull(ledger.create("garen", 1).uid)
        val b = requireNotNull(ledger.create("ahri", 1).uid)
        val before = ledger.snapshot()
        assertEquals("MERGE_HERO_AMBIGUOUS", ledger.merge(listOf(a, b), null, 2, UnitLocation.Unknown).reason)
        assertEquals(before, ledger.snapshot())
        assertEquals("MERGE_HERO_MISMATCH", ledger.merge(listOf(a, b), "garen", 2, UnitLocation.Unknown).reason)
        assertEquals(before, ledger.snapshot())
    }

    @Test
    fun snapshotOrderingAndLocationMapsAreDeterministicAndOnlyUnambiguous() {
        val ledger = JinChanUnitLedger()
        ledger.createWithUid(20, "garen", 1, UnitLocation.Board(2, 2))
        ledger.createWithUid(5, "ahri", 1, UnitLocation.Bench(3))
        ledger.createWithUid(9, "jinx", 1, UnitLocation.Board(2, 2))
        val snapshot = ledger.snapshot()
        assertEquals(listOf(5L, 9L, 20L), snapshot.units.map { it.uid })
        assertEquals(listOf(UnitLocation.Bench(3)), snapshot.benchUnits.keys.toList())
        assertTrue(snapshot.boardUnits.isEmpty())
        assertEquals(listOf("ahri", "garen", "jinx"), snapshot.heroes.map { it.heroKey })
    }

    @Test
    fun durableLedgerGraphHasNoFramePixelAndroidOrActionReferences() {
        val forbidden = setOf(IntArray::class.java, CapturedFrame::class.java, JinChanCanonicalFrame::class.java)
        val classes = listOf(
            JinChanUnitLedger::class.java,
            UnitRecord::class.java,
            UnitLedgerSnapshot::class.java,
            HeroUnitAggregate::class.java,
            UnitLocation.Board::class.java,
            UnitLocation.Bench::class.java,
        )
        classes.forEach { type -> assertFalse(type.declaredFields.any { it.type in forbidden }) }
        val names = classes.flatMap { type -> type.declaredFields.map { it.type.name } }
        assertFalse(names.any {
            it.contains("Bitmap", true) || it.contains("Mat", true) || it.contains("CapturedFrame", true) ||
                it.contains("Context", true) || it.contains("Activity", true) || it.contains("View", true) ||
                it.contains("Action", true) || it.contains("Gesture", true)
        })
    }
}
