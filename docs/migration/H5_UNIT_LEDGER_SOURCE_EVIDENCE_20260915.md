# H5 Unit Ledger Source Evidence — 2026-09-15

## Status

- H4-A HUD + Shop: PASS / MERGED / FROZEN
- H4-B Board Occupancy: PASS / MERGED / FROZEN
- H4-C Bench Structured Contract: PASS / MERGED / FROZEN
- H4-C raw Bench producer: YELLOW / deferred
- H5 source evidence: GREEN for Unit Ledger core and conservative reconciliation
- H6 Actions: NOT STARTED

Authoritative H5 base commit: `b57bcf4080812a8e5cd5c07bc79cd25672e52221` (`jinchan-clean-base`).

## Architectural decision

H5 MUST NOT turn into full-board hero recognition.

Production direction:

`Shop identity + Bench identity/slot evidence + Board occupancy -> stable UID ledger -> DecisionContext`

Board remains occupancy-first. Hero identity is established only from reliable structured evidence and then carried by ledger UID/location transitions. Unresolved board occupancy remains unresolved/UNKNOWN. Do not guess identity from composition, location, nearest known unit, or temporal coincidence unless the conservative reconciliation rule below is satisfied.

## Existing source selected for migration

### GREEN — core sources

1. `runtime/unit_ledger_v1.js`
   - Primary H5 source.
   - Stable UID-based unit records.
   - Event/revision model.
   - Unit states include ACTIVE / DRAGGING / SELL_PENDING / SOLD / CONSUMED / UNKNOWN.
   - Locations include BOARD(row,col), BENCH(slot), TRANSIT, UNKNOWN, NONE.
   - Star equivalent-copy semantics: 1-star=1, 2-star=3, 3-star=9.
   - Snapshot / aggregate semantics are reusable.

2. `runtime/unit_ledger_shadow_runtime_phase_b_v1.js`
   - Source for conservative observation reconciliation only.
   - Action-facing APIs MUST be excluded from H5.

3. `runtime/minimal_game_state_adapter.js`
   - Reference for H4 -> state projection and stable UNKNOWN semantics.

4. `perception/bench_perception.js`
   - Reference for structured Bench contract only.
   - 0-based output slot semantics after adaptation.
   - HERO / EMPTY / UNKNOWN remain distinct.
   - Star strictly 1|2|3|null.
   - Exact identity only; no fuzzy guessing.

5. `runtime/board_runtime_projection_v1.js`
   - Reference for Board occupancy projection into H5 observations.

### DEFER / DO NOT MIGRATE IN H5

- `runtime/board_visual_identity_runtime_v1.js`: do not make full-board identity a production prerequisite.
- Action confirmation APIs in old shadow runtime: `confirmBuy`, `confirmLogicalAction`, SELL/DEPLOY/BENCH confirmation entry points.
- Drag/action tracking: `beginDrag`, `moveDrag`, `endDrag`, action-bound UID tracker, sell-zone calibration.
- Any AutoJs6 action execution code.
- New Bench CV/OCR/geometry/threshold research.
- New Board hero-classification CV/OCR research.
- H6 action wiring.

## Frozen H5 semantic rules

### Stable unit identity

Every known owned unit is represented by a stable internal UID. UID is not a visual overlay marker and must not require drawing on screen.

A record may carry:

- uid
- heroKey (nullable/UNKNOWN)
- starLevel (1|2|3|null)
- equivalentCopies (1|3|9 when star known; otherwise unknown)
- location
- state
- createdRevision
- lastRevision
- evidence / diagnostics

### Location model

At minimum support:

- BOARD(row 1..4, col 1..7)
- BENCH(slot 0..8)
- UNKNOWN
- NONE

TRANSIT may exist internally only if it does not create an action dependency.

### Board policy

H4-B Board Occupancy is authoritative for whether a board cell is occupied.

Board occupancy does NOT imply hero identity.

Allowed:

`BOARD(r,c) = OCCUPIED, uid = unresolved`

Forbidden:

- infer hero from team composition
- infer hero from previous roster merely because count matches
- infer hero from nearest cell
- run full-board identity recognition as a required H5 path

### Bench policy

When structured Bench evidence is AVAILABLE, it may establish or confirm identity/location.

When Bench is UNKNOWN / UNAVAILABLE / PARTIAL, H5 must fail closed. Missing evidence must not be interpreted as EMPTY or removal.

Raw Bench producer remains YELLOW and is not part of H5-A completion criteria.

### Shop policy

A Shop hero is NOT an owned unit.

Shop observations are purchase candidates only. A shop appearance must never CREATE an owned Ledger unit.

Future action intent does not prove ownership. Ownership requires subsequent reliable observation.

### Conservative movement reconciliation

A movement may be inferred only when evidence is unique enough to avoid guessing.

Frozen safe rule from prior ledger work:

- exactly one previously occupied known location disappears
- exactly one previously empty location becomes occupied
- source is already bound to a known UID
- no competing/conflicting change exists

Then the same UID may MOVE to the new location.

Otherwise emit unresolved/reconcile-required state and keep identity UNKNOWN rather than guessing.

This applies to Board<->Board and may be extended to Bench<->Board only when the structured Bench evidence makes the source/destination transition unique and non-conflicting.

### Merge/star accounting

Preserve equivalent-copy model:

- 1-star = 1 copy
- 2-star = 3 copies
- 3-star = 9 copies

Do not fabricate a MERGE merely because total counts could mathematically form a higher star. MERGE requires explicit supported transition evidence.

### UNKNOWN discipline

UNKNOWN is a first-class value.

- UNKNOWN != EMPTY
- OCCUPIED with unknown identity is valid
- missing observation != removal
- unresolved UID binding is valid
- ambiguous transitions must remain unresolved

## H5 implementation slices

### H5-A — Native Unit Ledger Core

Translate the mature ledger data model to Kotlin without action APIs:

- immutable unit records
- UID generation/identity
- location model
- star/equivalent copies
- revision/event history
- create/snapshot/aggregate primitives
- explicit UNKNOWN/fail-closed semantics
- no Bitmap/Mat/pixel/frame-lease/action references in durable state

### H5-B — Shadow Observation Reconciler

Consume only frozen H4 typed observations:

- Board occupancy deltas
- Bench structured observations when available
- exact identity evidence only
- conservative unique-change reconciliation
- unresolved occupancy when identity cannot be proven
- current-frame/session freshness rules

### H5-C — Ownership Snapshot

Read-only projection for future DecisionContext:

- known owned units
- heroKey where known
- star/equivalentCopies
- board/bench locations
- hero-level aggregate counts where supported
- unresolved unit/location counts
- status/diagnostics/frameSeq/revision

No Action execution.

## Safety invariants

Must remain true throughout H5:

- `ACTION_ENABLED=false`
- `REAL_ACTION_REACHABLE=false`
- `OVERLAY_DEFAULT_ENABLED=false`
- `ACTION_EXECUTED=0`

No H5 change may restore legacy HZZS `AlgorithmPipeline`, `download_algorithm`, or `app://runtime/snapshot` behavior.

## Acceptance boundary

H5 success means reliable native ledger infrastructure and conservative state reconciliation over already-trusted observations.

It does NOT mean:

- full Bench raw visual recognition is solved
- full Board hero identity recognition is solved
- actions are enabled
- automatic buy/deploy/sell is enabled

Those remain separate future work and must not be silently pulled into H5.