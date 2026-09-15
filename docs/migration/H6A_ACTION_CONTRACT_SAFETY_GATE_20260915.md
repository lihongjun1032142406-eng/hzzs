# H6-A JinChan Action Contract + Safety Gate

Status: IMPLEMENTED / SHADOW-ONLY
Base: H5 frozen baseline `3cb89e774f144c7100049c93d42416a8c8d6265f`
Branch: `jinchan-h6-action-precheck`

## Goal

Introduce a pure Kotlin JinChan semantic action contract and fail-closed safety gate. H6-A may express and approve/reject an intended game action, but MUST NOT resolve final screen coordinates or reach any real input transport.

## Frozen boundary

Pipeline for H6-A:

`H5 state/ownership evidence -> JinChanActionIntent -> JinChanActionSafetyGate -> Approved/Rejected`

Explicitly out of scope:
- GestureSpec generation / final screen coordinates
- GestureArbiter calls
- GestureDispatcher / GestureDispatcherFactory calls
- Accessibility dispatchGesture
- Shizuku/Root shell input
- Decision production wiring
- new capture/OCR/CV
- changes to frozen H1-H5 algorithms

## Semantic action contract

Initial action kinds:
- `BuyShopSlot(slot)` where shop slot is 0-based `0..4`
- `MoveUnit(uid, destination)` where destination is an H5-compatible unit location
- `SellUnit(uid)`
- `RefreshShop`
- `BuyXp`

No semantic action may contain raw screen pixel coordinates or normalized gesture coordinates.

## Evidence context

The gate consumes caller-supplied immutable evidence. It must not perform capture/OCR/CV or mutate H5 state. Context must preserve enough provenance to reject stale/ambiguous actions, including where applicable:
- session identity
- frame sequence / evidence age
- UI/scene validity
- H5 ownership revision/snapshot
- Board/Bench/Shop availability/trust state
- target package
- unresolved reconciliation/ambiguity state

Do not invent evidence that is not currently exposed by the frozen H1-H5 models. If an action cannot be proven safe from available evidence, reject it with an explicit reason.

## Safety gate rules

Global fail-closed rules:
1. Master action enable must be explicitly true; default false.
2. Session/evidence mismatch or stale evidence -> reject.
3. Unsupported/invalid UI or scene -> reject.
4. Invalid intent parameters -> reject.
5. UNKNOWN is never EMPTY or SAFE; required UNKNOWN evidence -> reject.
6. Ambiguous/unresolved reconciliation -> reject when relevant to the action.
7. Target foreground package must be explicitly allowed for JinChan; no empty-set wildcard semantics at the H6-A boundary.
8. Duplicate/conflicting pending intent evidence -> reject where represented by the contract.

Action-specific minimums:
- BUY: SHOP_OPEN-compatible evidence; slot 0..4; trusted/available shop observation; target slot not UNKNOWN; fresh matching session/frame evidence.
- MOVE: UID exists and is ACTIVE; current location known; destination legal and different; required Board/Bench evidence trusted; no relevant unresolved reconciliation.
- SELL: UID exists and is ACTIVE; identity/location confirmed; sell-compatible UI evidence; no ownership/reconciliation ambiguity. SELL is destructive and must fail closed on any missing evidence.
- REFRESH / BUY_XP: only when the corresponding UI/scene evidence explicitly permits the action; otherwise reject.

## Result contract

Gate result is typed, not Boolean:
- Approved: immutable approved semantic action plus evidence/provenance needed by the next phase.
- Rejected: explicit stable rejection reason.

`ApprovedJinChanAction` is a capability boundary. Future H6-B/H6-C code must accept approved actions rather than raw intents when resolving/executing actions.

H6-A itself must not provide any method that dispatches an approved action.

## Tests / acceptance

Unit tests must cover at least:
- valid BUY approval; stale/wrong-session/UNKNOWN/invalid-slot BUY rejection
- valid MOVE approval; unknown UID, CONSUMED UID, UNKNOWN location, same destination, unresolved reconciliation rejection
- valid SELL approval; unknown identity/location, stale ownership, ambiguity rejection
- master action disabled -> every action rejected
- deterministic immutable results/reasons
- gate does not mutate H5 ledger/snapshot
- durable H6-A production sources contain no real-input reachability (no dispatchGesture, shell input, GestureDispatcher/Factory or GestureArbiter invocation)

Hard safety acceptance after H6-A:

`ACTION_ENABLED=false`
`REAL_ACTION_REACHABLE=false`
`ACTION_EXECUTED=0`

## Next phases (not authorized by H6-A)

- H6-B: Approved semantic action -> coordinate/gesture resolver; may remain shadow-only.
- H6-C: controlled integration with existing HZZS GestureArbiter/Dispatcher transport. Real input requires separate explicit authorization and acceptance.

## Implemented boundary

- Production contract and pure gate: `app/src/main/java/top/azek431/hzzs/data/jinchan/action/`.
- The gate uses caller-supplied session/sequence age, stable UI, package, explicit UI permissions, H5 ownership revision,
  Board/Bench/Shop trust, and reconciliation status. It reads no Android clock and mutates no H5 value.
- `actionEnabled` and every caller-supplied permission default to false. Approval remains a data-only capability without
  execution methods, gesture specifications, or coordinates.
- Focused JVM tests cover each action, fail-closed reasons, determinism/non-mutation, and forbidden-source scanning.

Hard safety state remains `ACTION_ENABLED=false`, `REAL_ACTION_REACHABLE=false`, `ACTION_EXECUTED=0`.
