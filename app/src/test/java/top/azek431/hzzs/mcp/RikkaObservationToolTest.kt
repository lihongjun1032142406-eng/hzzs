package top.azek431.hzzs.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.McpToolPolicy
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.bridge.RikkaBridgeStore
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinResult
import top.azek431.hzzs.data.jinchan.decision.JinChanJoinedStateSnapshot
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceAssembler
import top.azek431.hzzs.data.jinchan.decision.JinChanSameEvidenceFacts
import top.azek431.hzzs.data.jinchan.dryrun.JinChanDryRunValidator
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipProjector
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipSnapshot
import top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.BenchObservation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.state.JinChanOrientation
import top.azek431.hzzs.data.jinchan.state.JinChanShadowState
import top.azek431.hzzs.data.jinchan.state.JinChanShadowTiming
import top.azek431.hzzs.data.jinchan.state.JinChanTypedShadowObservation
import top.azek431.hzzs.data.jinchan.state.ShadowFieldStatus
import top.azek431.hzzs.mcp.executor.RikkaBridgeExecutor

/**
 * H6-C5 FIX1: read-only `get_rikka_observation_v1` must expose exactly the immutable publication that
 * the MCP resource `app://rikka/observation/v1/latest` already serves - no second source, no repair.
 */
class RikkaObservationToolTest {
    private val store = RikkaBridgeStore()
    private val executor = RikkaBridgeExecutor(store, JinChanDryRunValidator())

    @Test
    fun emptyStoreFailsClosedWithStableReason() = runBlocking {
        val result = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
        assertFalse(result.getBoolean("available"))
        assertEquals("NO_RIKKA_OBSERVATION", result.getString("reason"))
        assertEquals(RikkaBridgeExecutor.NO_OBSERVATION_REASON, result.getString("reason"))
        assertTrue(result.isNull("observation"))
    }

    @Test
    fun publishedObservationIsIdenticalToResourcePublication() = runBlocking {
        val published = store.publish(joined(UnitLocation.Bench(2)))
        val toolObservation = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
            .getJSONObject("observation")
        // Resource read path in McpActionRegistry is `latest()?.observation?.toJson()`.
        val resourceObservation = store.latest()!!.observation.toJson()
        assertEquals(resourceObservation.toString(), toolObservation.toString())
        assertEquals(published.observation.toJson().toString(), toolObservation.toString())
        assertEquals(1, toolObservation.getInt("schemaVersion"))
    }

    @Test
    fun latestPublicationWinsAndIsNeverMerged() = runBlocking {
        store.publish(joined(UnitLocation.Bench(0), frameSeq = 12))
        val second = store.publish(joined(UnitLocation.Board(1, 1), frameSeq = 40))
        val toolObservation = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
            .getJSONObject("observation")
        assertEquals(second.observation.toJson().toString(), toolObservation.toString())
        assertEquals(40L, toolObservation.getJSONObject("provenance").getLong("evidenceSequence"))
        val location = toolObservation.getJSONObject("ownership").getJSONArray("activeUnits")
            .getJSONObject(0).getJSONObject("location")
        assertEquals("BOARD", location.getString("type"))
        assertEquals(1, location.getInt("row"))
        assertFalse(toolObservation.toString().contains("\"BENCH\""))
    }

    @Test
    fun unknownAndInvalidFieldsAreNotNormalizedAway() = runBlocking {
        store.publish(
            joined(
                location = UnitLocation.Unknown,
                hero = null,
                star = null,
                shadow = shadow(levelStatus = ShadowFieldStatus.INVALID),
            ),
        )
        val observation = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
            .getJSONObject("observation")
        assertEquals("INVALID", observation.getJSONObject("state").getJSONObject("level").getString("fieldStatus"))
        assertEquals("UNKNOWN", observation.getJSONObject("state").getJSONObject("shop").getString("fieldStatus"))
        val unit = observation.getJSONObject("ownership").getJSONArray("activeUnits").getJSONObject(0)
        assertEquals("UNKNOWN", unit.getJSONObject("location").getString("type"))
        assertTrue(unit.isNull("heroKey"))
    }

    @Test
    fun ownershipActiveUnitUidIsPreserved() = runBlocking {
        store.publish(joined(UnitLocation.Bench(3)))
        val unit = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
            .getJSONObject("observation")
            .getJSONObject("ownership").getJSONArray("activeUnits").getJSONObject(0)
        assertEquals(41L, unit.getLong("uid"))
        assertEquals("hero", unit.getString("heroKey"))
        assertEquals(1, unit.getInt("starLevel"))
    }

    @Test
    fun provenanceQuadrupleIsPreserved() = runBlocking {
        store.publish(joined(UnitLocation.Bench(1), frameSeq = 12))
        val provenance = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
            .getJSONObject("observation").getJSONObject("provenance")
        assertEquals(7L, provenance.getLong("sessionId"))
        assertEquals(12L, provenance.getLong("evidenceSequence"))
        assertEquals(1L, provenance.getLong("ownershipRevision"))
        assertEquals(99L, provenance.getLong("captureTimestamp"))
    }

    @Test
    fun observationReadNeverTouchesActionInfrastructure() = runBlocking {
        store.publish(joined(UnitLocation.Bench(2)))
        val result = executor.execute(RikkaBridgeExecutor.GET_OBSERVATION_V1, JSONObject())
        assertFalse(result.has("actionExecuted"))
        assertFalse(result.has("realActionReachable"))
        assertFalse(result.has("c3Called"))
        assertFalse(result.has("coordinatorCalled"))
        val source = executorSource()
        listOf(
            "JinChanExecutionCoordinator",
            "JinChanRuntimeActionIntegration",
            "GestureArbiter",
            "GestureDispatcher",
            "dispatchGesture",
            "Shizuku",
            "Accessibility",
            "ledger.snapshot(",
            "reconcile(",
            "project(",
            "ACTION_ENABLED",
            "realActionReachable",
        ).forEach { token -> assertFalse("forbidden token in observation read path: $token", token in source) }
    }

    @Test
    fun submitDecisionV1BehaviourIsUnchanged() = runBlocking {
        store.publish(joined(UnitLocation.Bench(2)))
        val decision = JSONObject()
            .put("kind", "NoAction")
            .put(
                "provenance",
                JSONObject().put("sessionId", 7).put("evidenceSequence", 12).put("ownershipRevision", 1),
            )
        val result = executor.execute(
            RikkaBridgeExecutor.SUBMIT_DECISION_V1,
            JSONObject().put("decision", decision.toString()),
        )
        assertEquals(false, result.getBoolean("c3Called"))
        assertEquals(false, result.getBoolean("coordinatorCalled"))
        assertEquals("NoAction", result.getString("decisionKind"))
        assertEquals(false, result.getBoolean("realActionReachable"))
        assertEquals(0, result.getInt("actionExecuted"))
        assertFalse(result.has("available"))
    }

    @Test
    fun descriptorIsReadOnlyHasNoRequiredArgumentsAndResourceIsRetained() {
        val descriptor = McpToolCatalog.tool("get_rikka_observation_v1")
        assertNotNull(descriptor)
        assertEquals(McpToolRisk.READ, descriptor!!.risk)
        assertTrue(descriptor.required.isEmpty())
        assertEquals("object", descriptor.inputSchema.getString("type"))
        assertFalse(descriptor.inputSchema.optBoolean("additionalProperties", true))
        assertFalse(descriptor.inputSchema.has("required"))
        assertFalse(
            McpToolPolicySupport.requiresPhoneApproval(
                McpToolRisk.READ,
                McpPermissionLevel.READ_ONLY,
                McpToolPolicy.DEFAULT,
            ),
        )
        assertEquals(
            null,
            McpToolPolicySupport.hardRejectReason(
                McpToolRisk.READ,
                McpPermissionLevel.READ_ONLY,
                McpToolPolicy.DEFAULT,
                hasTrustedSession = false,
            ),
        )
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://rikka/observation/v1/latest" })
        assertEquals(McpToolRisk.WRITE, McpToolCatalog.tool("submit_rikka_decision_v1")!!.risk)
    }

    private fun executorSource(): String {
        val path = Path.of("src/main/java/top/azek431/hzzs/mcp/executor/RikkaBridgeExecutor.kt")
            .takeIf(Files::exists)
            ?: Path.of("app/src/main/java/top/azek431/hzzs/mcp/executor/RikkaBridgeExecutor.kt")
        return Files.readString(path)
    }

    private fun joined(
        location: UnitLocation,
        hero: String? = "hero",
        star: Int? = 1,
        frameSeq: Long = 12,
        shadow: JinChanShadowState = shadow(frameSeq = frameSeq),
    ): JinChanJoinedStateSnapshot {
        val ledger = JinChanUnitLedger().apply { createWithUid(41, hero, star, location, "test") }
        return joinedFromOwnership(JinChanOwnershipProjector.project(ledger), shadow)
    }

    private fun joinedFromOwnership(
        ownership: JinChanOwnershipSnapshot,
        shadow: JinChanShadowState,
    ): JinChanJoinedStateSnapshot {
        val context = JinChanActionContext(
            sessionId = shadow.sessionId,
            evidenceSessionId = shadow.sessionId,
            currentSequence = shadow.frameSeq,
            evidenceSequence = shadow.frameSeq,
            maximumSequenceAge = 0,
            stableState = JinChanStableState(null, JinChanStableUiState.UNKNOWN),
            ownership = ownership,
            ownershipSessionId = shadow.sessionId,
            ownershipSequence = shadow.frameSeq,
            expectedOwnershipRevision = ownership.sourceLedgerRevision,
        )
        val facts = JinChanSameEvidenceFacts(shadow, ownership, context)
        return (JinChanSameEvidenceAssembler.assemble(facts) as JinChanJoinResult.Assembled).snapshot
    }

    private fun shadow(
        frameSeq: Long = 12,
        bench: JinChanTypedShadowObservation<BenchObservation> = unknown(),
        levelStatus: ShadowFieldStatus = ShadowFieldStatus.UNKNOWN,
    ) = JinChanShadowState(
        JinChanFrameSessionId(7), frameSeq, 100, JinChanOrientation(3120, 1440, 0, 3120, 1440),
        unknown(), unknown(), JinChanTypedShadowObservation(levelStatus), unknown(), unknown(), bench,
        JinChanShadowTiming(99, 1, 1, 1, 4),
    )

    private fun <T> unknown() = JinChanTypedShadowObservation<T>(ShadowFieldStatus.UNKNOWN)
}
