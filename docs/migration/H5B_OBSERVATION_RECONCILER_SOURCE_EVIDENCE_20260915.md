# H5-B Observation Reconciler — Source Evidence

Status: SOURCE_EVIDENCE_GREEN / IMPLEMENTATION_NOT_STARTED
Base: `jinchan-clean-base` @ `6cc848d001630dbf9ad52e79d8bdead3d95c1ec4`

## Frozen inputs

H5-A Native Unit Ledger is PASS / MERGED / FROZEN. Reuse `JinChanUnitLedger` as the only durable UID/location truth store. Do not reopen H5-A semantics without a demonstrated defect.

H4-B Board Occupancy is PASS / MERGED / FROZEN. H5-B consumes only structured `BoardOccupancyObservation`; it must not capture frames, inspect pixels, alter V8 geometry/model/thresholds, or invoke Board visual identity.

H4-C Bench Structured Contract is PASS / MERGED / FROZEN. H5-B consumes only structured `BenchObservation`; raw Bench producer remains YELLOW / DEFERRED / NOT IMPLEMENTED. H5-B must not invent Bench CV/OCR/geometry or claim full live Bench recognition.

## Mature source semantics

Current migration source `runtime/unit_ledger_shadow_runtime_phase_b_v1.js` is a GREEN semantic reference for conservative reconciliation:

- sidecar over structured perceptions only;
- no capture / OCR / CV / GameData lookup / gesture / Actions;
- compare previous and current occupancy snapshots;
- only reconcile when a movement has a unique explanation and the source location is already bound to a known UID;
- otherwise report `RECONCILE_REQUIRED` and never guess identity;
- observed baseline advances after a trusted observation, including unresolved changes, so one ambiguous delta is not replayed forever;
- action-confirmation, drag, sell, and H6 behaviors are explicitly deferred.

The old source implemented unique Board one-out/one-in reconciliation. H5-B extends that conservative idea to the already-frozen structured Bench contract, without adding raw Bench perception.

## H5-B required semantics

Trusted Board input:
- `status == AVAILABLE`
- `decision == SET`
- complete unique 4x7 cell coordinates
- every cell has non-null occupancy
- HOLD/CLEAR/INVALID/UNAVAILABLE must not be treated as fresh movement evidence.

Trusted Bench input for cross-zone reconciliation:
- `status == COMPLETE`
- unique zero-based slots
- each slot is `HERO` or `EMPTY`
- `UNKNOWN`, PARTIAL, INVALID, UNAVAILABLE do not authorize a cross-zone move.

Conservative unique movement cases allowed:

1. Board -> Board: exactly one occupied cell removed and exactly one added, with no Bench delta; source Board location maps to exactly one ACTIVE UID.
2. Bench -> Board: exactly one Bench HERO becomes EMPTY and exactly one Board occupied cell is added, with no competing removals/additions; source Bench slot maps to exactly one ACTIVE UID.
3. Board -> Bench: exactly one Board occupied cell is removed and exactly one Bench EMPTY becomes HERO, with no competing deltas; source Board location maps to exactly one ACTIVE UID. If destination Bench structured identity is known and ledger heroKey is known, they must match.
4. Bench -> Bench: exactly one Bench HERO becomes EMPTY and exactly one Bench EMPTY becomes HERO, with no Board delta; source Bench slot maps to exactly one ACTIVE UID. If destination identity is known and ledger heroKey is known, they must match.

Any multi-change, source UID ambiguity/missing UID, occupied destination conflict, identity mismatch, malformed snapshot, or same-frame mismatch must fail closed as unresolved / `RECONCILE_REQUIRED`; no ledger mutation.

No observation-only auto-create is authorized in H5-B. A new unexplained unit may remain unresolved until a later explicit ownership/identity binding path is designed. Shop observation alone is not ownership.

## Safety boundaries

H5-B must remain pure state reconciliation:

- `ACTION_ENABLED=false`
- `REAL_ACTION_REACHABLE=false`
- `ACTION_EXECUTED=0`
- no gesture / accessibility / shell action / drag / sell APIs
- no Bitmap / Mat / IntArray / CapturedFrame / frame lease retained in reconciler state
- no Board visual identity
- no raw Bench CV/OCR
- no H6 work

## Acceptance

Unit tests must cover initialization, no-change, all four unique move patterns, unknown source UID, ambiguous multi-delta, destination conflict, identity mismatch, HOLD/partial/invalid input, malformed duplicate coordinates/slots, frame-sequence mismatch, and no-mutation-on-reject. Existing H1-H5A tests must remain green.
