# H4-C Codex Task — Bench Structured Contract Migration

## Mission

Implement **H4-C Bench structured contract migration** in HZZS native Kotlin, starting from branch `jinchan-h4c-bench-contract`.

This is a **contract/typed-shadow migration only**. Do not invent a Bench pixel/CV/OCR producer. Current production evidence says raw Bench perception remains YELLOW and live runner supplies `benchPerception:null`.

Read first:

- `docs/migration/H4C_BENCH_CONTRACT_SOURCE_EVIDENCE_20260915.md`
- existing H1/H2/H3/H4-A/H4-B JinChan Kotlin implementation and tests
- existing typed Shadow State patterns for Shop/HUD/Board

Base lineage must contain H4-B merge commit:

`ac61d37f9de3523f49932454bd77d0acb31235fa`

If not, stop with `BLOCKED_BASE_MISMATCH`.

## Required implementation

### 1. Native structured Bench model

Add immutable Kotlin types for a structured Bench observation. Preserve these logical semantics:

- slot indices are 0-based
- stable slot order
- logical slot states: `HERO`, `EMPTY`, `UNKNOWN`
- `UNKNOWN != EMPTY`
- hero identity may be null
- star level may only be `1`, `2`, `3`, or null
- malformed/unsupported evidence must fail closed

Use names consistent with current JinChan Kotlin style. Do not couple the durable model to pixels, Bitmap, Mat, CapturedFrame, leases, gestures, or action classes.

### 2. Structured input adapter/producer

Implement a pure/read-only structured Bench adapter that accepts upstream structured slot evidence and normalizes it into the native Bench observation contract.

It must not perform image analysis, OCR, pixel sampling, geometric search, or thresholding.

Required behavior:

- preserve 0-based slot identity
- never shift later slots to fill a missing slot
- missing slot -> `UNKNOWN`
- duplicate slot index -> fail closed diagnostic/status
- out-of-range slot index -> fail closed diagnostic/status
- malformed slot object -> fail closed diagnostic/status
- unsupported star value -> star becomes null and observation must not silently claim fully clean input
- identity unmatched -> keep state/evidence conservative; do not guess hero
- `EMPTY` may only come from explicit structured EMPTY evidence
- unknown/missing/unmatched must never be coerced to EMPTY

If the historical structured contract exposes status distinctions such as complete/partial/error/unavailable, preserve equivalent semantics in Kotlin. Do not invent a production-readiness claim.

### 3. Identity resolution boundary

If the current HZZS code already exposes the migrated authoritative Shop/GameData exact-resolution path, reuse that abstraction for structured Bench hero resolution.

Do not implement fuzzy matching.
Do not research or change hero aliases.
Do not add thresholds.
Do not hardcode new game data.

If no suitable native exact resolver exists, keep the adapter dependency injectable/nullable and preserve hero as unknown rather than inventing resolution logic.

### 4. Shadow State integration

Upgrade the H3 Shadow State `bench` field from the current untyped ROI placeholder to a typed Bench observation field, while preserving fail-closed behavior.

Important: there is currently **no authorized raw Bench frame producer**. Therefore ordinary `publishFrame(...)` without explicit upstream Bench structured input must continue to publish Bench as UNKNOWN/unavailable, not fake EMPTY/HERO data.

Add the minimum compatible input seam needed to inject structured Bench evidence into the publisher for tests/future runtime wiring. Keep defaults backward-compatible so existing call sites compile unchanged.

Every published Bench observation must be tied to the current accepted frame sequence/metadata. No stale observation object may masquerade as current-frame SET data.

Session start/stop must clear any retained Bench state. Prefer no temporal retention unless current source evidence explicitly requires it.

### 5. Tests

Add focused JVM tests covering at least:

- stable 0-based slot order
- missing slot remains UNKNOWN and does not shift later slots
- explicit EMPTY stays EMPTY
- UNKNOWN is not EMPTY
- HERO with exact resolved identity
- identity unmatched remains conservative/no fuzzy guess
- star values 1/2/3 accepted
- invalid star fails closed to null with partial/error diagnostic
- duplicate slot index fail-closed
- out-of-range slot index fail-closed
- publisher with no structured Bench input remains UNKNOWN/unavailable
- publisher with injected structured Bench input uses current frame sequence
- session reset clears any Bench retained state
- published model graph contains no pixel/lease/action references

Do not use tests that assume a pixel/CV/OCR Bench algorithm exists.

### 6. Documentation

Update only the minimal relevant migration/architecture notes.

The wording must clearly distinguish:

`H4-C structured Bench contract migration`

from

`production-ready raw Bench vision producer`.

The latter is **not** achieved in this phase.

## Forbidden scope

Do not implement or modify:

- Bench pixel occupancy detector
- Bench OCR
- Bench star visual detector
- new Bench geometry calibration
- new CV features/thresholds/weights
- Board Identity
- Unit Ledger / H5
- Decision
- H6 Actions
- GestureDispatcher paths
- buy/sell/reroll/move
- overlay enablement
- old HZZS AlgorithmPipeline / algorithm packs / download_algorithm / `app://runtime/snapshot`

Do not reopen H4-A or H4-B frozen algorithms.

## Safety invariants

Must remain true:

```text
ACTION_ENABLED=false
REAL_ACTION_REACHABLE=false
OVERLAY_DEFAULT_ENABLED=false
ACTION_EXECUTED=0
```

## Quality gates

Run the repository's normal verification path, including unit tests, lint, and debug APK build as already configured by GitHub Actions.

Before opening a PR, ensure:

- Kotlin compiles
- all new/changed unit tests pass
- lint passes
- debug APK assembles
- no production action path was introduced
- no durable Shadow State object contains pixels/frame leases

## PR rules

Open a PR with:

- base: `jinchan-clean-base`
- head: your H4-C implementation branch
- do **not** merge

PR description must explicitly state:

- this phase migrates only the structured Bench contract
- raw Bench pixel producer remains YELLOW / not production-ready
- no new thresholds/algorithms were invented
- Action/Overlay safety baseline remains OFF

## Stop conditions

Stop and report `BLOCKED_SOURCE_EVIDENCE` if any required implementation would require guessing:

- pixel geometry
- CV thresholds/features
- OCR rules
- visual star detection
- fuzzy identity rules

Stop and report `BLOCKED_BASE_MISMATCH` if the H4-B merge commit is not in history.

Otherwise implement, test, push, open the PR, and stop for Commander review. Do not merge.