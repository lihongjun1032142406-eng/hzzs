# H4-A HUD + Shop — Source Inventory

## Result

`STATUS=BLOCKED_SOURCE_EVIDENCE`（2026-09-14）。本记录不是 producer 实现通过证明；它是禁止猜测阈值、sub-ROI、OCR 和 identity policy 的迁移停止点。

## Inspected HZZS sources

- Repository/root and package instructions: `AGENTS.md`, `CLAUDE.md`, `app/CLAUDE.md`, Kotlin package `CLAUDE.md`, `data/vision/CLAUDE.md`.
- Frozen foundations: `JinChanFrameBridge.kt`, `JinChanFrameRuntime.kt`, `JinChanRoiRegistry.kt`, `JinChanShadowState.kt`, `JinChanShadowStatePublisher.kt`.
- Runtime integration: `VisionRuntimeController.kt` and current JVM tests under `app/src/test`.
- All local and fetched HZZS branch history (`origin/main`, `origin/jinchan-clean-base`, H1/H2/H3 task branches and the H4-A branch) was searched by symbol and historical path. No separate JinChanAI repository or migration-source bundle exists in the supplied workspace; the GitHub account exposes only HZZS.

## Authority found

- H1 provides one borrowed canonical-frame view and source/canonical mapping; H2 provides only global `LEVEL_EXP`, `GOLD`, and `SHOP` ROI boundaries; H3 provides metadata-only UNKNOWN observations.
- The task handoff provides behavioral outcomes (LEVEL 10/10, EXP 10/10, GOLD 8/10 best-effort; Shop gate and fail-closed rules), but it does not provide executable frozen recognizers, constants, validators, internal Shop geometry, identity tables, or fixtures. Outcomes alone are insufficient source evidence for a faithful port.

## Missing authority required to unblock

1. M5.8 frozen LEVEL, EXP and GOLD recognizer source, including any internal sub-ROI geometry, preprocessing/OCR constants and validators.
2. Frozen fixtures and expected observations that produced LEVEL 10/10, EXP 10/10 and GOLD 8/10.
3. M6.4/C6A/C6B `processFrame`, `buildShopPerception` and `shop_frame_perception_v1` source plus all slot/name-band/container geometry and constants.
4. The stabilized same-frame scene producer or equivalent authoritative `inGame`/`SHOP_OPEN` gate source.
5. Hero exact-match, aliases, `heroKey` mapping and GameData policy plus fixtures for unresolved identities and UNKNOWN-vs-EMPTY slots.

Until all applicable evidence is supplied, the migration adapter returns explicit UNKNOWN/INVALID/NOT_APPLICABLE results, the pending Shop gate never runs the expensive producer, Shadow State remains at the H3 UNKNOWN baseline, and H4-B must not begin.
