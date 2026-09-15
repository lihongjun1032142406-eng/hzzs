# H6-C Execution Wiring Plan — 2026-09-15

Base: H6-B frozen merge `424a08f684aa54d891a191ffe28e72e4343b5868`.

H6-C is split into three independently reviewable stages. No stage may invent coordinates, bypass H6-A, or mutate H1-H5 perception/ledger semantics.

## C1 — Execution Envelope Adapter

Goal: bridge a successfully resolved H6-B `GestureSpec` into the existing automation domain as an `AutomationAction`, but do not dispatch it.

Required behavior:
- Input is only `JinChanGestureResolveResult.Resolved` plus explicit execution metadata supplied by caller.
- Exact package allow-list must be `{ "com.tencent.jkchess" }`; empty allow-list is forbidden.
- IDs, track ID, uptime timestamps and TTL are explicit immutable inputs; reject invalid/expired metadata fail-closed.
- Preserve the H6-B gesture unchanged.
- Output is a typed prepared/enveloped result or typed BLOCKED reason.
- No `GestureArbiter.dispatch`, no dispatcher/service/shell/accessibility calls.
- JUnit4 tests prove package lock, gesture preservation, TTL/ID validation and source-level transport prohibition.

Acceptance: pure adapter tests green; `REAL_ACTION_REACHABLE=false`, `ACTION_EXECUTED=0`.

## C2 — Armed Dispatch Boundary

Goal: add the single JinChan-specific dispatch boundary that consumes C1 output and delegates only to the existing `GestureArbiter`.

Required behavior:
- Explicit runtime arming flag defaults `false`.
- Recheck exact target package immediately before dispatch.
- Recheck expiry immediately before dispatch.
- Only an already prepared C1 action may enter.
- Delegate to existing `GestureArbiter`; do not create a second arbiter or call `GestureDispatcher` directly.
- Return typed receipt/result; no ledger ownership mutation and no decision planning.
- Tests use fake dispatcher/arbiter path and prove disabled/package/expiry fail closed and at-most-one delegated dispatch.

Acceptance: default path remains non-executing. Tests may exercise fake transport only. Production `ACTION_ENABLED=false`.

## C3 — Runtime Integration / Shadow-to-Armed Switch

Goal: wire H6-A → H6-B → C1 → C2 into one JinChan action runtime entry point with production defaults still disabled.

Required behavior:
- Pipeline order is fixed: Safety Gate approval → Gesture Resolver → Execution Envelope → Armed Dispatch Boundary.
- Any H6-A rejection or H6-B/C1 block terminates without dispatch.
- Coordinate profile must be caller-provided explicit calibration; missing profile remains BLOCKED.
- `SELL_ZONE_CALIBRATED=false`: production/default profile must not contain a sell target; Sell remains blocked.
- No decision engine expansion, no coordinate calibration, no H1-H5 mutation.
- Expose structured stage/result provenance for logs/tests.
- End-to-end JUnit4 tests cover disabled default, missing calibration, H6-A rejection, successful synthetic test-only click/drag through fake dispatcher, and no dispatch on any blocked stage.

Acceptance before any real-device enablement:
- CI green on exact PR head and post-merge SHA.
- Production defaults: `ACTION_ENABLED=false`, `REAL_ACTION_REACHABLE=false`, `ACTION_EXECUTED=0`, `SELL_ZONE_CALIBRATED=false`.
- Real-device arming/calibration is a later separately authorized phase; H6-C completion does not authorize it.

## Frozen exclusions

No new action semantics; no raw coordinates; no ROI-as-hitbox; no normalized-to-pixel duplicate converter; no direct Accessibility/Shizuku/Root/shell input calls from JinChan; no old `AlgorithmPipeline`, `download_algorithm`, or `app://runtime/snapshot`; no H1-H5 reopening.
