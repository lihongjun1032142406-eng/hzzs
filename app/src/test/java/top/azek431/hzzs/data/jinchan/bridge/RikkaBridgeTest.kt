package top.azek431.hzzs.data.jinchan.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.*
import org.junit.Test
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.decision.*
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.*
import top.azek431.hzzs.data.jinchan.perception.*
import top.azek431.hzzs.data.jinchan.state.*

class RikkaBridgeTest {
    @Test fun productionJoinUsesSameEvidenceAndUnavailableFailsClosed() {
        val completeBench = BenchObservation(BenchObservationStatus.COMPLETE, 12, List(9) { BenchSlotObservation(it, BenchSlotState.EMPTY) })
        val joined = JinChanProductionEvidenceJoiner().join(shadow(bench = JinChanTypedShadowObservation(ShadowFieldStatus.AVAILABLE, completeBench)))
        assertTrue(joined is ProductionJoinResult.Joined)
        val value = (joined as ProductionJoinResult.Joined).value.snapshot
        assertEquals(12, value.evidenceSequence)
        assertSame(value.ownershipSnapshot, value.actionContext.ownership)
        assertEquals(value.ownershipRevision, value.actionContext.expectedOwnershipRevision)
        assertEquals(
            ProductionJoinResult.Blocked(ProductionJoinBlockedReason.RECONCILIATION_UNAVAILABLE_OR_AMBIGUOUS),
            JinChanProductionEvidenceJoiner().join(shadow()),
        )
    }

    @Test fun observationPreservesUidUnknownInvalidAndContainsNoRuntimePayload() {
        val store = RikkaBridgeStore()
        val joined = joined(UnitLocation.Unknown, hero = null, star = null, shadow = shadow(levelStatus = ShadowFieldStatus.INVALID))
        val observation = store.publish(joined).observation
        val json = observation.toJson().toString()
        assertEquals(41, observation.ownership.activeUnits.single().uid)
        assertEquals("UNKNOWN", observation.ownership.activeUnits.single().location.toJson().getString("type"))
        assertEquals("INVALID", observation.state.toJson().getJSONObject("level").getString("fieldStatus"))
        listOf("pixels", "CapturedFrame", "Gesture", "dispatcher", "coordinator", "actionId").forEach { assertFalse(json.contains(it, true)) }
    }

    @Test fun exactDecisionAcceptedAndStaleRevisionOrUnknownUidRejected() {
        val ledger = JinChanUnitLedger().apply { createWithUid(41, "hero", 1, UnitLocation.Bench(2), "test") }
        val joined = joinedFromOwnership(JinChanOwnershipProjector.project(ledger), shadow())
        val store = RikkaBridgeStore().apply { publish(joined) }
        val provenance = RikkaDecisionProvenance(7, 12, 1)
        val accepted = store.validate(RikkaDecisionV1.Action(provenance, JinChanActionIntent.MoveUnit(41, UnitLocation.Board(1, 1))))
        assertTrue(accepted is RikkaDecisionValidationResult.ValidatedAction)
        assertEquals(UnitLocation.Bench(2), (accepted as RikkaDecisionValidationResult.ValidatedAction).sourceLocation)
        assertTrue(store.validate(RikkaDecisionV1.NoAction(provenance, null)) is RikkaDecisionValidationResult.Terminated)
        assertTrue(store.validate(RikkaDecisionV1.Blocked(provenance, "WAIT")) is RikkaDecisionValidationResult.Terminated)
        assertEquals(RikkaDecisionRejection.PROVENANCE_MISMATCH, (store.validate(RikkaDecisionV1.NoAction(provenance.copy(evidenceSequence = 11), null)) as RikkaDecisionValidationResult.Rejected).reason)
        assertEquals(RikkaDecisionRejection.PROVENANCE_MISMATCH, (store.validate(RikkaDecisionV1.NoAction(provenance.copy(ownershipRevision = 2), null)) as RikkaDecisionValidationResult.Rejected).reason)
        assertEquals(RikkaDecisionRejection.UID_NOT_UNIQUE_ACTIVE, (store.validate(RikkaDecisionV1.Action(provenance, JinChanActionIntent.SellUnit(99))) as RikkaDecisionValidationResult.Rejected).reason)
        ledger.move(41, UnitLocation.Board(4, 7))
        assertEquals(UnitLocation.Bench(2), (store.validate(RikkaDecisionV1.Action(provenance, JinChanActionIntent.SellUnit(41))) as RikkaDecisionValidationResult.ValidatedAction).sourceLocation)

        val duplicatedOwnership = joined.ownershipSnapshot.copy(activeUnits = List(2) { joined.ownershipSnapshot.activeUnits.single() })
        val duplicateJoined = joinedFromOwnership(duplicatedOwnership, shadow())
        store.publish(duplicateJoined)
        assertEquals(RikkaDecisionRejection.UID_NOT_UNIQUE_ACTIVE, (store.validate(RikkaDecisionV1.Action(provenance, JinChanActionIntent.SellUnit(41))) as RikkaDecisionValidationResult.Rejected).reason)
    }

    @Test fun latestStoreAndSourceProhibitionsRemainExecutionFree() {
        val store = RikkaBridgeStore()
        val first = store.publish(joined(UnitLocation.Bench(0)))
        assertSame(first, store.latest())
        val root = Path.of("src/main/java/top/azek431/hzzs/data/jinchan/bridge").takeIf(Files::exists)
            ?: Path.of("app/src/main/java/top/azek431/hzzs/data/jinchan/bridge")
        val sources = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.map(Files::readString).toList() }
        val forbidden = listOf("JinChanExecutionCoordinator", "GestureDispatcher", "GestureArbiter", "dispatchGesture", "requestAction", "StateFlow")
        forbidden.forEach { assertFalse("forbidden bridge token: $it", sources.any { source -> it in source }) }
    }

    private fun joined(location: UnitLocation, hero: String? = "hero", star: Int? = 1, shadow: JinChanShadowState = shadow()): JinChanJoinedStateSnapshot {
        val ledger = JinChanUnitLedger().apply { createWithUid(41, hero, star, location, "test") }
        return joinedFromOwnership(JinChanOwnershipProjector.project(ledger), shadow)
    }

    private fun joinedFromOwnership(ownership: JinChanOwnershipSnapshot, shadow: JinChanShadowState): JinChanJoinedStateSnapshot {
        val context = JinChanActionContext(sessionId = shadow.sessionId, evidenceSessionId = shadow.sessionId, currentSequence = shadow.frameSeq, evidenceSequence = shadow.frameSeq, maximumSequenceAge = 0, stableState = JinChanStableState(null, JinChanStableUiState.UNKNOWN), ownership = ownership, ownershipSessionId = shadow.sessionId, ownershipSequence = shadow.frameSeq, expectedOwnershipRevision = ownership.sourceLedgerRevision)
        return (JinChanSameEvidenceAssembler.assemble(JinChanSameEvidenceFacts(shadow, ownership, context)) as JinChanJoinResult.Assembled).snapshot
    }

    private fun shadow(bench: JinChanTypedShadowObservation<BenchObservation> = unknown(), levelStatus: ShadowFieldStatus = ShadowFieldStatus.UNKNOWN) = JinChanShadowState(
        JinChanFrameSessionId(7), 12, 100, JinChanOrientation(3120, 1440, 0, 3120, 1440),
        unknown(), unknown(), JinChanTypedShadowObservation(levelStatus), unknown(), unknown(), bench,
        JinChanShadowTiming(99, 1, 1, 1, 4),
    )
    private fun <T> unknown() = JinChanTypedShadowObservation<T>(ShadowFieldStatus.UNKNOWN)
}
