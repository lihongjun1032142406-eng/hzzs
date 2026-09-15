package top.azek431.hzzs.data.jinchan.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.data.jinchan.action.ApprovedJinChanAction
import top.azek431.hzzs.data.jinchan.action.JinChanActionApprovalProvenance
import top.azek431.hzzs.data.jinchan.action.JinChanActionIntent
import top.azek431.hzzs.data.jinchan.action.JinChanActionSafetyGate
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolveResult
import top.azek431.hzzs.data.jinchan.action.JinChanGestureResolverProvenance
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.domain.automation.GestureSpec

class JinChanExecutionAdapterTest {
    @Test
    fun `click maps exactly and preserves resolved provenance`() {
        val gesture = GestureSpec(0.2f, 0.3f)
        val resolved = resolved(gesture)
        val result = JinChanExecutionAdapter.adapt(input(resolved))
        assertTrue(result is JinChanExecutionAdapterResult.Ready)
        result as JinChanExecutionAdapterResult.Ready
        assertSame(resolved, result.resolved)
        assertSame(gesture, result.automationAction.gesture)
        assertEquals(11L, result.automationAction.id)
        assertEquals(22L, result.automationAction.trackId)
        assertEquals(100L, result.automationAction.createdAtUptimeMs)
        assertEquals(200L, result.automationAction.expiresAtUptimeMs)
        assertEquals(setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE), result.automationAction.allowedPackages)
        assertTrue(result.automationAction.requiredWindowClassPrefixes.isEmpty())
        assertEquals(0, result.automationAction.retryCount)
        assertEquals("jinchan-h6b-v1", result.resolved.provenance.resolverId)
        assertEquals("profile-v1", result.resolved.provenance.profileId)
    }

    @Test
    fun `drag gesture is passed through exactly`() {
        val gesture = GestureSpec(0.1f, 0.2f, 0.7f, 0.8f, 300L)
        val resolved = resolved(gesture)
        val result = JinChanExecutionAdapter.adapt(input(resolved)) as JinChanExecutionAdapterResult.Ready
        assertSame(gesture, result.automationAction.gesture)
        assertSame(resolved, result.resolved)
    }

    @Test
    fun `invalid target package is rejected`() {
        val resolved = resolved(GestureSpec(0.2f, 0.3f), targetPackage = "example.invalid")
        assertRejected(input(resolved), JinChanExecutionAdapterRejectionReason.INVALID_TARGET_PACKAGE)
    }

    @Test
    fun `invalid action id is rejected`() {
        assertRejected(input(resolved(GestureSpec(0.2f, 0.3f)), actionId = 0L), JinChanExecutionAdapterRejectionReason.INVALID_ACTION_ID)
    }

    @Test
    fun `invalid track id is rejected`() {
        assertRejected(input(resolved(GestureSpec(0.2f, 0.3f)), trackId = 0L), JinChanExecutionAdapterRejectionReason.INVALID_TRACK_ID)
    }

    @Test
    fun `invalid time window is rejected`() {
        assertRejected(input(resolved(GestureSpec(0.2f, 0.3f)), createdAt = 201L, expiresAt = 200L), JinChanExecutionAdapterRejectionReason.INVALID_TIME_WINDOW)
    }

    private fun resolved(
        gesture: GestureSpec,
        targetPackage: String = JinChanActionSafetyGate.JINCHAN_PACKAGE,
    ): JinChanGestureResolveResult.Resolved {
        val action = ApprovedJinChanAction(
            intent = JinChanActionIntent.RefreshShop,
            provenance = JinChanActionApprovalProvenance(
                sessionId = JinChanFrameSessionId(7L),
                evidenceSequence = 9L,
                ownershipRevision = 3L,
                targetPackage = targetPackage,
            ),
        )
        return JinChanGestureResolveResult.Resolved(
            action = action,
            gesture = gesture,
            provenance = JinChanGestureResolverProvenance("jinchan-h6b-v1", "profile-v1"),
        )
    }

    private fun input(
        resolved: JinChanGestureResolveResult.Resolved,
        actionId: Long = 11L,
        trackId: Long = 22L,
        createdAt: Long = 100L,
        expiresAt: Long = 200L,
    ) = JinChanExecutionAdapterInput(
        resolved = resolved,
        actionId = actionId,
        trackId = trackId,
        createdAtUptimeMs = createdAt,
        expiresAtUptimeMs = expiresAt,
    )

    private fun assertRejected(
        input: JinChanExecutionAdapterInput,
        reason: JinChanExecutionAdapterRejectionReason,
    ) {
        val result = JinChanExecutionAdapter.adapt(input)
        assertEquals(JinChanExecutionAdapterResult.Rejected(reason), result)
    }
}
