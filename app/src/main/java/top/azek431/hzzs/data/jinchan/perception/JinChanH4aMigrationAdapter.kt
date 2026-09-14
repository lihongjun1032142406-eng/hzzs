package top.azek431.hzzs.data.jinchan.perception

import top.azek431.hzzs.data.jinchan.frame.FrameRect
import top.azek431.hzzs.data.jinchan.frame.JinChanCanonicalFrame

/**
 * H4-A integration seam. Frozen M5.8/M6.4 producer source, constants, validators, fixtures and hero
 * identity data are not present in this repository, so the default adapter deliberately performs no
 * recognition. It must be replaced only by a source-faithful migration, never guessed thresholds.
 */
class JinChanH4aMigrationAdapter(
    private val hudProducer: JinChanHudProducer = SourceEvidenceBlockedHudProducer,
    private val shopGate: JinChanShopGate = JinChanShopGate { _, _ -> JinChanShopGateInput(null, null) },
    private val shopProducer: JinChanShopProducer = SourceEvidenceBlockedShopProducer,
) {
    fun perceive(
        frame: JinChanCanonicalFrame,
        source: JinChanSourceFrame,
        levelExpRoi: FrameRect,
        goldRoi: FrameRect,
        shopRoi: FrameRect,
    ): JinChanH4aResult {
        val hud = runCatching { hudProducer.produce(frame, source, levelExpRoi, goldRoi) }
            .getOrElse { invalidHud(source, "HUD_PRODUCER_ERROR") }
        val gate = runCatching { shopGate.current(frame, source) }.getOrNull()
        val shop = when {
            gate == null -> JinChanPerceptionResult.Invalid(source, "SHOP_GATE_ERROR")
            gate.inGame == null || gate.uiState == null -> JinChanPerceptionResult.Unknown(source, SHOP_GATE_SOURCE_PENDING)
            gate.inGame != true || gate.uiState != SHOP_OPEN -> JinChanPerceptionResult.NotApplicable(source, "SHOP_GATE_CLOSED")
            else -> runCatching { shopProducer.produce(frame, source, shopRoi) }
                .getOrElse { JinChanPerceptionResult.Invalid(source, "SHOP_PRODUCER_ERROR") }
        }
        return JinChanH4aResult(hud, shop)
    }

    private fun invalidHud(source: JinChanSourceFrame, reason: String) = JinChanHudResult(
        JinChanPerceptionResult.Invalid(source, reason),
        JinChanPerceptionResult.Invalid(source, reason),
        JinChanPerceptionResult.Invalid(source, reason),
    )

    companion object {
        const val SHOP_OPEN = "SHOP_OPEN"
        const val SHOP_GATE_SOURCE_PENDING = "SHOP_GATE_SOURCE_PENDING"
        const val BLOCKED_SOURCE_EVIDENCE = "BLOCKED_SOURCE_EVIDENCE"
    }
}

data class JinChanH4aResult(val hud: JinChanHudResult, val shop: JinChanPerceptionResult<ShopObservation>)

private object SourceEvidenceBlockedHudProducer : JinChanHudProducer {
    override fun produce(frame: JinChanCanonicalFrame, source: JinChanSourceFrame, levelExpRoi: FrameRect, goldRoi: FrameRect) =
        JinChanHudResult(
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
            JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE),
        )
}

private object SourceEvidenceBlockedShopProducer : JinChanShopProducer {
    override fun produce(frame: JinChanCanonicalFrame, source: JinChanSourceFrame, shopRoi: FrameRect) =
        JinChanPerceptionResult.Unknown(source, JinChanH4aMigrationAdapter.BLOCKED_SOURCE_EVIDENCE)
}
