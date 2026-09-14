# H4-B Source Evidence Supplement 2 — Level-1 CV, Guards, Scene Ratios

Status: SOURCE_EVIDENCE_SUPPLEMENT
Authority: exact current production package `JINCHAN_CURRENT_MIGRATION_SOURCE_20260914.zip`, SHA256 `e4c665c670ab2358141ef65af308296dc7b9ff3f33884841c101a86962072f0b`.

This supplement resolves the second `BLOCKED_SOURCE_EVIDENCE` report. Do not infer parameters beyond what is stated here.

## 1. V8 Level-1 OpenCV maps

Authoritative source: `tests/m6_15/m6_15_p4_mat_cv.js`, SHA256 `5f668fde7f570752c0153257516973f1fbc74b6908575abcda0ec30b9754dbed`.
A verbatim reference is committed as `docs/migration/h4b_reference/m6_15_p4_mat_cv.js`.

Exact sequence for each 110x80 anchor Mat:

1. If input has 4 channels: `cvtColor(anchor,tmp,COLOR_BGRA2BGR)`; otherwise use anchor as BGR input.
2. `cvtColor(bgr,g,COLOR_BGR2GRAY)`.
3. `g.convertTo(g32,CV_32F)`.
4. `Sobel(g32,gx,CV_32F,1,0,3)`.
5. `Sobel(g32,gy,CV_32F,0,1,3)`.
6. `Core.magnitude(gx,gy,mag)`.
7. `Laplacian(g32,lap,CV_32F,3)`.

No explicit scale, delta, or borderType is supplied in source, therefore OpenCV overload defaults are authoritative. Do not invent non-default values. Maps consumed by V8 are `gray=g32`, `gx`, `gy`, `mag`, `lap`, all float Mats.

The cell is cropped first by `mat.submat(anchor...)`; Level-1 color conversion/differentiation runs on that 110x80 submat.

## 2. P6-5G Banner Guard exact algorithm

Authoritative source: `runtime/board_occupancy_producer.js`, SHA256 `85a6f2bdca96153a448dd859a233aceaf2faec512e4186c639adef7ed2e04da0`.
Extracted reference is committed as `docs/migration/h4b_reference/board_occupancy_producer.js`.

ROI `(850,830,1450,15)`. Require full frame 3120x1440.

Exact operations:

- crop ROI first with `submat`;
- 4-channel -> `COLOR_RGBA2GRAY`; 3-channel -> `COLOR_BGR2GRAY`; otherwise `convertTo(CV_8U)`;
- vertical Sobel: `Sobel(gray, gy, CV_32F, 0, 1, 3)` using OpenCV overload defaults;
- `Core.convertScaleAbs(gy, absGy)`;
- `Core.reduce(absGy, rows, 1, REDUCE_AVG, CV_32F)`; this produces per-row mean absolute vertical-gradient values across width;
- choose max row with `Core.minMaxLoc(rows)`; `peak = maxVal`; `rowIdx = round(maxLoc.y)`;
- take `absGy.row(rowIdx)`;
- `threshold(peakRow, mask, 30.0, 255, THRESH_BINARY)`. OpenCV THRESH_BINARY therefore counts values strictly greater than threshold as non-zero;
- `coverage = countNonZero(mask) / 1450`;
- banner detected iff `peak >= 100.0 && coverage >= 0.70`.

Detected means contamination (`BOARD_BANNER_LIKE`) and producer becomes unavailable, causing snapshot HOLD. Evaluation failure also makes producer unavailable/HOLD.

## 3. P6-5H Damage/Settlement Panel Guard exact algorithm

Same authoritative producer source.

ROI `(2760,150,360,900)`. Require full frame 3120x1440.

Exact operations:

- crop ROI first;
- 4-channel -> `COLOR_RGBA2GRAY`; 3-channel -> `COLOR_BGR2GRAY`; otherwise copy source to gray;
- `Core.meanStdDev(gray, mean, sd)`;
- use first channel `m=mean[0]`, `s=sd[0]`;
- detected iff `m < 120.0 && s < 45.0` (strict `<`, not `<=`).

OpenCV `meanStdDev` semantics are authoritative; do not substitute sample standard deviation. Alpha is removed by RGBA2GRAY for four-channel input. Detected means contamination (`DAMAGE_PANEL_LIKE`) and producer unavailable/HOLD. Evaluation failure also unavailable/HOLD.

## 4. Production Scene Truth Gate ratio producers

Authoritative source: `recognizer.js`, SHA256 `0c9a7c4e45c04bbd66b06c1a8c28480c32f4c6b5d708731b54de0a8d2c1c2015`; `config.js`, SHA256 `ec227a79c309ee3a34eea3641b46c31cd8145915e8da854ea4046598dc694b97`.

`slowPass(img,w,h)` computes all three ratios from the same captured `img` that is later consumed in the production cycle. This is the frame-binding contract; HZZS must compute them from the same accepted H1 frame/frameSeq, with no second capture.

Normalized ROIs in this production source:

- BOARD `{x:0.17,y:0.10,w:0.66,h:0.70}`
- SHOP `{x:0.15,y:0.55,w:0.68,h:0.40}`

These are recognizer-internal source ROIs. They do NOT authorize changing the already-frozen H2 registry. H4-B may use equivalent internal sub-ROI mapping through the single H1 coordinate layer.

Ratios:

- `shopSlotRatio = countColorRatio(SHOP, SHOP_SLOT)` where SHOP_SLOT inclusive RGB ranges are R 40..100, G 40..100, B 60..120.
- `boardDarkRatio = countColorRatio(BOARD, HUD_DARK)` where HUD_DARK inclusive RGB ranges are R 0..80, G 0..80, B 0..110.
- `combatRatio = countColorRatio(BOARD, COMBAT_BRIGHT)` where COMBAT_BRIGHT inclusive RGB ranges are R 200..255, G 200..255, B 150..255.

`countColorRatio` exact source semantics:

- clamp ROI to image bounds;
- source uses `images.clip` then `region.getMat()`;
- `Core.inRange(mat, Scalar(rMin,gMin,bMin,0), Scalar(rMax,gMax,bMax,255), mask)`;
- ranges are closed/inclusive as OpenCV inRange semantics;
- alpha is unrestricted 0..255;
- ratio = `countNonZero(mask)/(actualClippedWidth*actualClippedHeight)`;
- source failure returns `0` and logs warning.

For H4-B fail-closed migration, do not silently convert an inability to evaluate the same-frame ratio into trustworthy evidence. If HZZS cannot evaluate it, surface evidence missing/unavailable rather than inventing a ratio.

## 5. geometryTrusted / skinTrusted clarification

The second blocker assumed these must be produced by a separate runtime source. That is NOT what the current production `runtime/board_occupancy_producer.js` does.

Immediately before D4 scoring, current producer constructs `sceneForD4` and explicitly passes:

```js
geometryTrusted: true,
skinTrusted: true
```

Therefore H4-B must not invent a new skin/geometry classifier merely to satisfy this phase. The current production package does not include a separate wired producer for these flags in the Board production seam. For H4-B migration fidelity, after the existing eligibility + same-frame Scene Truth Gate + full-frame dimension check + P6-5G + P6-5H have passed, the frozen D4 scorer is invoked under the same production assumption represented by these two `true` values.

This does NOT authorize Board Identity, Bench skin work, or a new trust algorithm. If a later phase introduces a separately proven geometry/skin producer, that is a separate evidence/promotion decision.

## 6. Stop-rule resolution

The following previous blockers are now source-resolved:

- V8 Level-1 OpenCV map generation;
- P6-5G Banner Guard formula and polarity;
- P6-5H Damage Panel Guard formula and polarity;
- combatRatio / boardDarkRatio / shopSlotRatio production computation and same-frame binding;
- geometryTrusted / skinTrusted current production behavior.

If Codex still finds a blocker, it must identify the exact missing source field/function rather than requesting a redesigned detector or new model research.
