# H4-B Codex Task — Board Occupancy Migration

Date: 2026-09-14
Target repository: `lihongjun1032142406-eng/hzzs`
Working branch: `jinchan-h4b-board-occupancy`
Authoritative base before H4-B evidence/task commits: `8b905b8ace5a256290bbd0df242bf6d10a3d9775`

## 1. Mission

Implement **H4-B Board Occupancy migration** in native Kotlin on top of the already frozen H1/H2/H3/H4-A chain.

This is a migration of frozen JinChanAI production behavior, **not algorithm research**.

Use this file together with:

- `docs/migration/H4B_BOARD_OCCUPANCY_SOURCE_EVIDENCE_20260914.md` — authoritative H4-B source evidence.
- Existing H1/H2/H3/H4-A implementation already present on this branch.

Do not reopen H1/H2/H3/H4-A and do not alter their frozen contracts unless a real compile/test incompatibility proves a minimal compatibility edit is required.

## 2. Required runtime flow

The target flow is:

```text
same CapturedFrame
→ H1 JinChanFrameBridge / JinChanFrameRuntime
→ H2 BOARD ROI
→ H4-B Board Scene Truth Gate
→ H4-B Board Occupancy Producer
→ typed Board observation
→ H3 JinChanShadowStatePublisher
```

Strict invariants:

- exactly the same `CapturedFrame` / canonical frame as H4-A;
- no second capture;
- no long-lived frame or pixel retention;
- no full-frame copy added for Board;
- Board result must carry source frame identity / `frameSeq`;
- Board/Shop/HUD on one publish cycle must refer to the same accepted H1 frame;
- Action remains unreachable;
- Overlay remains off by default and H4-B must not depend on overlay rendering.

## 3. Frozen H4-B source contract

Do not select new thresholds or redesign the model. Migrate the values and semantics from the authoritative evidence file.

At minimum preserve:

- board grid: 28 cells, 4 rows × 7 columns;
- geometry tag: `HEX_V2_FROZEN`;
- per-cell anchor region: 110 × 80 in the frozen canonical geometry;
- model tag: `V8_EDGE_UI_SUPPRESSION_DEV_FROZEN`;
- frozen 120-feature order exactly as documented in the evidence file;
- frozen Logistic Regression configuration `C=0.1`;
- frozen occupancy threshold `0.65`;
- Scene Truth Gate thresholds:
  - `combatRatio = 0.10`
  - `boardDarkRatio = 0.25`
  - `shopSlotRatio = 0.03`
- P6-5G Banner Guard:
  - ROI `(850, 830, 1450, 15)` in canonical 3120×1440 coordinates
  - thresholds `100 / 0.70 / 30` exactly per evidence semantics;
- P6-5H Damage Panel Guard:
  - ROI `(2760, 150, 360, 900)`
  - guard conditions `mean < 120` and `std < 45` exactly per evidence semantics;
- board snapshot transition semantics: `SET / HOLD / CLEAR`;
- UNKNOWN must never be silently converted to EMPTY / unoccupied;
- insufficient scene evidence, failed guard evaluation, malformed model input, unsupported dimensions, or producer exception must fail closed.

Do not reinterpret numeric constants. If the evidence document does not specify a detail needed to implement something safely, preserve `UNKNOWN` / unavailable and stop that path rather than inventing a value.

## 4. Coordinate and frame rules

- H1 canonical coordinate space remains `3120 × 1440`.
- Use the existing authoritative H1 coordinate conversion layer.
- Do not add a second coordinate implementation.
- Use H2 `JinChanRoiId.BOARD` for the high-level Board ROI contract.
- Internal H4-B sub-ROIs / cell geometry may use the frozen canonical coordinates from H4-B evidence.
- Do not overwrite or mutate H2 registry values to fit H4-B internals.
- Unsupported orientation/dimensions or unmappable ROI must fail closed.

## 5. Output model

Add a typed Board occupancy observation suitable for H3 Shadow State. It should be immutable and pixel-free after publication.

The durable result must distinguish at least:

- unavailable / unknown;
- invalid evaluation;
- available valid occupancy snapshot;

The valid occupancy payload must preserve canonical logical cell identity for all 28 cells. Do not delete cells or shift indices when one cell is uncertain.

Preserve the frozen logical Board representation. If the source evidence defines row/column numbering, reproduce it exactly. Otherwise do not guess a new convention: retain a stable 28-slot canonical order and document the mapping.

Published Shadow State must never retain:

- `CapturedFrame`;
- `JinChanCanonicalFrame`;
- `IntArray` frame buffers;
- image/bitmap/mat handles;
- action or gesture objects.

## 6. Scene gate and snapshot semantics

Board production must run only when the stabilized scene context is eligible according to the migrated production gate.

Preserve the production distinction between:

- no usable evidence / scene not eligible → unavailable / clear stale observation according to frozen `CLEAR` semantics;
- transient condition that calls for `HOLD` → keep prior accepted logical snapshot only if the frozen evidence explicitly says HOLD;
- valid new evaluation → `SET`;
- explicitly invalid evaluation → invalid, not fake empty Board.

Do not make disappearance alone mean SELL, death, removal, or any Unit Ledger event. H4-B only publishes occupancy evidence.

## 7. Out of scope

Do **not** implement or modify any of the following in H4-B:

- Board hero identity / C6D visual identity;
- Bench producer;
- Unit Ledger / H5;
- Decision / Dynamic Hero Value;
- purchases, reroll, sell, move, drag, gesture dispatch;
- action simulation;
- HP / items / loot perception;
- new OCR work unrelated to existing H4-A;
- new threshold/model research;
- new training or fitting;
- old HZZS builtin algorithm stack;
- `AlgorithmPipeline`;
- `download_algorithm`;
- `app://runtime/snapshot`.

## 8. Safety requirements

These must remain true after the change:

```text
ACTION_ENABLED=false
REAL_ACTION_REACHABLE=false
OVERLAY_DEFAULT_ENABLED=false
ACTION_EXECUTED=0
```

No production action path may become reachable through H4-B.

## 9. Integration requirements

Extend the existing H3/H4-A state publication path rather than creating a parallel runtime.

Requirements:

- one accepted frame per publish cycle;
- H4-A HUD/Shop behavior remains unchanged;
- Board producer receives that same canonical frame;
- Bench stays UNKNOWN;
- Board Identity stays out of scope;
- existing stale / order / session rejection remains authoritative;
- producer failures are isolated and fail closed instead of aborting the whole capture runtime where avoidable.

Do not add per-frame filesystem I/O.

## 10. Performance constraints

H4-B should be ROI/cell scoped.

Do not introduce:

- persistent 3120×1440 full-frame copies;
- per-cell full-frame conversions;
- redundant canonical→source coordinate work when it can be hoisted per ROI/cell;
- unnecessary allocations in the inner 28-cell loop;
- long-lived pixel ownership beyond the synchronous frame lease.

Prefer one Board-scoped view / reusable computations for the same accepted frame.

Correctness and frozen semantics come first, but avoid obviously multiplying work by 28 when one shared computation is sufficient.

## 11. Tests required

Add JVM tests sufficient to prove at least:

1. all 28 cells are emitted in stable canonical order;
2. frozen geometry / key constants match evidence;
3. occupancy threshold boundary around `0.65` is handled exactly as defined;
4. Scene Truth Gate accepts/rejects the frozen representative cases;
5. Banner Guard behavior;
6. Damage Panel Guard behavior;
7. `SET / HOLD / CLEAR` semantics;
8. UNKNOWN is not coerced to EMPTY;
9. unsupported/unmappable frame fails closed;
10. Board producer and H4-A producers share the same `frameSeq`;
11. no second capture is introduced;
12. Board/Bench scope remains correct: Board typed observation, Bench still UNKNOWN;
13. durable state graph contains no pixel/frame/action references;
14. H4-A existing tests continue to pass.

If exact model coefficients / feature vectors are part of the evidence file, add deterministic fixture/unit coverage that verifies the Kotlin score for known inputs against the frozen source behavior. Do not substitute approximate or newly fitted weights.

## 12. Documentation required

Update only the minimum relevant docs, for example:

- `CHANGELOG.md`
- `docs/architecture.md`
- project README / agent guidance only if necessary to keep migration status accurate.

Record H4-B as migrated only after code and tests exist. Do not mark Board Identity or Bench as completed.

## 13. Acceptance commands

Run the repository's existing quality gates and relevant Gradle tasks.

Expected minimum validation:

```text
git diff --check
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew :app:testDebugUnitTest lintDebug assembleDebug
```

If the local Codex environment lacks Android SDK/NDK/CMake or other required host tooling, do not install a new toolchain or change repository architecture to compensate. Record the local blocker and rely on GitHub Actions for the official Android build validation.

## 14. Stop conditions

STOP and report `BLOCKED_SOURCE_EVIDENCE` instead of guessing if implementation needs a value/weight/feature mapping not present in `H4B_BOARD_OCCUPANCY_SOURCE_EVIDENCE_20260914.md`.

STOP and report the actual compiler/test failure if CI exposes a problem; do not widen scope.

Do not start H4-C / Bench, H5, or H6 in this task.

## 15. Deliverable

Produce one H4-B implementation PR whose intended final base is:

```text
jinchan-clean-base
```

Keep H4-B work isolated to this milestone. At completion report:

- changed files;
- source evidence used;
- tests / quality gates run;
- local blockers if any;
- branch/head SHA;
- explicit safety result;
- whether Board Occupancy is ready for Commander final review.

Do not merge the PR yourself.