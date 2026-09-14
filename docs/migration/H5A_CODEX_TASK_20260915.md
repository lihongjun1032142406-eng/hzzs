# Codex Task — H5-A Native Unit Ledger Core

## Mission

Implement H5-A Native Unit Ledger Core in Kotlin on branch `jinchan-h5-unit-ledger`.

This is a migration of mature ledger semantics, not a redesign. Do not start H5-B/H5-C, do not add new perception algorithms, and do not add any Action execution path.

Read first:

- `docs/migration/H5_UNIT_LEDGER_SOURCE_EVIDENCE_20260915.md`
- existing H1/H2/H3/H4 Kotlin runtime and tests
- especially the current typed Shadow State contracts produced by H4-B and H4-C

Authoritative branch base before H5 docs: `b57bcf4080812a8e5cd5c07bc79cd25672e52221`.

## Required implementation

Create the smallest native Kotlin ledger core that preserves the frozen semantics below.

### 1. Immutable Unit Ledger model

Define immutable types equivalent in role to the old `UNIT_LEDGER_V1` core. Names may follow existing repository conventions, but the model must support:

- stable `uid`
- nullable/unknown `heroKey`
- `starLevel: Int?` accepting only 1, 2, 3
- equivalent-copy semantics: 1 -> 1, 2 -> 3, 3 -> 9, unknown -> unknown
- location types:
  - BOARD(row 1..4, col 1..7)
  - BENCH(slot 0..8)
  - UNKNOWN
  - NONE
- unit lifecycle/state sufficient for H5 core:
  - ACTIVE
  - CONSUMED
  - UNKNOWN

Do NOT expose DRAGGING / SELL_PENDING / SOLD as live Action-facing functionality in H5-A. If historical compatibility requires enum values, they must remain inert data only and must not create callable action pathways.

### 2. Revision/event core

Provide deterministic read-only ledger evolution with revision tracking.

Minimum supported core events/operations:

- CREATE known or unresolved unit
- MOVE an existing UID to a validated location
- MERGE only when explicitly requested by trusted future reconciliation input; never auto-infer from arithmetic
- mark CONSUMED if needed by MERGE semantics
- snapshot
- aggregate/read model

Do NOT add `confirmBuy`, `confirmLogicalAction`, SELL confirmation, deploy confirmation, Bench action confirmation, drag APIs, touch injection, accessibility actions, shell actions, or any other execution bridge.

### 3. Validation / fail-closed behavior

Must reject or safely degrade:

- duplicate UID creation
- invalid Board row/col
- invalid Bench slot
- invalid star outside 1..3
- MOVE of nonexistent UID
- ambiguous/invalid MERGE membership
- self-merge / duplicate merge inputs

No exception should corrupt ledger state. Prefer result/status objects consistent with current project conventions.

UNKNOWN must remain first-class:

- unknown identity is allowed
- unknown location is allowed
- UNKNOWN is never silently converted to EMPTY/NONE

### 4. Snapshot / aggregation

Expose an immutable snapshot suitable for later H5-B/H5-C use.

At minimum the snapshot must expose:

- current revision
- stable ordered unit records
- active units
- unresolved-identity count
- units by Board/Bench location where unambiguous
- hero-level aggregate equivalent copies only when heroKey and star are known
- diagnostics/status for invalid operations if repository architecture supports it

Do not consume Shop observations here. A shop hero is not owned.

### 5. Durable-state safety

The H5-A ledger object graph MUST NOT retain:

- Bitmap
- Mat/OpenCV objects
- image buffers / raw pixel arrays
- CapturedFrame
- frame lease / session lease objects
- Android View/Context/Activity references
- action executor/controller/gesture references

Keep it pure Kotlin/JVM-testable wherever possible.

## Explicitly out of scope

Do NOT implement any of the following in this task:

- Board hero identity CV/OCR/classification
- Bench raw producer, OCR, CV, geometry or thresholds
- Board occupancy changes (H4-B is frozen)
- H5-B observation reconciliation
- H5-C DecisionContext/ownership projection beyond minimal snapshot/aggregate primitives
- Shop -> owned-unit creation
- automatic UID binding based on guesses
- action confirmation APIs
- buy/deploy/sell/move execution
- overlay UI
- H6
- Qwen integration
- legacy HZZS `AlgorithmPipeline`, `download_algorithm`, or `app://runtime/snapshot`

## Required tests

Add focused JVM tests for at least:

1. CREATE generates/preserves stable UID and revision.
2. Duplicate UID is rejected/fail-closed without mutating prior state.
3. Board location bounds: rows 1..4, cols 1..7 only.
4. Bench location bounds: slots 0..8 only.
5. MOVE preserves UID and updates only location/revision.
6. MOVE unknown UID fails without mutation.
7. Star accepts only 1/2/3/null.
8. Equivalent copies exactly 1/3/9/null.
9. Unknown heroKey is legal and counted as unresolved, never guessed.
10. MERGE consumes the explicitly supplied source records and produces exactly the requested valid resulting star/record semantics; invalid/ambiguous merge is rejected without mutation.
11. Snapshot ordering is deterministic.
12. Aggregate does not count unknown hero/star as known equivalent copies.
13. Durable ledger graph contains no pixel/frame/action references.
14. Session/frame concepts are absent from H5-A core unless only scalar metadata is used; no frame object retention.

Use repository test style and existing quality checks.

## Validation commands

Run the repository's existing checks. At minimum:

```bash
python tools/quality/check_resources.py
python tools/quality/check_project.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

If the repository wrapper requires the existing wrapper invocation pattern, use that exact repository pattern.

## Final report

Report:

- files changed
- exact ledger API/model added
- event/revision behavior
- validation/fail-closed behavior
- test results
- quality/lint/build results
- confirmation that no perception/CV/OCR/action path was added
- confirmation of:
  - `ACTION_ENABLED=false`
  - `REAL_ACTION_REACHABLE=false`
  - `OVERLAY_DEFAULT_ENABLED=false`
  - `ACTION_EXECUTED=0`

Do not open or merge a PR unless explicitly instructed by the Commander/user. Commit the completed implementation to the current H5 branch/task branch and leave a clean worktree.