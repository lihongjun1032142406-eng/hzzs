package top.azek431.hzzs.data.jinchan.decision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipProjector
import top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.state.JinChanOrientation
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState
import top.azek431.hzzs.data.jinchan.state.JinChanShadowTiming
import top.azek431.hzzs.data.jinchan.state.JinChanTypedShadowObservation
import top.azek431.hzzs.data.jinchan.state.ShadowFieldStatus

class JinChanDecisionContractTest {
    @Test
    fun exactSameEvidenceAssemblesAndPreservesImmutableSourceLocation() {
        val fixture = fixture()
        val result = JinChanSameEvidenceAssembler.assemble(fixture.facts)
        assertTrue(result is JinChanJoinResult.Assembled)
        val snapshot = (result as JinChanJoinResult.Assembled).snapshot
        assertEquals(fixture.session, snapshot.sessionId)
        assertEquals(12L, snapshot.evidenceSequence)
        assertEquals(fixture.ownership.sourceLedgerRevision, snapshot.ownershipRevision)
        assertSame(fixture.ownership, snapshot.ownershipSnapshot)
        assertEquals(UnitLocation.Bench(3), snapshot.ownershipSnapshot.activeUnits.single().location)
    }

    @Test
    fun sessionMismatchBlocks() {
        val fixture = fixture()
        assertBlocked(
            fixture.facts.copy(actionContext = fixture.facts.actionContext.copy(evidenceSessionId = JinChanFrameSessionId(2))),
            JinChanJoinBlockedReason.SESSION_MISMATCH,
        )
    }

    @Test
    fun evidenceMismatchBlocks() {
        val fixture = fixture()
        assertBlocked(
            fixture.facts.copy(actionContext = fixture.facts.actionContext.copy(ownershipSequence = 13L)),
            JinChanJoinBlockedReason.EVIDENCE_SEQUENCE_MISMATCH,
        )
    }

    @Test
    fun ownershipRevisionMismatchBlocks() {
        val fixture = fixture()
        assertBlocked(
            fixture.facts.copy(
                actionContext = fixture.facts.actionContext.copy(
                    expectedOwnershipRevision = fixture.ownership.sourceLedgerRevision + 1,
                ),
            ),
            JinChanJoinBlockedReason.OWNERSHIP_REVISION_MISMATCH,
        )
    }

    @Test
    fun missingOwnershipFailsClosed() {
        val fixture = fixture()
        assertBlocked(fixture.facts.copy(ownershipSnapshot = null), JinChanJoinBlockedReason.OWNERSHIP_MISSING)
    }

    @Test
    fun decisionCopiesInputProvenanceAndCannotReturnAction() {
        val joined = JinChanSameEvidenceAssembler.assemble(fixture().facts) as JinChanJoinResult.Assembled
        val result = FailClosedJinChanDecisionEngine.decide(JinChanDecisionInput(joined.snapshot))
        assertTrue(result is JinChanDecisionResult.NoAction)
        assertEquals(
            JinChanDecisionProvenance(
                joined.snapshot.sessionId,
                joined.snapshot.evidenceSequence,
                joined.snapshot.ownershipRevision,
            ),
            result.provenance,
        )
    }

    @Test
    fun c4cProductionSourcesContainNoExecutionOrLaterLookupPath() {
        val root = Path.of("src/main/java/top/azek431/hzzs/data/jinchan/decision")
            .takeIf(Files::exists)
            ?: Path.of("app/src/main/java/top/azek431/hzzs/data/jinchan/decision")
        val sources = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .map(Files::readString)
                .toList()
        }
        assertFalse(sources.isEmpty())
        val forbidden = listOf(
            "JinChanRuntimeActionIntegration", "JinChanExecutionCoordinator", "GestureArbiter",
            "GestureDispatcher", "dispatchGesture", "Shizuku", "RootGestureDispatcher", "ShellInput",
            "JinChanUnitLedger", "StateFlow", ".value", "JinChanActionIntent",
        )
        forbidden.forEach { token ->
            assertFalse("forbidden C4C production token: $token", sources.any { token in it })
        }
    }

    private fun assertBlocked(facts: JinChanSameEvidenceFacts, reason: JinChanJoinBlockedReason) {
        assertEquals(JinChanJoinResult.Blocked(reason), JinChanSameEvidenceAssembler.assemble(facts))
    }

    private fun fixture(): Fixture {
        val session = JinChanFrameSessionId(1)
        val ledger = JinChanUnitLedger().apply {
            createWithUid(9L, "hero", 1, UnitLocation.Bench(3), "test")
        }
        val ownership = JinChanOwnershipProjector.project(ledger)
        val shadow = JinChanShadowState(
            sessionId = session,
            frameSeq = 12L,
            timestampElapsedRealtimeNanos = 100L,
            orientation = JinChanOrientation(3120, 1440, 0, 3120, 1440),
            shop = unknown(),
            gold = unknown(),
            level = unknown(),
            exp = unknown(),
            board = unknown(),
            bench = unknown(),
            timing = JinChanShadowTiming(100L, 1L, 1L, 1L, 4L),
        )
        val context = JinChanActionContext(
            sessionId = session,
            evidenceSessionId = session,
            currentSequence = 12L,
            evidenceSequence = 12L,
            maximumSequenceAge = 0L,
            stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT),
            ownership = ownership,
            ownershipSessionId = session,
            ownershipSequence = 12L,
            expectedOwnershipRevision = ownership.sourceLedgerRevision,
        )
        return Fixture(session, ownership, JinChanSameEvidenceFacts(shadow, ownership, context))
    }

    private data class Fixture(
        val session: JinChanFrameSessionId,
        val ownership: top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot,
        val facts: JinChanSameEvidenceFacts,
    )

    private fun <T> unknown() = JinChanTypedShadowObservation<T>(ShadowFieldStatus.UNKNOWN)
}
