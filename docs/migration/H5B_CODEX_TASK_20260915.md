# H5-B Codex Task — Observation Reconciler

Branch: `jinchan-h5b-observation-reconciler`
Base: `jinchan-clean-base` @ `6cc848d001630dbf9ad52e79d8bdead3d95c1ec4`

Read first:
- `docs/migration/H5B_OBSERVATION_RECONCILER_SOURCE_EVIDENCE_20260915.md`
- `docs/migration/H5_UNIT_LEDGER_SOURCE_EVIDENCE_20260915.md`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/ledger/JinChanUnitLedger.kt`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/perception/JinChanBenchContract.kt`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/perception/JinChanBoardOccupancy.kt`

## Goal

Implement H5-B as a pure Kotlin observation reconciler that compares trusted structured Bench evidence and trusted Board occupancy snapshots, recognizes only uniquely explainable location changes, and updates the existing H5-A Unit Ledger by stable UID.

This phase is reconciliation/state tracking, not perception research and not Actions.

## Required implementation

Add a small reconciler under the existing JinChan data layer, preferably `data/jinchan/ledger/JinChanObservationReconciler.kt`, plus JVM tests.

The reconciler must:
- own only previous trusted structured snapshots/metadata, never frames or pixels;
- consume `BoardOccupancyObservation?` and `BenchObservation?` plus `JinChanUnitLedger`;
- normalize trusted Board SET snapshots into occupied Board locations;
- normalize trusted COMPLETE Bench snapshots into zero-based HERO/EMPTY slot states;
- compute deltas against the previous trusted snapshot;
- move an existing ACTIVE ledger UID only for the four unique patterns frozen in the source-evidence document;
- use `ledger.move(...)` so UID remains stable;
- return an immutable result/status describing INIT / NO_CHANGE / RECONCILED_MOVE / RECONCILE_REQUIRED / INPUT_UNAVAILABLE or equivalent explicit states;
- expose enough diagnostics to identify source/destination/reason without retaining perception objects unnecessarily;
- advance trusted baselines after processing a trusted observation, including an unresolved delta, matching the mature source behavior.

## Fail-closed rules

Do not mutate the ledger when:
- source UID is absent or ambiguous;
- destination already maps to a different ACTIVE UID;
- more than one movement explanation exists;
- Board snapshot is not AVAILABLE+SET or is malformed;
- Bench snapshot is not COMPLETE for a cross-zone/Bench move or is malformed;
- both current trusted sources are used for one cross-zone decision but `frameSeq` differs;
- known destination Bench hero identity conflicts with the known ledger heroKey;
- any required fact is UNKNOWN.

UNKNOWN is not EMPTY.

Do not auto-create a unit merely because a new occupied Board cell or Bench HERO appears. New-unit binding is outside this H5-B task.

## Explicitly out of scope

Do not implement or modify:
- raw Bench CV/OCR/geometry/thresholds;
- Board V8 model, geometry, scene gate, banner guard, damage guard;
- Board visual identity;
- Shop purchase confirmation;
- drag/sell/action confirmation;
- accessibility/shell/gesture paths;
- H5-C Ownership Snapshot;
- H6 Action.

Do not change H5-A semantics unless a failing regression test proves a defect.

## Test gate

Add focused JVM tests for at least:
- first trusted snapshots -> INIT/no mutation;
- no change -> NO_CHANGE;
- unique Board->Board move preserves UID;
- unique Bench->Board move preserves UID;
- unique Board->Bench move preserves UID and validates known identity;
- unique Bench->Bench move preserves UID;
- unknown source UID -> RECONCILE_REQUIRED/no mutation;
- multiple added/removed positions -> RECONCILE_REQUIRED/no mutation;
- occupied destination conflict -> no mutation;
- known hero mismatch -> no mutation;
- Board HOLD does not create a movement delta;
- Bench PARTIAL/UNKNOWN does not authorize a Bench move;
- duplicate/malformed Board cells or Bench slots fail closed;
- frameSeq mismatch for a cross-zone candidate fails closed;
- unresolved trusted delta is not replayed on the next identical observation;
- no durable references to Bitmap/Mat/IntArray/CapturedFrame/Activity/Context/action/gesture classes.

Run:

```bash
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew --no-daemon --continue clean testDebugUnitTest lintDebug assembleDebug
git diff --check
```

If the current Codex environment lacks Android SDK, record that as environment-blocked and still run all possible static checks. GitHub Actions remains the final Gradle gate.

## Completion

Commit all H5-B changes to this branch and create a PR targeting `jinchan-clean-base`.

Final report must include:
- STATUS
- head SHA
- PR number/URL
- changed files
- tests/checks run
- any BLOCKED item
- safety values confirming no real action path.

Do not merge the PR yourself; Commander will review CI and merge/freeze after acceptance.
