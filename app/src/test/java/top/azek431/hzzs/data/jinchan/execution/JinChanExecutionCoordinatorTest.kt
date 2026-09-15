package top.azek431.hzzs.data.jinchan.execution

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AutomationConfig
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.data.jinchan.action.ApprovedJinChanAction
import top.azek431.hzzs.data.jinchan.action.JinChanActionApprovalProvenance
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolverProvenance
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import top.azek431.hzzs.domain.automation.GestureSpec
import top.azek431.hzzs.service.automation.ForegroundWindowSnapshot
import top.azek431.hzzs.service.automation.GestureDispatcherFactory

class JinChanExecutionCoordinatorTest {
    @Test
    fun `production kill switch blocks before factory and dispatch`() = runBlocking {
        val factory = FakeFactory()
        val coordinator = coordinator(factory, productionEnabled = false)
        coordinator.startSession(enabledAutomation(), GestureBackend.ACCESSIBILITY)

        val result = coordinator.execute(resolved(), trackId = 7L)

        assertEquals(
            JinChanExecutionResult.Disabled(JinChanExecutionDisabledReason.PRODUCTION_ACTION_DISABLED),
            result,
        )
        assertEquals(0, factory.factoryCalls)
        assertEquals(0, factory.dispatchCalls)
    }

    @Test
    fun `runtime automation and disclaimer gates fail closed before factory`() = runBlocking {
        val factory = FakeFactory()
        val coordinator = coordinator(factory)

        coordinator.startSession(AutomationConfig(enabled = false), GestureBackend.ACCESSIBILITY)
        assertEquals(
            JinChanExecutionResult.Disabled(JinChanExecutionDisabledReason.AUTOMATION_DISABLED),
            coordinator.execute(resolved(), 7L),
        )
        coordinator.startSession(
            AutomationConfig(enabled = true, disclaimerAcceptedVersion = 0),
            GestureBackend.ACCESSIBILITY,
        )
        assertEquals(
            JinChanExecutionResult.Disabled(JinChanExecutionDisabledReason.DISCLAIMER_REQUIRED),
            coordinator.execute(resolved(), 7L),
        )
        coordinator.stopSession()
        assertEquals(
            JinChanExecutionResult.Disabled(JinChanExecutionDisabledReason.RUNTIME_INACTIVE),
            coordinator.execute(resolved(), 7L),
        )
        assertEquals(0, factory.factoryCalls)
        assertEquals(0, factory.dispatchCalls)
    }

    @Test
    fun `invalid track id fails closed without consuming action id`() = runBlocking {
        val factory = FakeFactory()
        val coordinator = coordinator(factory)
        coordinator.startSession(enabledAutomation(), GestureBackend.ACCESSIBILITY)

        assertEquals(JinChanExecutionResult.InvalidTrackId, coordinator.execute(resolved(), 0L))
        val completed = coordinator.execute(resolved(), 8L) as JinChanExecutionResult.Completed

        assertEquals(1L, completed.receipt.action.id)
        assertEquals(8L, completed.receipt.action.trackId)
        assertEquals(2_000L, completed.receipt.action.expiresAtUptimeMs - completed.receipt.action.createdAtUptimeMs)
        assertEquals(setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE), completed.receipt.action.allowedPackages)
    }

    @Test
    fun `action ids are positive monotonic and arbiter serializes dispatch`() = runBlocking {
        val factory = FakeFactory()
        val coordinator = coordinator(factory)
        coordinator.startSession(enabledAutomation(), GestureBackend.ACCESSIBILITY)

        val first = async { coordinator.execute(resolved(), 10L) }
        val second = async { coordinator.execute(resolved(), 11L) }
        val receipts = listOf(first.await(), second.await()).map {
            (it as JinChanExecutionResult.Completed).receipt
        }

        assertEquals(listOf(1L, 2L), receipts.map { it.action.id }.sorted())
        assertEquals(1, factory.maxInFlight)
        assertEquals(2, factory.dispatchCalls)
    }

    @Test
    fun `arbiter expiry maps to typed expired without dispatcher`() = runBlocking {
        val factory = FakeFactory()
        var calls = 0
        val coordinator = JinChanExecutionCoordinator(
            dispatcherFactory = factory,
            clock = { if (++calls >= 3) 2_001L else 0L },
            productionActionEnabled = { true },
        )
        coordinator.startSession(enabledAutomation(), GestureBackend.ACCESSIBILITY)

        val result = coordinator.execute(resolved(), 12L)

        assertTrue(result is JinChanExecutionResult.Expired)
        assertEquals(0, factory.factoryCalls)
        assertEquals(0, factory.dispatchCalls)
    }

    @Test
    fun `dispatcher outcomes map without ledger side effects`() = runBlocking {
        val cases = listOf(
            DispatchOutcome.REJECTED to JinChanExecutionResult.Rejected::class,
            DispatchOutcome.CANCELLED to JinChanExecutionResult.Cancelled::class,
            DispatchOutcome.EXPIRED to JinChanExecutionResult.Expired::class,
        )
        for ((outcome, expectedClass) in cases) {
            val factory = FakeFactory(outcome)
            val coordinator = coordinator(factory)
            coordinator.startSession(enabledAutomation(), GestureBackend.ACCESSIBILITY)
            assertEquals(expectedClass, coordinator.execute(resolved(), 20L)::class)
        }
    }

    private fun coordinator(
        factory: FakeFactory,
        productionEnabled: Boolean = true,
    ) = JinChanExecutionCoordinator(factory, clock = { 100L }, productionActionEnabled = { productionEnabled })

    private fun enabledAutomation() = AutomationConfig(
        enabled = true,
        disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
    )

    private fun resolved() = JinChanGestureResolveResult.Resolved(
        action = ApprovedJinChanAction(
            intent = JinChanActionIntent.RefreshShop,
            provenance = JinChanActionApprovalProvenance(
                JinChanFrameSessionId(1L),
                evidenceSequence = 2L,
                ownershipRevision = null,
                targetPackage = JinChanActionSafetyGate.JINCHAN_PACKAGE,
            ),
        ),
        gesture = GestureSpec(0.2f, 0.3f),
        provenance = JinChanGestureResolverProvenance("resolver", "profile"),
    )

    private class FakeFactory(
        private val outcome: DispatchOutcome = DispatchOutcome.COMPLETED,
    ) : GestureDispatcherFactory {
        var factoryCalls = 0
        var dispatchCalls = 0
        var inFlight = 0
        var maxInFlight = 0

        override fun dispatcher(backend: GestureBackend): GestureDispatcher {
            factoryCalls++
            return GestureDispatcher { action: AutomationAction ->
                dispatchCalls++
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                try {
                    DispatchReceipt(action, outcome)
                } finally {
                    inFlight--
                }
            }
        }

        override suspend fun snapshotForeground(backend: GestureBackend): ForegroundWindowSnapshot? = null

        override fun clearShellCaches() = Unit
    }
}
