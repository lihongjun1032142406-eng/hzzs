package top.azek431.hzzs.data.jinchan.dryrun

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionUiEvidence
import top.azek431.hzzs.data.jinchan.action.JinChanCoordinateProfile
import top.azek431.hzzs.data.jinchan.action.JinChanNormalizedPoint
import top.azek431.hzzs.data.jinchan.bridge.RikkaBridgeStore
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionProvenance
import top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionV1
import top.azek431.hzzs.data.jinchan.bridge.RikkaDryRunDiagnostics
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinResult
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceAssembler
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceFacts
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipProjector
import top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger
import top.azek431.hzzs.data.jinchan.ledger.ObservationReconcileStatus
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.state.JinChanOrientation
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState
import top.azek431.hzzs.data.jinchan.state.JinChanShadowTiming
import top.azek431.hzzs.data.jinchan.state.JinChanTypedShadowObservation
import top.azek431.hzzs.data.jinchan.state.ShadowFieldStatus

class JinChanDryRunValidatorTest {
    private val validator = JinChanDryRunValidator()

    @Test fun exactMovePassesGateButProductionProfileIsUnavailableAndNeverExecutes() {
        val fixture = fixture(UnitLocation.Bench(2))
        val result = submit(fixture, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1)))
        assertEquals(JinChanDryRunTerminalReason.RESOLVER_PROFILE_UNAVAILABLE, result.terminalReason)
        assertEquals("WOULD_PASS", result.gateStatus)
        assertEquals(UnitLocation.Bench(2), result.sourceLocation)
        assertFalse(result.realActionReachable)
        assertEquals(0, result.actionExecuted)
    }

    @Test fun testOnlyProfileWouldResolveAndHardStops() {
        val fixture = fixture(UnitLocation.Bench(2))
        val profile = JinChanCoordinateProfile(
            profileId = "jvm-test-only",
            boardCells = mapOf(UnitLocation.Board(1, 1) to JinChanNormalizedPoint(.7f, .4f)),
            benchSlots = List(9) { if (it == 2) JinChanNormalizedPoint(.3f, .8f) else null },
        )
        val paired = fixture.store.validatePaired(action(fixture, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1))))
        val result = validator.validate(paired, profile)
        assertEquals(JinChanDryRunTerminalReason.WOULD_RESOLVE, result.terminalReason)
        assertEquals("WOULD_RESOLVE", result.resolverStatus)
        assertEquals("jvm-test-only", result.profileId)
        assertFalse(result.realActionReachable)
        assertEquals(0, result.actionExecuted)
    }

    @Test fun h6aRejectStopsBeforeResolver() {
        val fixture = fixture(UnitLocation.Bench(2), actionEnabled = false)
        val result = submit(fixture, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1)))
        assertEquals(JinChanDryRunTerminalReason.GATE_REJECTED, result.terminalReason)
        assertEquals("ACTION_DISABLED", result.gateReason)
        assertEquals("NOT_EVALUATED", result.resolverStatus)
        assertNull(result.profileId)
    }

    @Test fun provenanceStaleMissingAndDuplicateUidNeverEnterActionPath() {
        val fixture = fixture(UnitLocation.Bench(2))
        val stale = fixture.store.validatePaired(action(fixture, JinChanActionIntent.SellUnit(41), revision = 99))
        assertEquals(JinChanDryRunTerminalReason.PROVENANCE_REJECTED, rejected(stale).terminalReason)
        val missing = fixture.store.validatePaired(action(fixture, JinChanActionIntent.SellUnit(99)))
        assertEquals(JinChanDryRunTerminalReason.UID_REJECTED, rejected(missing).terminalReason)

        val duplicate = fixture.joined.ownershipSnapshot.copy(activeUnits = List(2) { fixture.joined.ownershipSnapshot.activeUnits.single() })
        val duplicateFixture = fixture(UnitLocation.Bench(2), ownershipOverride = duplicate)
        val duplicated = duplicateFixture.store.validatePaired(action(duplicateFixture, JinChanActionIntent.SellUnit(41)))
        assertEquals(JinChanDryRunTerminalReason.UID_REJECTED, rejected(duplicated).terminalReason)
        listOf(stale, missing, duplicated).forEach { paired -> assertEquals("NOT_EVALUATED", rejected(paired).gateStatus) }
    }

    @Test fun pairedSnapshotRemainsAuthoritativeForMoveAndSellAfterLedgerChanges() {
        val moveFixture = fixture(UnitLocation.Bench(2))
        moveFixture.ledger.move(41, UnitLocation.Board(4, 7))
        val move = submit(moveFixture, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1)))
        assertEquals(UnitLocation.Bench(2), move.sourceLocation)

        val sellFixture = fixture(UnitLocation.Board(2, 3))
        sellFixture.ledger.move(41, UnitLocation.Bench(8))
        val sell = submit(sellFixture, JinChanActionIntent.SellUnit(41))
        assertEquals(UnitLocation.Board(2, 3), sell.sourceLocation)
        assertSame(sellFixture.joined.ownershipSnapshot, sellFixture.joined.actionContext.ownership)
    }

    @Test fun noActionAndRikkaBlockedSkipBothValidators() {
        val fixture = fixture(UnitLocation.Bench(2), actionEnabled = false)
        val provenance = provenance(fixture)
        val noAction = validator.validate(fixture.store.validatePaired(RikkaDecisionV1.NoAction(provenance, null)))
        val blocked = validator.validate(fixture.store.validatePaired(RikkaDecisionV1.Blocked(provenance, "WAIT")))
        assertEquals(JinChanDryRunTerminalReason.RIKKA_NO_ACTION, noAction.terminalReason)
        assertEquals(JinChanDryRunTerminalReason.RIKKA_BLOCKED, blocked.terminalReason)
        listOf(noAction, blocked).forEach {
            assertEquals("NOT_EVALUATED", it.gateStatus)
            assertEquals("NOT_EVALUATED", it.resolverStatus)
        }
    }

    @Test fun diagnosticsAndProductionArchitectureAreHardStopped() {
        RikkaDryRunDiagnostics.resetForTest()
        val fixture = fixture(UnitLocation.Bench(2))
        submit(fixture, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1)))
        val counts = RikkaDryRunDiagnostics.snapshot().counters
        listOf("OBSERVATION_PUBLISHED", "RIKKA_DECISION_RECEIVED", "DECISION_VALIDATED", "GATE_WOULD_PASS", "PROFILE_UNAVAILABLE", "DRY_RUN_HARD_STOP").forEach {
            assertEquals("counter $it", 1L, counts[it])
        }

        val roots = listOf(
            Path.of("src/main/java/top/azek431/hzzs/data/jinchan/dryrun"),
            Path.of("app/src/main/java/top/azek431/hzzs/data/jinchan/dryrun"),
        )
        val root = roots.first(Files::exists)
        val text = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.map(Files::readString).toList() }.joinToString("\n")
        listOf("JinChanRuntimeActionIntegration", "JinChanExecutionCoordinator", "GestureArbiter", "GestureDispatcherFactory", "dispatchGesture", "Shizuku", "RootGesture").forEach {
            assertFalse("forbidden C5 production reference: $it", text.contains(it))
        }
    }

    private fun submit(fixture: Fixture, intent: JinChanActionIntent): JinChanDryRunResult =
        validator.validate(fixture.store.validatePaired(action(fixture, intent)))

    private fun rejected(value: top.azek431.hzzs.data.jinchan.bridge.PairedRikkaValidation): JinChanDryRunResult =
        rikkaRejectedResult((value.result as top.azek431.hzzs.data.jinchan.bridge.RikkaDecisionValidationResult.Rejected).reason)

    private fun action(fixture: Fixture, intent: JinChanActionIntent, revision: Long = fixture.joined.ownershipRevision) =
        RikkaDecisionV1.Action(provenance(fixture).copy(ownershipRevision = revision), intent)

    private fun provenance(fixture: Fixture) = RikkaDecisionProvenance(
        fixture.joined.sessionId.value, fixture.joined.evidenceSequence, fixture.joined.ownershipRevision,
    )

    private fun fixture(location: UnitLocation, actionEnabled: Boolean = true, ownershipOverride: top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot? = null): Fixture {
        val ledger = JinChanUnitLedger().apply { createWithUid(41, "hero", 1, location, "test") }
        val ownership = ownershipOverride ?: JinChanOwnershipProjector.project(ledger)
        val shadow = shadow()
        val context = JinChanActionContext(
            actionEnabled = actionEnabled,
            targetPackage = "com.tencent.jkchess",
            sessionId = shadow.sessionId,
            evidenceSessionId = shadow.sessionId,
            currentSequence = shadow.frameSeq,
            evidenceSequence = shadow.frameSeq,
            maximumSequenceAge = 0,
            stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT),
            boardTrusted = true,
            benchTrusted = true,
            ownership = ownership,
            ownershipSessionId = shadow.sessionId,
            ownershipSequence = shadow.frameSeq,
            expectedOwnershipRevision = ownership.sourceLedgerRevision,
            reconcileStatus = ObservationReconcileStatus.NO_CHANGE,
            uiEvidence = JinChanActionUiEvidence(sellAllowed = true),
        )
        val joined = (JinChanSameEvidenceAssembler.assemble(JinChanSameEvidenceFacts(shadow, ownership, context)) as JinChanJoinResult.Assembled).snapshot
        val store = RikkaBridgeStore().apply { publish(joined) }
        return Fixture(ledger, joined, store)
    }

    private fun shadow() = JinChanShadowState(
        JinChanFrameSessionId(7), 12, 100, JinChanOrientation(3120, 1440, 0, 3120, 1440),
        unknown(), unknown(), unknown(), unknown(), unknown(), unknown(), JinChanShadowTiming(99, 1, 1, 1, 4),
    )
    private fun <T> unknown() = JinChanTypedShadowObservation<T>(ShadowFieldStatus.UNKNOWN)
    private data class Fixture(val ledger: JinChanUnitLedger, val joined: top.azek431.hzzs.data.jinchan.decision.JinChanJoinedStateSnapshot, val store: RikkaBridgeStore)
}
