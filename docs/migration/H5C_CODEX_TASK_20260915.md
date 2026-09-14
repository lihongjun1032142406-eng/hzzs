# H5-C Codex Task — Ownership Snapshot

Branch: `jinchan-h5c-ownership-snapshot`
Base: `jinchan-clean-base` @ `d49de551ad4fada583849cc47b57e0d0a861644d`

Read first:
- `docs/migration/H5C_OWNERSHIP_SNAPSHOT_SOURCE_EVIDENCE_20260915.md`
- `docs/migration/H5_UNIT_LEDGER_SOURCE_EVIDENCE_20260915.md`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/ledger/JinChanUnitLedger.kt`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/ledger/JinChanObservationReconciler.kt`

## Goal

Implement H5-C as a pure Kotlin, immutable, deterministic Ownership Snapshot projector over H5-A Unit Ledger state. This is the read model that future Decision code can consume to know what units are currently owned and where confirmed units are located.

## Required implementation

Add a small projector under the JinChan ledger/state data layer, preferably `JinChanOwnershipSnapshot.kt`, plus focused JVM tests.

It must consume a `LedgerSnapshot` (preferred) or read `ledger.snapshot()` once and then project without mutating the ledger.

Output should include:
- ledger revision;
- deterministic ACTIVE unit projections preserving UID, heroKey?, starLevel?, equivalentCopies?, location and source as needed;
- deterministic known-hero aggregates;
- total ACTIVE unit count;
- Board ACTIVE count;
- Bench ACTIVE count;
- unresolved identity count;
- unresolved star count;
- unresolved location count for UNKNOWN/NONE;
- enough immutable information for future DecisionContext without carrying the mutable ledger itself.

Known hero aggregates should expose at least unit count and confirmed equivalent-copy total. If some unit for a hero has unknown star/equivalent copies, do not fabricate copies; expose an unresolved-star count/flag for that hero.

Only ACTIVE units count as current ownership. CONSUMED/history must not count.

UNKNOWN identity must remain UNKNOWN and must not be aggregated under a fake hero key.

BOARD/BENCH/UNKNOWN/NONE location semantics must remain unchanged.

Reuse H5-A star/equivalent-copy semantics exactly. Do not introduce new weights or scoring.

Projection must be deterministic: sort unit projections and hero aggregates by stable keys.

## Fail-closed / invariants

- Never mutate ledger state or revision.
- Never create/move/merge units.
- Never infer hero identity from location or perception.
- Never treat UNKNOWN/NONE location as Board/Bench.
- Never convert null star to 1.
- Never count CONSUMED units as owned.
- Do not perform GameData lookup/remapping in this phase.

## Explicitly out of scope

Do not implement or modify:
- H5-B reconciliation semantics;
- Bench CV/OCR;
- Board occupancy/visual identity algorithms;
- Shop purchase confirmation;
- Decision scoring/value engine;
- Actions/gesture/accessibility/shell;
- H6.

Do not change H4/H5-A/H5-B frozen semantics unless a regression test proves a real defect; report BLOCKED instead of silently redesigning them.

## Tests

Cover at least:
1. empty ledger snapshot;
2. ACTIVE Board and Bench units project with stable UID;
3. same hero across multiple units aggregates deterministically;
4. 1/2/3-star confirmed copies aggregate as 1/3/9;
5. CONSUMED unit excluded from current ownership;
6. unknown hero remains unresolved and is absent from hero aggregate;
7. unknown star does not fabricate equivalent copies and is surfaced unresolved;
8. UNKNOWN/NONE location remains unresolved and is not counted Board/Bench;
9. deterministic ordering independent of insertion order where source snapshot permits;
10. projector does not mutate ledger revision/state;
11. no durable references to Bitmap/Mat/IntArray/CapturedFrame/Activity/Context/action/gesture/perception objects.

Run:
```bash
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew --no-daemon --continue clean testDebugUnitTest lintDebug assembleDebug
git diff --check
```

If Codex lacks Android SDK, report `BLOCKED_ENVIRONMENT` for Gradle only; do not fabricate PASS. GitHub Actions is the final Gradle gate.

## Completion

Commit H5-C changes and create a PR targeting `jinchan-clean-base`. Do not merge it.

Report STATUS, HEAD_SHA, PR number/URL, changed files, tests/checks, safety audit, and BLOCKED items.

Safety must remain:
`ACTION_ENABLED=false`
`REAL_ACTION_REACHABLE=false`
`OVERLAY_DEFAULT_ENABLED=false`
`ACTION_EXECUTED=0`.