package top.azek431.hzzs.data.jinchan.action

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.extension
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

class JinChanActionSafetyGateTest {
    private val session = JinChanFrameSessionId(7)

    @Test
    fun validBuyIsApproved() {
        val result = gate(JinChanActionIntent.BuyShopSlot(2), context())
        val approved = assertIs<JinChanActionGateResult.Approved>(result).action
        assertEquals(JinChanActionIntent.BuyShopSlot(2), approved.intent)
        assertEquals(100, approved.provenance.evidenceSequence)
        assertEquals(JinChanActionSafetyGate.JINCHAN_PACKAGE, approved.provenance.targetPackage)
    }

    @Test
    fun buyRejectsEveryGlobalAndShopFailure() {
        assertRejected(JinChanActionRejectionReason.ACTION_DISABLED, JinChanActionIntent.BuyShopSlot(0), context(actionEnabled = false))
        assertRejected(JinChanActionRejectionReason.STALE_EVIDENCE, JinChanActionIntent.BuyShopSlot(0), context(currentSequence = 104))
        assertRejected(JinChanActionRejectionReason.SESSION_MISMATCH, JinChanActionIntent.BuyShopSlot(0), context(evidenceSessionId = JinChanFrameSessionId(8)))
        assertRejected(JinChanActionRejectionReason.INVALID_TARGET_PACKAGE, JinChanActionIntent.BuyShopSlot(0), context(targetPackage = "*"))
        assertRejected(JinChanActionRejectionReason.INVALID_TARGET_PACKAGE, JinChanActionIntent.BuyShopSlot(0), context(targetPackage = null))
        assertRejected(JinChanActionRejectionReason.INVALID_SLOT, JinChanActionIntent.BuyShopSlot(5), context())
        assertRejected(JinChanActionRejectionReason.SHOP_EVIDENCE_UNAVAILABLE, JinChanActionIntent.BuyShopSlot(0), context(shop = null))
        assertRejected(JinChanActionRejectionReason.SHOP_EVIDENCE_UNTRUSTED, JinChanActionIntent.BuyShopSlot(0), context(shopTrusted = false))
        assertRejected(
            JinChanActionRejectionReason.SHOP_SLOT_UNKNOWN,
            JinChanActionIntent.BuyShopSlot(1),
            context(shop = shop(ShopContentType.UNKNOWN)),
        )
    }

    @Test
    fun validMoveIsApprovedAndFailuresAreTyped() {
        val move = JinChanActionIntent.MoveUnit(1, UnitLocation.Bench(2))
        assertIs<JinChanActionGateResult.Approved>(gate(move, moveContext()))
        assertRejected(JinChanActionRejectionReason.INVALID_UID, JinChanActionIntent.MoveUnit(0, UnitLocation.Bench(2)), moveContext())
        assertRejected(JinChanActionRejectionReason.UNIT_NOT_ACTIVE, JinChanActionIntent.MoveUnit(99, UnitLocation.Bench(2)), moveContext())

        val consumed = JinChanUnitLedger().apply {
            createWithUid(1, "hero", 1, UnitLocation.Board(1, 1))
            createWithUid(2, "hero", 1, UnitLocation.Board(1, 2))
            createWithUid(3, "hero", 1, UnitLocation.Board(1, 3))
            merge(listOf(1, 2, 3), "hero", 2, UnitLocation.Board(1, 1), resultUid = 4)
        }
        assertRejected(JinChanActionRejectionReason.UNIT_NOT_ACTIVE, move, moveContext(consumed))

        for (location in listOf(UnitLocation.Unknown, UnitLocation.None)) {
            assertRejected(JinChanActionRejectionReason.UNIT_LOCATION_UNKNOWN, move, moveContext(ledger(location = location)))
        }
        assertRejected(JinChanActionRejectionReason.INVALID_DESTINATION, JinChanActionIntent.MoveUnit(1, UnitLocation.Board(0, 1)), moveContext())
        assertRejected(JinChanActionRejectionReason.INVALID_DESTINATION, JinChanActionIntent.MoveUnit(1, UnitLocation.None), moveContext())
        assertRejected(JinChanActionRejectionReason.SAME_DESTINATION, JinChanActionIntent.MoveUnit(1, UnitLocation.Board(1, 1)), moveContext())
        assertRejected(JinChanActionRejectionReason.BOARD_EVIDENCE_UNTRUSTED, JinChanActionIntent.MoveUnit(1, UnitLocation.Board(2, 1)), moveContext().copy(boardTrusted = false))
        assertRejected(JinChanActionRejectionReason.BENCH_EVIDENCE_UNTRUSTED, move, moveContext().copy(benchTrusted = false))
        assertRejected(JinChanActionRejectionReason.RECONCILIATION_UNRESOLVED, move, moveContext().copy(reconcileStatus = ObservationReconcileStatus.RECONCILE_REQUIRED))
        assertRejected(JinChanActionRejectionReason.OWNERSHIP_AMBIGUOUS, move, moveContext().copy(ownershipAmbiguous = true))
    }

    @Test
    fun validSellIsApprovedAndDestructiveChecksFailClosed() {
        val sell = JinChanActionIntent.SellUnit(1)
        assertIs<JinChanActionGateResult.Approved>(gate(sell, sellContext()))
        assertRejected(JinChanActionRejectionReason.UNIT_IDENTITY_UNKNOWN, sell, sellContext(ledger(hero = null)))
        assertRejected(JinChanActionRejectionReason.UNIT_LOCATION_UNKNOWN, sell, sellContext(ledger(location = UnitLocation.Unknown)))
        assertRejected(JinChanActionRejectionReason.STALE_EVIDENCE, sell, sellContext().copy(ownershipSequence = 90))
        assertRejected(JinChanActionRejectionReason.OWNERSHIP_REVISION_MISMATCH, sell, sellContext().copy(expectedOwnershipRevision = 2))
        assertRejected(JinChanActionRejectionReason.OWNERSHIP_AMBIGUOUS, sell, sellContext().copy(ownershipAmbiguous = true))
        assertRejected(JinChanActionRejectionReason.ACTION_UI_EVIDENCE_MISSING, sell, sellContext().copy(uiEvidence = JinChanActionUiEvidence()))
        assertRejected(
            JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE,
            sell,
            sellContext().copy(stableState = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN)),
        )
    }

    @Test
    fun shopControlsRequireExplicitCompatibleEvidence() {
        val allowed = context(uiEvidence = JinChanActionUiEvidence(refreshShopAllowed = true, buyXpAllowed = true))
        assertIs<JinChanActionGateResult.Approved>(gate(JinChanActionIntent.RefreshShop, allowed))
        assertIs<JinChanActionGateResult.Approved>(gate(JinChanActionIntent.BuyXp, allowed))
        assertRejected(JinChanActionRejectionReason.ACTION_UI_EVIDENCE_MISSING, JinChanActionIntent.RefreshShop, context())
        assertRejected(JinChanActionRejectionReason.ACTION_UI_EVIDENCE_MISSING, JinChanActionIntent.BuyXp, context())
        val boardUi = allowed.copy(stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT))
        assertRejected(JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE, JinChanActionIntent.RefreshShop, boardUi)
        assertRejected(JinChanActionRejectionReason.INCOMPATIBLE_UI_SCENE, JinChanActionIntent.BuyXp, boardUi)
    }

    @Test
    fun everyActionKindRejectsWhenDisabled() {
        val intents = listOf(
            JinChanActionIntent.BuyShopSlot(0),
            JinChanActionIntent.MoveUnit(1, UnitLocation.Bench(1)),
            JinChanActionIntent.SellUnit(1),
            JinChanActionIntent.RefreshShop,
            JinChanActionIntent.BuyXp,
        )
        intents.forEach { assertRejected(JinChanActionRejectionReason.ACTION_DISABLED, it, context(actionEnabled = false)) }
    }

    @Test
    fun sameInputIsDeterministicAndProjectionDoesNotMutateLedgerOrSnapshot() {
        val ledger = ledger()
        val beforeLedger = ledger.snapshot()
        val ownership = JinChanOwnershipProjector.project(beforeLedger)
        val context = moveContext(ledger).copy(ownership = ownership)
        val intent = JinChanActionIntent.MoveUnit(1, UnitLocation.Bench(3))
        assertEquals(gate(intent, context), gate(intent, context))
        assertEquals(beforeLedger, ledger.snapshot())
        assertEquals(ownership, context.ownership)
    }

    @Test
    fun productionActionSourcesHaveNoExecutionTransportReferences() {
        val root = Path.of("src/main/java/top/azek431/hzzs/data/jinchan/action")
            .takeIf(Files::exists)
            ?: Path.of("app/src/main/java/top/azek431/hzzs/data/jinchan/action")
        val forbidden = listOf(
            "GestureSpec", "AutomationAction", "GestureArbiter", "GestureDispatcher",
            "GestureDispatcherFactory", "HzzsAccessibilityService", "dispatchGesture",
            "android.accessibilityservice", "Shizuku", "root shell input",
        )
        val sources = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.map(Files::readString).toList()
        }
        forbidden.forEach { token ->
            assertEquals("forbidden production token: $token", false, sources.any { token in it })
        }
    }

    private fun gate(intent: JinChanActionIntent, context: JinChanActionContext) =
        JinChanActionSafetyGate.evaluate(intent, context)

    private fun assertRejected(reason: JinChanActionRejectionReason, intent: JinChanActionIntent, context: JinChanActionContext) {
        assertEquals(reason, assertIs<JinChanActionGateResult.Rejected>(gate(intent, context)).reason)
    }

    private fun context(
        actionEnabled: Boolean = true,
        targetPackage: String? = JinChanActionSafetyGate.JINCHAN_PACKAGE,
        evidenceSessionId: JinChanFrameSessionId = session,
        currentSequence: Long = 101,
        shop: ShopObservation? = shop(),
        shopTrusted: Boolean = true,
        uiEvidence: JinChanActionUiEvidence = JinChanActionUiEvidence(),
    ) = JinChanActionContext(
        actionEnabled = actionEnabled,
        targetPackage = targetPackage,
        sessionId = session,
        evidenceSessionId = evidenceSessionId,
        currentSequence = currentSequence,
        evidenceSequence = 100,
        maximumSequenceAge = 2,
        stableState = JinChanStableState(true, JinChanStableUiState.SHOP_OPEN),
        shop = shop,
        shopTrusted = shopTrusted,
        uiEvidence = uiEvidence,
    )

    private fun moveContext(source: JinChanUnitLedger = ledger()): JinChanActionContext {
        val ownership = JinChanOwnershipProjector.project(source)
        return context().copy(
            stableState = JinChanStableState(true, JinChanStableUiState.BOARD_OR_COMBAT),
            boardTrusted = true,
            benchTrusted = true,
            ownership = ownership,
            ownershipSessionId = session,
            ownershipSequence = 100,
            expectedOwnershipRevision = ownership.sourceLedgerRevision,
            reconcileStatus = ObservationReconcileStatus.NO_CHANGE,
        )
    }

    private fun sellContext(source: JinChanUnitLedger = ledger()) = moveContext(source).copy(
        uiEvidence = JinChanActionUiEvidence(sellAllowed = true),
    )

    private fun ledger(hero: String? = "hero", location: UnitLocation = UnitLocation.Board(1, 1)) =
        JinChanUnitLedger().apply { createWithUid(1, hero, 1, location) }

    private fun shop(second: ShopContentType = ShopContentType.HERO_CARD) = ShopObservation(
        ShopObservationStatus.AVAILABLE,
        100,
        List(5) { index -> ShopSlotObservation(index, if (index == 1) second else ShopContentType.HERO_CARD) },
    )

    private inline fun <reified T> assertIs(value: Any): T {
        assertTrue(value is T)
        return value as T
    }
}
