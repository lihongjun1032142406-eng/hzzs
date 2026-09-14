# H4-B Board Occupancy — Authoritative Source Evidence

Date: 2026-09-14
Status: SOURCE_EVIDENCE_READY
Target phase: H4-B Board Occupancy migration
Base branch: `jinchan-clean-base`
Base commit: `8b905b8ace5a256290bbd0df242bf6d10a3d9775`

## 1. Authority and scope

This evidence is extracted from the current production migration package:

- Package: `JINCHAN_CURRENT_MIGRATION_SOURCE_20260914.zip`
- Package SHA256: `e4c665c670ab2358141ef65af308296dc7b9ff3f33884841c101a86962072f0b`
- Production entry: `main.js`
- `main.js` SHA256: `3ef47a4bd58c2eb19376fad47b5de0c577f4a3b0aff1a4d4052e349f4413da7f`

H4-B migrates only Board Occupancy and its scene/contamination safety gates. It does **not** include Board Visual Identity, Bench perception, Unit Ledger, Decision, Overlay rendering, or any Action path.

Safety invariants remain:

- `ACTION_ENABLED=false`
- `REAL_ACTION_REACHABLE=false`
- `OVERLAY_DEFAULT_ENABLED=false`
- same already-captured frame only; no second capture
- uncertainty stays unavailable/UNKNOWN; never fabricate EMPTY

## 2. Authoritative source files

- `runtime/board_occupancy_producer.js`
  - SHA256 `85a6f2bdca96153a448dd859a233aceaf2faec512e4186c639adef7ed2e04da0`
  - version `M6.15-P6.5H-INTEGRATION-V1`
- `runtime/board_scene_truth_gate.js`
  - SHA256 `f76a80277cf338ac55db5355d87d8b1adfbbe8f947fe39d24ada52e393309289`
  - version `M6.18-C6C5A-GATE-V1`
- `tests/m6_10/d4_integration/d4_hex_v2_geometry.js`
  - SHA256 `b4bc0f21f3179d2afcf2e38b5d7c5590036d0b858ff031c2ba17fa0c805e528a`
  - geometry `HEX_V2_FROZEN`
- `tests/m6_10/d4_integration/d4_board_occupancy.js`
  - SHA256 `038e30a00d27ae05479e4dcdd09ca68da81058966830c7311a08061864660856`
- `tests/m6_10/d4_integration/d4_v8_params.js`
  - SHA256 `93a818539760538a5286c22beb3b8932667d8d5049e75c1c8890a37a62106cba`
  - model `V8_EDGE_UI_SUPPRESSION_DEV_FROZEN`
- `tests/m6_15/m6_15_p5_native_extractor_adapter.js`
  - SHA256 `fc2473711be14c566a4f17b3baa149e69695a9a7eb1f05e5136e05776bd178b4`

## 3. Production call contract

Current `main.js` production flow is:

```text
same Screen.capture() image
→ BoardSceneTruthGate.evaluate(recog.uiStateResult.details)
→ require stabilized inGame == true
→ require stabilized uiState == BOARD_OR_COMBAT
→ require scene gate allowed == true
→ BoardOccupancyProducer.evaluate(same image, trusted scene context)
→ BoardSceneTruthGate.decideSnapshot(...)
→ SET / HOLD / CLEAR policy
→ State.updateGameData(gd)
```

No second board evaluation and no second capture are allowed.

Snapshot semantics:

- out of game -> `CLEAR`
- scene not allowed -> `HOLD`
- producer available -> `SET`
- producer unavailable -> `HOLD`

Combat/panel/transition/UNKNOWN never mean EMPTY and must never overwrite the last trusted board snapshot.

## 4. Scene truth gate

`board_scene_truth_gate.js` uses the following current candidate thresholds:

- `COMBAT_MIN = 0.10`
- `PANEL_DARK_MIN = 0.25`
- `PLANNING_SHOP_MIN = 0.03`

Important: source comments explicitly classify these as **conservative CANDIDATE gates, not frozen detector truth**. H4-B must preserve these current production semantics; do not relabel them as a new frozen detector or retune them without evidence.

Evaluation contract:

- missing finite `combatRatio`, `boardDarkRatio`, or `shopSlotRatio` -> reject `EVIDENCE_MISSING`
- `combatRatio >= 0.10` -> reject `COMBAT_LIKE`
- `boardDarkRatio >= 0.25` -> reject `PANEL_LIKE`
- `shopSlotRatio < 0.03` -> allow `PLANNING_CANDIDATE_LOW_SHOP_SIGNAL`
- otherwise -> allow `PLANNING_CANDIDATE`

## 5. Producer eligibility and fail-closed guards

Producer `eligible(scene)` requires:

- `scene.inGame === true`
- `scene.uiState === 'BOARD_OR_COMBAT'`

The producer requires exactly `3120x1440`; other dimensions are unavailable.

### P6-5G board banner guard

ROI / thresholds:

```text
x=850, y=830, w=1450, h=15
edgeMin=100.0
coverageMin=0.70
pixelEdgeMin=30.0
```

Detection or guard evaluation failure makes the producer unavailable. It must HOLD the last trusted snapshot, not write EMPTY.

### P6-5H damage / settlement panel guard

ROI / thresholds:

```text
x=2760, y=150, w=360, h=900
meanMax=120.0
stdMax=45.0
```

Detection or guard evaluation failure makes the producer unavailable.

## 6. Frozen board geometry

Geometry is `HEX_V2_FROZEN` at canonical resolution `3120x1440`.

- 4 rows x 7 columns = 28 cells
- row order top -> bottom `R1..R4`
- column order left -> right `C1..C7`
- each anchor crop = `110x80`
- runtime center rounding uses `Math.round(cx/cy)`
- anchor = rounded center minus `(55,40)`

Center formula from source:

```text
odd_rows_x0  = 1066.6153132070312
even_rows_x0 = 968.20135319375
y0           = 700.1169451667137
x_pitch      = 196.8279200265625
y_pitch      = 124.39295845641749
```

The generated 28 anchor coordinates in `d4_hex_v2_geometry.js` are authoritative. H4-B must port them exactly or derive byte-for-byte equivalent integer anchors from the same formula/rounding; do not substitute the broad H2 BOARD ROI as the 28-cell geometry.

## 7. Frozen occupancy model

`d4_board_occupancy.js` / `d4_v8_params.js` define:

- geometry: `HEX_V2_FROZEN`
- model: `V8_EDGE_UI_SUPPRESSION_DEV_FROZEN`
- standardized logistic regression
- `C = 0.1`
- class weight `balanced`
- occupancy threshold `0.65`
- 120-feature vector in the exact V8 feature order

The model coefficients, scaler mean, scaler scale, intercept and feature ordering are frozen source data. H4-B must migrate them exactly; no retraining, threshold search, feature deletion, or weight tuning.

Per-cell contract:

- bad/missing feature vector -> `occupied = null`, `probability = null`
- finite score -> classify at threshold `0.65`
- UNKNOWN remains UNKNOWN; no default `false`

Whole-board contract:

- 28 ordered cells
- fields preserve `row`, `col`, `cellId`, `occupied`, `probability`
- valid board payload reports `model='V8'`, `geometry='HEX_V2_FROZEN'`
- `occupiedCount` counts only explicit `occupied === true`

## 8. Geometry/skin trust

The D4 scorer explicitly rejects when:

- `scene.geometryTrusted === false`, or
- `scene.skinTrusted === false`

Reason: frozen GREEN absolute geometry is trusted; non-GREEN/UNKNOWN geometry must not write own-board occupancy unless separately proven. H4-B must preserve fail-closed geometry trust semantics.

## 9. Native extractor / fallback semantics

Current producer runs 28 anchors through the frozen extractor adapter, preserving the exact 120-feature contract.

Path reporting distinguishes:

- `native_p4_a1`
- `fallback_p3_d1d2`
- `mixed`

Fallback is an exception/recovery path, not the normal performance target. A bad feature contract for any cell makes the current producer result unavailable rather than guessing.

H4-B Kotlin implementation may replace AutoJs6/OpenCV plumbing with a native Android/Kotlin/C++ equivalent, but the output features/model/geometry must remain semantically equivalent and use the same frame. Performance implementation details may change; business thresholds/model semantics may not.

## 10. H4-B required output boundary

H4-B should publish a typed Board Occupancy observation into H3 Shadow State from the same canonical frame:

```text
frameSeq
status: AVAILABLE | UNKNOWN/UNAVAILABLE | INVALID as mapped by the H3 contract
scene decision/reason
geometry = HEX_V2_FROZEN
model = V8
occupiedCount
cells[28]: row, col, cellId, occupied(true|false|null), probability(number|null)
timing diagnostics (read-only)
```

Board Visual Identity (`C6D`) is explicitly out of H4-B scope. Occupancy answers only where units are present, not which hero they are.

## 11. Acceptance constraints

H4-B is acceptable only if all are true:

1. Starts from current H4-A frozen base.
2. Same `CapturedFrame` / same `frameSeq`; no second capture.
3. Exact 28-cell `HEX_V2_FROZEN` geometry.
4. Exact V8 feature order/scaler/weights/intercept/threshold `0.65`.
5. Preserves scene gate + P6-5G + P6-5H fail-closed behavior.
6. Preserves SET/HOLD/CLEAR snapshot semantics without fabricating EMPTY.
7. Missing/bad evidence remains UNKNOWN/unavailable.
8. Board Identity / Bench / H5 / H6 remain out of scope.
9. No pixel/frame lease retained after publication.
10. `ACTION_ENABLED=false`, `REAL_ACTION_REACHABLE=false`, Overlay remains OFF by default.
11. CI must pass unit tests, lint, and debug APK build before H4-B may be marked PASS/FROZEN.

## 12. Historical evidence status

Project handoff records H4 ordering as `HUD/Shop -> Board Occupancy -> Bench producer`, explicitly stating frozen perception results are migrated without reopening threshold research. Existing project history records C6C-2 Board occupancy promotion, C6C-5A Board gate repair, and C6C-5B short-live Board Occupancy acceptance as FINAL_PASS/FROZEN; H4-B is therefore a runtime migration/integration phase, not a new research phase.
