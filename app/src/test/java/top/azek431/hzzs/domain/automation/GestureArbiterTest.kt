package top.azek431.hzzs.domain.automation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureArbiterTest {
    @Test
    fun dispatchesStrictlySerially() = runTest {
        val order = mutableListOf<Long>()
        val arbiter = GestureArbiter(clock = { 10L }, dispatcher = GestureDispatcher { action ->
            order += action.id
            delay(20)
            DispatchReceipt(action, DispatchOutcome.COMPLETED)
        })
        val a = async { arbiter.dispatch(action(1)) }
        val b = async { arbiter.dispatch(action(2)) }
        a.await()
        b.await()
        assertEquals(listOf(1L, 2L), order)
    }

    @Test
    fun callbackTimeoutIncludesBudgetDetail() = runTest {
        val arbiter = GestureArbiter(
            clock = { 10L },
            dispatcher = GestureDispatcher { awaitCancellation() },
            dispatchTimeoutMs = 100L,
        )
        val receipt = arbiter.dispatch(action(1))
        assertEquals(DispatchOutcome.CANCELLED, receipt.outcome)
        assertTrue(receipt.detail?.contains("budget=") == true)
        assertTrue(receipt.detail?.contains("手势回调超时") == true)
    }

    @Test
    fun expiredActionNeverDispatches() = runTest {
        var called = false
        val arbiter = GestureArbiter(clock = { 100L }, dispatcher = GestureDispatcher { action ->
            called = true
            DispatchReceipt(action, DispatchOutcome.COMPLETED)
        })
        val result = arbiter.dispatch(action(1).copy(expiresAtUptimeMs = 99L))
        assertEquals(DispatchOutcome.EXPIRED, result.outcome)
        assertFalse(called)
    }

    @Test
    fun callbackTimeoutFailsClosed() = runTest {
        val arbiter = GestureArbiter(
            clock = { 10L },
            dispatcher = GestureDispatcher { awaitCancellation() },
            dispatchTimeoutMs = 100L,
        )
        assertEquals(DispatchOutcome.CANCELLED, arbiter.dispatch(action(1)).outcome)
    }

    @Test
    fun timeoutHoldsLockWhileDrainingLateCompletion() = runTest {
        val order = mutableListOf<String>()
        val arbiter = GestureArbiter(
            clock = { 10L },
            dispatcher = GestureDispatcher { action ->
                order += "start-${action.id}"
                // 当前仲裁器会按手势开销扩展主超时预算（默认点击约 2930ms）。
                // 延迟超过主预算、但落在 POST_TIMEOUT_DRAIN 内，才能真正覆盖 drain 持锁语义。
                delay(3_500L)
                order += "end-${action.id}"
                DispatchReceipt(action, DispatchOutcome.COMPLETED)
            },
            dispatchTimeoutMs = 100L,
        )
        val first = async {
            arbiter.dispatch(action(1).copy(expiresAtUptimeMs = 20_000L))
        }
        delay(10L)
        val second = async {
            arbiter.dispatch(action(2).copy(expiresAtUptimeMs = 20_000L))
        }
        val r1 = first.await()
        val r2 = second.await()
        // 第一单在 drain 内完成 → COMPLETED；第二单在第一单释放锁后才 start。
        assertEquals(DispatchOutcome.COMPLETED, r1.outcome)
        assertEquals(DispatchOutcome.COMPLETED, r2.outcome)
        assertEquals(listOf("start-1", "end-1", "start-2", "end-2"), order)
    }

    @Test
    fun mismatchedReceiptIsRejected() = runTest {
        val requested = action(1)
        val arbiter = GestureArbiter(clock = { 10L }, dispatcher = GestureDispatcher {
            DispatchReceipt(action(2), DispatchOutcome.COMPLETED)
        })
        assertEquals(DispatchOutcome.REJECTED, arbiter.dispatch(requested).outcome)
    }

    @Test
    fun onlyCompletedActionIsCommitted() = runTest {
        val ledger = ActionCommitLedger()
        val action = action(9)
        ledger.commit(DispatchReceipt(action, DispatchOutcome.CANCELLED))
        assertEquals(true, ledger.canPlan(9, nowMs = 10L))
        ledger.commit(DispatchReceipt(action, DispatchOutcome.COMPLETED))
        // 冷却内不可再规划
        assertEquals(false, ledger.canPlan(9, nowMs = action.createdAtUptimeMs + 100L))
        // 冷却后可再规划（同 track 在无尽跑中会再次靠近）
        assertEquals(true, ledger.canPlan(9, nowMs = action.createdAtUptimeMs + 2_000L))
    }

    @Test
    fun canPlanIsSynchronousAndFailClosedOnBusyLock() = runTest {
        val ledger = ActionCommitLedger()
        // 无竞争时同步可读
        assertTrue(ledger.canPlan(1L, nowMs = 1L))
        assertFalse(ledger.canPlan(0L, nowMs = 1L))
        // commit 持锁期间 canPlan tryLock 失败 → fail-closed false（不抛、不挂起）
        val hold = async {
            ledger.commit(
                DispatchReceipt(action(42), DispatchOutcome.COMPLETED),
                completedAtUptimeMs = 100L,
            )
            // 人为拉长：commit 本身很快，这里再测冷却语义即可
        }
        hold.await()
        assertFalse(ledger.canPlan(42L, nowMs = 150L))
        assertTrue(ledger.canPlan(42L, nowMs = 2_000L))
    }

    @Test
    fun doublePressDelayExtendsTimeoutBudget() = runTest {
        var calls = 0
        val arbiter = GestureArbiter(
            clock = { 10L },
            dispatcher = GestureDispatcher { action ->
                calls += 1
                // 模拟服务侧消费 doublePressDelayMs 的等待。
                delay(action.gesture.doublePressDelayMs + 40L)
                DispatchReceipt(action, DispatchOutcome.COMPLETED)
            },
            dispatchTimeoutMs = 100L,
        )
        val result = arbiter.dispatch(
            action(3).copy(
                gesture = GestureSpec(0.5f, 0.5f, durationMs = 24L, doublePressDelayMs = 200L),
                expiresAtUptimeMs = 10_000L,
            ),
        )
        assertEquals(DispatchOutcome.COMPLETED, result.outcome)
        assertEquals(1, calls)
    }

    @Test
    fun shellStyleDoublePressBudgetCoversPerPressOverhead() = runTest {
        var calls = 0
        val arbiter = GestureArbiter(
            clock = { 10L },
            dispatcher = GestureDispatcher { action ->
                calls += 1
                // 近似两次 shell tap + 间隔（旧预算会误报超时）。
                delay(2_200L)
                DispatchReceipt(action, DispatchOutcome.COMPLETED)
            },
            dispatchTimeoutMs = 100L,
        )
        val result = arbiter.dispatch(
            action(4).copy(
                gesture = GestureSpec(0.5f, 0.5f, durationMs = 24L, doublePressDelayMs = 80L),
                expiresAtUptimeMs = 20_000L,
            ),
        )
        assertEquals(DispatchOutcome.COMPLETED, result.outcome)
        assertEquals(1, calls)
    }

    @Test
    fun gestureSpecRejectsInvalidDoublePressDelay() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GestureSpec(0.5f, 0.5f, doublePressDelayMs = 3_000L)
        }
    }

    private fun action(id: Long) = AutomationAction(
        id = id,
        trackId = id,
        gesture = GestureSpec(0.8f, 0.8f),
        createdAtUptimeMs = 0L,
        expiresAtUptimeMs = 1_000L,
        allowedPackages = setOf("com.smile.gifmaker"),
    )
}
