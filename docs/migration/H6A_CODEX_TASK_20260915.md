# H6-A Codex Task — JinChan Action Contract + Safety Gate

## Starting point

Repository: `lihongjun1032142406-eng/hzzs`
Branch: `jinchan-h6-action-precheck`
Expected branch head before implementation: `877b9eea33c4c0f1e98b51d9f6a6c4243cb461aa`
Authoritative frozen H5 base: `3cb89e774f144c7100049c93d42416a8c8d6265f`

Read first:
- `docs/migration/H6A_ACTION_CONTRACT_SAFETY_GATE_20260915.md`
- H5 ledger/ownership/reconciler production models and tests
- existing automation domain/service code only to enforce the H6-A no-transport boundary

Do not reopen or redesign H1-H5.

## Objective

Implement H6-A as pure Kotlin semantic action contract + fail-closed safety gate. H6-A must be shadow-only and have zero real-input reachability.

## Required implementation

Create a small JinChan-specific action package under the existing JinChan data/runtime architecture (choose the narrowest package consistent with repository structure).

Implement:

1. `JinChanActionIntent` sealed contract with exactly the initial semantic actions:
   - `BuyShopSlot(slot)` — 0-based slot 0..4
   - `MoveUnit(uid, destination)`
   - `SellUnit(uid)`
   - `RefreshShop`
   - `BuyXp`

   No intent may contain pixel or normalized gesture coordinates.

2. Immutable `JinChanActionContext` / evidence model using only evidence actually available from frozen H1-H5 models or explicit caller-supplied primitive gate evidence. Preserve provenance needed for session/freshness/UI/scene/ownership/package/ambiguity checks. Do not invent perception facts.

3. Typed stable rejection reasons (`enum` or sealed type). Do not return bare Boolean or free-form-only failure strings.

4. `JinChanActionGateResult`:
   - `Approved(ApprovedJinChanAction)`
   - `Rejected(reason, ...)`

5. `ApprovedJinChanAction` as an immutable capability object containing the semantic intent and approval provenance. It MUST expose no dispatch/execute method and no GestureSpec.

6. `JinChanActionSafetyGate` pure projector/validator. It must not mutate ledger, ownership, perception, state, or context.

## Mandatory safety semantics

Global:
- `actionEnabled` defaults/fails closed to false. If false, every intent rejects.
- explicit target package must equal the JinChan package `com.tencent.jkchess`; blank/null/wildcard semantics reject.
- stale/mismatched session/evidence rejects.
- invalid/unsupported UI/scene rejects.
- UNKNOWN required evidence rejects; UNKNOWN is never EMPTY or SAFE.
- unresolved/ambiguous reconciliation rejects when relevant.
- invalid intent parameters reject.

BUY:
- slot must be 0..4.
- require SHOP_OPEN-compatible, fresh trusted/available shop evidence.
- target slot must be explicitly known/non-UNKNOWN.

MOVE:
- UID > 0 and must resolve to an ACTIVE H5 ownership unit.
- current location must be known.
- destination must be a legal H5 BOARD or BENCH location and different from current location.
- relevant Board/Bench evidence must be trusted.
- unresolved reconciliation relevant to the move rejects.

SELL:
- UID must resolve to ACTIVE H5 ownership.
- identity and current location must be confirmed/known.
- require explicit sell-compatible UI/scene evidence.
- any relevant ownership/reconciliation ambiguity rejects.

REFRESH_SHOP / BUY_XP:
- require explicit compatible UI/scene evidence. Missing evidence rejects.

Freshness/session implementation must be deterministic and testable; inject/accept timestamps/sequence values rather than reading Android clocks inside the gate.

## Hard forbidden dependencies in H6-A production source

Do not import/call/reference as execution dependencies:
- `GestureSpec`
- `AutomationAction`
- `GestureArbiter`
- `GestureDispatcher`
- `GestureDispatcherFactory`
- `HzzsAccessibilityService`
- `dispatchGesture`
- Shizuku/Root shell input
- Android accessibility APIs
- capture/OCR/CV

Do not generate final screen coordinates.

## Tests

Add focused unit tests covering at minimum:
- valid BUY approved
- BUY rejects disabled, stale, wrong session, wrong package, invalid slot, UNKNOWN/untrusted shop slot/evidence
- valid MOVE approved
- MOVE rejects unknown UID, CONSUMED/non-active UID, UNKNOWN/NONE location, illegal/same destination, untrusted Board/Bench, unresolved reconcile
- valid SELL approved
- SELL rejects unknown identity/location, stale ownership/evidence, ambiguity, incompatible UI
- REFRESH and BUY_XP positive + missing/incompatible evidence cases
- all action kinds reject when `actionEnabled=false`
- deterministic approval/rejection for same input
- projection does not mutate H5 ledger/snapshot
- structural test/source scan proves H6-A production files have no forbidden transport/action execution references

Use existing H5 test builders/models where practical; do not weaken H5 contracts to make tests easier.

## Validation

Run in one batch where possible:
- `git diff --check`
- repository Python quality/resource checks used by current CI
- targeted H6-A tests
- full unit tests/lint/debug build if environment supports it

If local SDK/JDK prevents Gradle, report `BLOCKED_ENVIRONMENT` for that check only; do not change build semantics merely to bypass the local environment. GitHub Actions is the final build gate.

## Delivery

Commit all H6-A implementation/tests/docs to the current branch. Do not merge.

Create a PR targeting `jinchan-clean-base` if the environment permits. Report:
- STATUS
- branch/head SHA
- changed files
- test/quality/build results
- PR number/link if created
- confirmation that `ACTION_ENABLED=false`, `REAL_ACTION_REACHABLE=false`, `ACTION_EXECUTED=0`

Acceptance requires Commander semantic review + green GitHub CI. H6-B/H6-C are not authorized by this task.
