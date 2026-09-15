package top.azek431.hzzs.data.jinchan.execution

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AutomationConfig
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.data.jinchan.action.JinChanActionContext
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionRejectionReason
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanActionUiEvidence
import top.azek431.hzzs.data.jinchan.action.JinChanCoordinateProfile
import top.azek431.hzzs.data.jinchan.action.JinChanGestureBlockedReason
import top.azek431.hzzs.data.jinchan.action.JinChanNormalizedPoint
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.JinChanOwnershipProjector
import top.azek431.hzzs.data.jinchan.ledger.JinChanUnitLedger
import top.azek431.hzzs.data.jinchan.ledger.ObservationReconcileStatus
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.data.jinchan.perception.JinChanStableState
import top.azek431.hzzs.data.jinchan.perception.JinChanStableUiState
import top.azek431.hzzs.data.jinchan.perception.ShopContentType
import top.azek431.hzzs.data.jinchan.perception.ShopObservation
import top.azek431.hzzs.data.jinchan.perception.ShopObservationStatus
import top.azek431.hzzs.data.jinchan.perception.ShopSlotObservation
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import top.azek431.hzzs.domain.automation.GestureSpec
import top.azek431.hzzs.service.automation.ForegroundWindowSnapshot
import top.azek431.hzzs.service.automation.GestureDispatcherFactory

class JinChanRuntimeActionIntegrationTest {
    private val session = JinChanFrameSessionId(7L)

    @Test fun `production kill switch blocks before fake dispatch`() = runBlocking {
        val fixture = fixture(productionEnabled = false)
        val result = fixture.integration.submit(refreshRequest()) as JinChanRuntimeActionResult.Execution
        assertEquals(
            JinChanExecutionResult.Disabled(JinChanExecutionDisabledReason.PRODUCTION_ACTION_DISABLED),
            result.result,
        )
        assertEquals(0, fixture.factory.dispatchCalls)
        assertNull(result.provenance.actionId)
    }

    @Test fun `invalid identities gate rejection and unavailable profile never dispatch`() = runBlocking {
        val fixture = fixture()
        assertInputBlocked(
            fixture.integration.submit(refreshRequest().copy(requestId = "")),
            JinChanRuntimeInputBlockedReason.INVALID_REQUEST_ID,
        )
        assertInputBlocked(
            fixture.integration.submit(refreshRequest().copy(trackId = 0L)),
            JinChanRuntimeInputBlockedReason.INVALID_TRACK_ID,
        )
        val gateRejected = fixture.integration.submit(
            refreshRequest().copy(context = shopContext().copy(actionEnabled = false)),
        ) as JinChanRuntimeActionResult.GateRejected
        assertEquals(JinChanActionRejectionReason.ACTION_DISABLED, gateRejected.reason)
        val profileBlocked = fixture.integration.submit(
            refreshRequest().copy(coordinateProfile = null),
        ) as JinChanRuntimeActionResult.ResolverBlocked
        assertEquals(JinChanGestureBlockedReason.PROFILE_UNAVAILABLE, profileBlocked.reason)
        assertEquals(0, fixture.factory.dispatchCalls)
    }

    @Test fun `source evidence must match the H6-A authoritative snapshot`() = runBlocking {
        val fixture = fixture()
        val request = moveRequest()
        val source = requireNotNull(request.sourceEvidence)
        assertInputBlocked(
            fixture.integration.submit(request.copy(sourceEvidence = null)),
            JinChanRuntimeInputBlockedReason.SOURCE_EVIDENCE_REQUIRED,
        )
        assertInputBlocked(
            fixture.integration.submit(
                request.copy(sourceEvidence = source.copy(evidenceSequence = 99L)),
            ),
            JinChanRuntimeInputBlockedReason.SOURCE_PROVENANCE_MISMATCH,
        )
        assertInputBlocked(
            fixture.integration.submit(
                request.copy(sourceEvidence = source.copy(location = UnitLocation.Unknown)),
            ),
            JinChanRuntimeInputBlockedReason.SOURCE_EVIDENCE_INVALID,
        )
        assertEquals(0, fixture.factory.dispatchCalls)
    }

    @Test fun `sell without calibrated target remains blocked`() = runBlocking {
        val fixture = fixture()
        val result = fixture.integration.submit(sellRequest(profile(sellTarget = null)))
            as JinChanRuntimeActionResult.ResolverBlocked
        assertEquals(JinChanGestureBlockedReason.SELL_TARGET_UNCALIBRATED, result.reason)
        assertEquals(0, fixture.factory.dispatchCalls)
    }

    @Test fun `runtime automation and disclaimer gates never dispatch`() = runBlocking {
        val cases = listOf(
            null to JinChanExecutionDisabledReason.RUNTIME_INACTIVE,
            AutomationConfig(enabled = false) to JinChanExecutionDisabledReason.AUTOMATION_DISABLED,
            AutomationConfig(enabled = true, disclaimerAcceptedVersion = 0) to
                JinChanExecutionDisabledReason.DISCLAIMER_REQUIRED,
        )
        for ((automation, reason) in cases) {
            val fixture = fixture(startAutomation = automation)
            val result = fixture.integration.submit(refreshRequest()) as JinChanRuntimeActionResult.Execution
            assertEquals(JinChanExecutionResult.Disabled(reason), result.result)
            assertEquals(0, fixture.factory.dispatchCalls)
        }
    }

    @Test fun `synthetic calibrated click is dispatched exactly once with provenance preserved`() = runBlocking {
        val fixture = fixture()
        val result = fixture.integration.submit(refreshRequest(trackId = 41L))
            as JinChanRuntimeActionResult.Execution
        val receipt = (result.result as JinChanExecutionResult.Completed).receipt
        assertEquals(1, fixture.factory.dispatchCalls)
        assertEquals(GestureSpec(0.2f, 0.3f), receipt.action.gesture)
        assertEquals(41L, receipt.action.trackId)
        assertEquals("request-refresh", result.provenance.requestId)
        assertEquals(41L, result.provenance.trackId)
        assertEquals(session, result.provenance.sessionId)
        assertEquals(100L, result.provenance.evidenceSequence)
        assertEquals("jinchan-h6b-v1", result.provenance.resolverId)
        assertEquals("synthetic-test", result.provenance.profileId)
        assertEquals(receipt.action.id, result.provenance.actionId)
    }

    @Test fun `synthetic move and test-only sell each dispatch one exact drag`() = runBlocking {
        val fixture = fixture()
        val move = fixture.integration.submit(moveRequest(trackId = 51L)) as JinChanRuntimeActionResult.Execution
        val moveGesture = (move.result as JinChanExecutionResult.Completed).receipt.action.gesture
        assertEquals(GestureSpec(0.1f, 0.2f, 0.7f, 0.8f, 300L), moveGesture)

        val sellProfile = profile(sellTarget = JinChanNormalizedPoint(0.9f, 0.1f))
        val sell = fixture.integration.submit(sellRequest(sellProfile, trackId = 52L))
            as JinChanRuntimeActionResult.Execution
        val sellGesture = (sell.result as JinChanExecutionResult.Completed).receipt.action.gesture
        assertEquals(GestureSpec(0.1f, 0.2f, 0.9f, 0.1f, 300L), sellGesture)
        assertEquals(2, fixture.factory.dispatchCalls)
    }

    @Test fun `fake terminal outcomes are returned without retry`() = runBlocking {
        for (outcome in listOf(DispatchOutcome.REJECTED, DispatchOutcome.CANCELLED, DispatchOutcome.EXPIRED)) {
            val fixture = fixture(outcome = outcome)
            val result = fixture.integration.submit(refreshRequest()) as JinChanRuntimeActionResult.Execution
            val expected = when (outcome) {
                DispatchOutcome.REJECTED -> JinChanExecutionResult.Rejected::class
                DispatchOutcome.CANCELLED -> JinChanExecutionResult.Cancelled::class
                DispatchOutcome.EXPIRED -> JinChanExecutionResult.Expired::class
                DispatchOutcome.COMPLETED -> error("not a terminal test case")
            }
            assertEquals(expected, result.result::class)
            assertEquals(1, fixture.factory.dispatchCalls)
        }
    }

    @Test fun `two synthetic requests use the existing serialized arbiter`() = runBlocking {
        val fixture = fixture(dispatchDelayMs = 20L)
        val first = async { fixture.integration.submit(refreshRequest(trackId = 61L)) }
        val second = async { fixture.integration.submit(refreshRequest(trackId = 62L)) }
        first.await()
        second.await()
        assertEquals(2, fixture.factory.dispatchCalls)
        assertEquals(1, fixture.factory.maxInFlight)
    }

    @Test fun `C3 source has no forbidden execution ownership or producer logic`() {
        val path = Path.of(
            "src/main/java/top/azek431/hzzs/data/jinchan/execution/JinChanRuntimeActionIntegration.kt",
        ).takeIf(Files::exists) ?: Path.of(
            "app/src/main/java/top/azek431/hzzs/data/jinchan/execution/JinChanRuntimeActionIntegration.kt",
        )
        val source = Files.readString(path)
        listOf(
            "GestureArbiter(", ".dispatcher(", "dispatchGesture", "HzzsAccessibilityService",
            "Shizuku", "RootGestureDispatcher", "ShellInput", "pixel", "ROI", "DecisionEngine",
        ).forEach { token -> assertEquals("forbidden C3 token: $token", false, token in source) }
    }

    private fun fixture(
        productionEnabled: Boolean = true,
        startAutomation: AutomationConfig? = enabledAutomation(),
        outcome: DispatchOutcome = DispatchOutcome.COMPLETED,
        dispatchDelayMs: Long = 0L,
    ): Fixture {
        val factory = FakeFactory(outcome, dispatchDelayMs)
        val coordinator = JinChanExecutionCoordinator(
            dispatcherFactory = factory,
            clock = { 100L },
            productionActionEnabled = { productionEnabled },
        )
        startAutomation?.let { coordinator.startSession(it, GestureBackend.ACCESSIBILITY) }
        return Fixture(JinChanRuntimeActionIntegration(coordinator), factory)
    }

    private fun refreshRequest(trackId: Long = 31L) = JinChanRuntimeActionRequest(
        requestId = "request-refresh",
        intent = JinChanActionIntent.RefreshShop,
        context = shopContext(),
        trackId = trackId,
        coordinateProfile = profile(refreshShop = JinChanNormalizedPoint(0.2f, 0.3f)),
    )

    private fun moveRequest(trackId: Long = 32L): JinChanRuntimeActionRequest {
        val context = ownershipContext()
        return JinChanRuntimeActionRequest(
            requestId = "request-move",
            intent = JinChanActionIntent.MoveUnit(1L, UnitLocation.Bench(2)),
            context = context,
            trackId = trackId,
            coordinateProfile = profile(),
            sourceEvidence = sourceEvidence(context),
        )
    }

    private fun sellRequest(
        coordinateProfile: JinChanCoordinateProfile,
        trackId: Long = 33L,
    ): JinChanRuntimeActionRequest {
        val context = ownershipContext(sell = true)
        return JinChanRuntimeActionRequest(
            requestId = "request-sell",
            intent = JinChanActionIntent.SellUnit(1L),
            context = context,
            trackId = trackId,
            coordinateProfile = coordinateProfile,
            sourceEvidence = sourceEvidence(context),
        )
    }

    private fun shopContext() = JinChanActionContext(
        actionEnabled = true,
        targetPackage = JinChanActionSafetyGate.JINCHAN_PACKAGE,
        sessionId = session,
        evidenceSessionId = session,
        currentSequence = 101L,
        evidenceSequence = 100L,
        maximumSequenceAge = 2L,
        stableState = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN),
        shop = ShopObservation(
            ShopObservationStatus.AVAILABLE,
            100L,
            List(5) { ShopSlotObservation(it, ShopContentType.HERO_CARD) },
        ),
        shopTrusted = true,
        uiEvidence = JinChanActionUiEvidence(refreshShopAllowed = true, buyXpAllowed = true),
    )

    private fun ownershipContext(sell: Boolean = false): JinChanActionContext {
        val ledger = JinChanUnitLedger().apply {
            createWithUid(1L, "hero", 1, UnitLocation.Board(1, 1))
        }
        val ownership = JinChanOwnershipProjector.project(ledger)
        return shopContext().copy(
            stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT),
            boardTrusted = true,
            benchTrusted = true,
            ownership = ownership,
            ownershipSessionId = session,
            ownershipSequence = 100L,
            expectedOwnershipRevision = ownership.sourceLedgerRevision,
            reconcileStatus = ObservationReconcileStatus.NO_CHANGE,
            uiEvidence = JinChanActionUiEvidence(sellAllowed = sell),
        )
    }

    private fun sourceEvidence(context: JinChanActionContext) = JinChanActionSourceEvidence(
        location = UnitLocation.Board(1, 1),
        sessionId = session,
        evidenceSequence = context.evidenceSequence,
        ownershipRevision = requireNotNull(context.expectedOwnershipRevision),
    )

    private fun profile(
        refreshShop: JinChanNormalizedPoint? = null,
        sellTarget: JinChanNormalizedPoint? = null,
    ): JinChanCoordinateProfile {
        val boardSource = UnitLocation.Board(1, 1)
        return JinChanCoordinateProfile(
            profileId = "synthetic-test",
            boardCells = mapOf(boardSource to JinChanNormalizedPoint(0.1f, 0.2f)),
            benchSlots = List(9) { index ->
                if (index == 2) JinChanNormalizedPoint(0.7f, 0.8f) else null
            },
            refreshShop = refreshShop,
            sellTarget = sellTarget,
        )
    }

    private fun enabledAutomation() = AutomationConfig(
        enabled = true,
        disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
    )

    private fun assertInputBlocked(
        result: JinChanRuntimeActionResult,
        reason: JinChanRuntimeInputBlockedReason,
    ) {
        assertTrue(result is JinChanRuntimeActionResult.InputBlocked)
        assertEquals(reason, (result as JinChanRuntimeActionResult.InputBlocked).reason)
    }

    private data class Fixture(
        val integration: JinChanRuntimeActionIntegration,
        val factory: FakeFactory,
    )

    private class FakeFactory(
        private val outcome: DispatchOutcome,
        private val dispatchDelayMs: Long,
    ) : GestureDispatcherFactory {
        var dispatchCalls = 0
        var inFlight = 0
        var maxInFlight = 0

        override fun dispatcher(backend: GestureBackend): GestureDispatcher = GestureDispatcher { action: AutomationAction ->
            dispatchCalls++
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (dispatchDelayMs > 0L) delay(dispatchDelayMs)
                DispatchReceipt(action, outcome)
            } finally {
                inFlight--
            }
        }

        override suspend fun snapshotForeground(backend: GestureBackend): ForegroundWindowSnapshot? = null
        override fun clearShellCaches() = Unit
    }
}
