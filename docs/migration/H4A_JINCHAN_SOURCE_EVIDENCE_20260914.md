# H4-A JinChan Source Evidence — 2026-09-14

Status: AUTHORITATIVE_MIGRATION_EVIDENCE
Target phase: H4-A HUD + Shop only
Target branch: `jinchan-h4a-hud-shop`
Frozen base: `793d98e05ac8e04055acd76c56c6a4c3fb5c4564`

## 1. Evidence package

Source package captured read-only from the current phone production project:

- Project root: `/sdcard/脚本/JinChanAI_AutoPlayer_V1/`
- Package: `JINCHAN_CURRENT_MIGRATION_SOURCE_20260914.zip`
- SHA256: `e4c665c670ab2358141ef65af308296dc7b9ff3f33884841c101a86962072f0b`
- Production entry: `main.js`
- Collection method: recursive `require()` closure from `main.js`, dynamic BASE requires, plus runtime GameData file loads.

Authoritative H4-A source files and SHA256:

- `config.js` — `ec227a79c309ee3a34eea3641b46c31cd8145915e8da854ea4046598dc694b97`
- `recognizer.js` — `0c9a7c4e45c04bbd66b06c1a8c28480c32f4c6b5d708731b54de0a8d2c1c2015`
- `validator.js` — `12c7fff679762b799300bd842e3ec9a16f9ca8299c9c197477ac9bedb7864b2d`
- `state.js` — `0d5e1b3bb33a648d4d8a9ce9920d03d17f56127ac144299f6379039163b17431`
- `runtime/shop_frame_perception_v1.js` — `fb59437b8ec5abc777778b876ea87e8c579c782111bfb0b4dd18ecdb95e4b087`
- `perception/shop_perception.js` — `b03a38369731fc712078b62e72cbedcf652fd967aad20cfabc55b9c50abf5747`
- `main.js` — `3ef47a4bd58c2eb19376fad47b5de0c577f4a3b0aff1a4d4052e349f4413da7f`

This document is migration evidence only. HZZS runtime must not depend on AutoJs6 JS files at runtime.

## 2. H4-A scope boundary

H4-A migrates only:

- HUD: LEVEL / EXP / GOLD
- Shop frame perception + Shop structured builder
- validator/fail-closed semantics required by those producers
- same-frame wiring into the existing H1/H2/H3 runtime

Not H4-A:

- Board Occupancy / Board Identity
- Bench producer
- Unit Ledger
- Decision
- Actions
- Overlay production output

Safety remains:

- `ACTION_ENABLED=false`
- `REAL_ACTION_REACHABLE=false`
- `OVERLAY_DEFAULT_ENABLED=false`
- `ACTION_EXECUTED=0`

## 3. Same-frame production contract

Current production `main.js` captures one image, then reuses that same image for HUD and Shop. Shop is attempted only when the stabilized state satisfies:

`inGame == true && uiState == SHOP_OPEN`

There is no second Shop capture. Any Shop exception, partial result, not-available result, or unavailable builder result fails closed and does not interrupt the legacy cycle.

HZZS H4-A must preserve this as:

`CapturedFrame -> H1 adapt once -> H2 ROI resolution -> HUD/Shop producers -> validator -> H3 Shadow State`

No producer-specific screen capture is allowed.

## 4. HUD frozen/best-effort contracts

Canonical perception space: 3120x1440 landscape.

### LEVEL

Frozen OCR ROI from current production `config.js`:

`LEVEL_OCR = { x=0.11666666666666667, y=0.9333333333333333, w=0.014743589743589743, h=0.03611111111111111 }`

Equivalent canonical pixels: `(364,1344,46,52)`.
Historical source comment: M5.3 MLKit benchmark 10/10, FROZEN.

Visibility contract:

- Edge metric = mean absolute adjacent grayscale difference over horizontal + vertical neighbor pairs.
- `LEVEL_VISIBILITY_EDGE_FLOOR = 2.0`
- measurement failure / invalid metric => fail-open to OCR
- edge below floor => `LEVEL_NOT_VISIBLE`, value null, no OCR, not counted as OCR failure

Validation:

- only digit-only VALID result becomes an integer value
- NOT_VISIBLE / EMPTY do not update a production value
- other invalid results remain INVALID

### EXP

Frozen OCR ROI:

`EXP_OCR = { x=0.06442307692307692, y=0.7659722222222223, w=0.0391025641025641, h=0.02847222222222222 }`

Equivalent canonical pixels: `(201,1103,122,41)`.
Historical source comment: M5.3 MLKit benchmark 10/10, FROZEN.

Visibility contract:

- same edge metric as LEVEL
- `EXP_VISIBILITY_EDGE_FLOOR = 3.0`
- metric failure => fail-open to OCR
- below floor => `EXP_NOT_VISIBLE`, current/max null, no OCR, not OCR failure

Validation:

- require parsed integers
- `current >= 0`
- `max > 0`
- `current <= max`
- violation => INVALID

### GOLD

Current production is explicitly best-effort, not equivalent in maturity to LEVEL/EXP.

Baseline ROI:

`GOLD_BASELINE_ROI_V1 = { x=0.9185897435897435, y=0.8611111111111112, w=0.04807692307692308, h=0.041666666666666664 }`

Equivalent canonical pixels: `(2866,1240,150,60)`.
Historical source comment: M5.6 baseline 8/10; `GOLD_TEXT_ROI` is NOT_FROZEN.

Visibility contract:

- `GOLD_VISIBILITY_EDGE_FLOOR = 2.0`
- metric failure => fail-open to OCR
- below floor => `GOLD_NOT_VISIBLE`, value null, no OCR

Content-purity diagnostic:

- threshold grayscale at 150
- reduce vertically to a per-column profile
- first 30 columns only
- icon zone = columns 0..16
- gap zone = columns 17..29
- `GOLD_CONTENT_PURITY_ICON_MIN = 15`
- `GOLD_CONTENT_PURITY_GAP_MIN = 10`
- both hit => `SUSPECT`, otherwise `CLEAN`; unavailable measurement => UNKNOWN/fail-open

Gold trust states:

- `UNAVAILABLE`
- `RAW_VALID`
- `UNTRUSTED`
- `TRUSTED`

Risk-filter ceiling from current config:

- `observedSafetyCeiling = 200`
- this is a dataset risk filter, NOT a proven game-domain maximum
- over ceiling => `UNTRUSTED`, production value null

Current trust behavior requires explicit clean evidence; raw OCR VALID alone must not become a production trusted Gold value. Ambiguity remains unavailable/untrusted rather than guessed.

## 5. Shop frozen geometry

Source tag: `M6_1B_MEDIAN_V1`.

Five normalized slot rectangles:

1. `{ nx=0.149458, ny=0.04, nw=0.162664, nh=0.415758 }`
2. `{ nx=0.312000, ny=0.04, nw=0.162664, nh=0.415758 }`
3. `{ nx=0.474664, ny=0.04, nw=0.162664, nh=0.415758 }`
4. `{ nx=0.637083, ny=0.04, nw=0.162664, nh=0.415758 }`
5. `{ nx=0.800188, ny=0.04, nw=0.162664, nh=0.415758 }`

Name band inside each slot crop:

`NAME_BAND = { x0=0.0, x1=1.0, y0=0.898, y1=0.9766 }`

Do not recompute or retune this geometry during H4-A.

## 6. Shop content gate

Content types:

- `HERO_CARD`
- `EMPTY`
- `NON_HERO`
- `UNKNOWN`

Production safety rule:

`UNKNOWN != EMPTY`

Current runtime deliberately degrades uncertain empty-looking slots to UNKNOWN:

`RUNTIME_EMPTY_DEGRADED_TO_UNKNOWN = true`

Evidence-first gate:

- G1: usable name OCR evidence => HERO_CARD
- G2: when name is empty, conservative visual reject requires ALL:
  - `meanLuma < 45`
  - `madLuma < 14`
  - `edgeEvidence < 12`
  => NON_HERO
- G3: name empty and not G2 => UNKNOWN

Name OCR default confidence used by the source contract: `0.8`.

H4-A must not invent an EMPTY classifier that is not present in this production runtime.

## 7. Shop structured identity contract

`perception/shop_perception.js` preserves all five canonical slot positions. Missing/uncertain slots must not shift subsequent positions.

Identity resolution is exact/conservative:

- normalize name text
- canonical GameData name lookup
- alias lookup where supported by GameData
- unresolved hero => UNKNOWN/unmatched identity, not a guessed hero

For `EMPTY`, `NON_HERO`, and `UNKNOWN`, no hero identity may be fabricated.

A HERO_CARD becomes a structured hero only after a successful exact/alias GameData resolution.

## 8. H4-A production Shop gate

Although the source helper contains an older F1-compatible entry helper, the current production `main.js` promotion path only invokes Shop when the stabilized state is:

`inGame == true && uiState == SHOP_OPEN`

H4-A must preserve the production gate, not restore F1 test-harness gates.

The C2-E2 container-presence helper/constants in the legacy source are not part of the H4-A production entry gate and must not be promoted as a new authority here.

## 9. H3 output update rules for H4-A

After successful H4-A migration:

- `level` may become real typed observation
- `gold` may become real typed observation, but only trusted value is published as trusted Gold; ambiguity remains UNKNOWN/UNAVAILABLE/UNTRUSTED
- `shop` may become real typed observation only under the authoritative Shop gate and successful producer/builder path
- `board` stays H3 UNKNOWN during H4-A
- `bench` stays H3 UNKNOWN during H4-A

State must not retain:

- `CapturedFrame`
- full-frame pixel arrays
- ROI pixel buffers beyond their required processing lifetime
- Bitmap/frame leases
- Action references

## 10. Required migration discipline

1. Reuse H1 Frame Bridge/Runtime; do not add another coordinate conversion layer.
2. Reuse H2 ROI Registry for top-level regions. Internal frozen HUD/Shop sub-ROIs are evidence-backed constants, not a new screen coordinate system.
3. Consume one accepted frame for all H4-A observations.
4. No second capture.
5. Do not retune frozen thresholds/ROIs during migration.
6. Do not turn GOLD best-effort into a stronger claim than the source supports.
7. Do not turn UNKNOWN into EMPTY.
8. Do not make partial/exception Shop results available.
9. Keep Overlay OFF by default.
10. Keep all real Actions unreachable.

## 11. Required H4-A tests

At minimum cover:

- HUD and Shop use the same frameSeq / same CapturedFrame lease
- no producer invokes capture
- LEVEL visibility threshold and fail-open measurement failure
- EXP visibility threshold and range validation
- GOLD visibility, purity, ceiling, and trust fail-closed behavior
- Shop five-slot geometry
- Shop name-band geometry
- Shop G1/G2/G3 semantics
- UNKNOWN is never coerced to EMPTY
- Shop not attempted outside stabilized inGame + SHOP_OPEN
- partial/error/unavailable Shop => unavailable state
- board/bench remain UNKNOWN in H4-A
- published Shadow State contains no pixel/frame/action references
- `REAL_ACTION_REACHABLE=false`

END OF AUTHORITATIVE H4-A MIGRATION EVIDENCE
