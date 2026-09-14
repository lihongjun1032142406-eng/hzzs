# H4-B Board Occupancy — Frozen Model Asset Supplement

Date: 2026-09-14
Status: SOURCE_EVIDENCE_READY / UNBLOCKS_MODEL_DATA
Source package: `JINCHAN_CURRENT_MIGRATION_SOURCE_20260914.zip`
Package SHA256: `e4c665c670ab2358141ef65af308296dc7b9ff3f33884841c101a86962072f0b`

This supplement exists because the first H4-B Codex run correctly stopped when the full frozen model data were not present in the HZZS repository. Do not infer or refit anything. Use the extracted assets below as authoritative migration source data.

## 1. Exact frozen model parameters

Repository reference:

`docs/migration/h4b_reference/d4_v8_params.js`

Original source path:

`tests/m6_10/d4_integration/d4_v8_params.js`

Original source SHA256:

`93a818539760538a5286c22beb3b8932667d8d5049e75c1c8890a37a62106cba`

The file contains the complete frozen values for:

- status `V8_EDGE_UI_SUPPRESSION_DEV_FROZEN`
- geometry `HEX_V2_FROZEN`
- standardized logistic regression
- `C=0.1`
- threshold `0.65`
- class weight `balanced`
- exact 120-feature order
- all 120 `scaler_mean` values
- all 120 `scaler_scale` values
- all 120 coefficients
- intercept `-1.7453634942999956`
- source model SHA256 `cd62a821514c375994c681a2bc9a737edf5d0804954e1dcdc680b872af8ebf2b`

These values are frozen data. Kotlin must reproduce them exactly; no fitting, simplification, pruning, rounding policy changes, or threshold search.

## 2. Exact scoring math

Original loader source:

`tests/m6_10/d4_integration/d4_v8_loader.js`

Original SHA256:

`43f406f7147f255428703f01f1169cd67db1f4b6079fdfe87a6c3da542cb415f`

Scoring semantics:

```text
z = intercept
for i = 0..119:
    z += coefficient[i] * ((x[i] - scaler_mean[i]) / scaler_scale[i])

if z >= 0:
    p = 1 / (1 + exp(-z))
else:
    ep = exp(z)
    p = ep / (1 + ep)

occupied = (p >= 0.65)
```

Input must be exactly 120 finite values in the frozen feature order. A malformed feature vector must not be scored as empty/unoccupied; it remains UNKNOWN/unavailable according to the H4-B fail-closed contract.

## 3. Frozen feature extractor semantics

Repository reference:

`docs/migration/h4b_reference/d4_v8_extractor.js`

Original source path:

`tests/m6_10/d4_integration/d4_v8_extractor.js`

Original source SHA256:

`a12afc2735fed51bb554d9ea0fe4dba25d66fe9ac2effd6fe3ace5cdc0e0e0d8`

The frozen extractor uses host OpenCV Level-1 maps for each 110x80 anchor:

- grayscale
- Sobel gx
- Sobel gy
- magnitude
- Laplacian with ksize=3

Do not replace these with approximate hand-written convolution.

Level-2 feature regions are half-open coordinates:

```text
all      x[5,105)  y[5,79)
mid      x[20,90)  y[15,60)
lower    x[15,95)  y[30,79)
center   x[30,80)  y[25,75)
feet     x[30,80)  y[45,79)
corefeet x[38,72)  y[50,79)
```

Frozen feature math:

- grayscale population standard deviation (`ddof=0`)
- magnitude percentiles 50/75/85/90/95 using NumPy default linear interpolation semantics
- strict edge densities `mean(mag > t)` for t=15/25/40/60
- 75th percentile of `abs(gx)`, `abs(gy)`, `abs(lap)`
- ratio features use `(a + 1e-4) / (b + 1e-4)`

The exact 120 output order is the `features` array in `d4_v8_params.js`.

## 4. Geometry/source hashes needed for verification

Authoritative original source hashes from the migration manifest:

```text
tests/m6_10/d4_integration/d4_hex_v2_geometry.js
b4bc0f21f3179d2afcf2e38b5d7c5590036d0b858ff031c2ba17fa0c805e528a

tests/m6_10/d4_integration/d4_board_occupancy.js
038e30a00d27ae05479e4dcdd09ca68da81058966830c7311a08061864660856

tests/m6_10/d4_integration/d4_scene_guard.js
f6b90722160469bd8fdb07b06a7397248da51196a36c907cb21423216112fcb2

tests/m6_10/d4_integration/d4_v8_extractor.js
a12afc2735fed51bb554d9ea0fe4dba25d66fe9ac2effd6fe3ace5cdc0e0e0d8

tests/m6_10/d4_integration/d4_v8_loader.js
43f406f7147f255428703f01f1169cd67db1f4b6079fdfe87a6c3da542cb415f

tests/m6_10/d4_integration/d4_v8_params.js
93a818539760538a5286c22beb3b8932667d8d5049e75c1c8890a37a62106cba

tests/m6_15/m6_15_p5_native_extractor_adapter.js
fc2473711be14c566a4f17b3baa149e69695a9a7eb1f05e5136e05776bd178b4
```

## 5. Migration rule after this supplement

The previous blocker `BLOCKED_SOURCE_EVIDENCE` for missing V8 model data is resolved for model parameters and Level-2 feature semantics.

Codex should resume H4-B implementation using:

1. `docs/migration/H4B_CODEX_TASK_20260914.md`
2. `docs/migration/H4B_BOARD_OCCUPANCY_SOURCE_EVIDENCE_20260914.md`
3. this supplement
4. `docs/migration/h4b_reference/d4_v8_params.js`
5. `docs/migration/h4b_reference/d4_v8_extractor.js`

If a new blocker appears, it must identify the exact still-missing source datum. Do not fall back to research or invented parameters.
