# H4-C Bench Contract Source Evidence — 2026-09-15

## Status

`SOURCE_CONTRACT=GREEN`
`STRUCTURED_ADAPTER=GREEN`
`LIVE_RAW_PRODUCER=YELLOW`
`PIXEL_ALGORITHM=BLOCKED_SOURCE_EVIDENCE`

H4-C is authorized only as a structured Bench contract migration. It is **not** evidence for a mature native pixel/CV/OCR Bench producer.

## Authoritative project facts

1. Current production live observation chain supplies `benchPerception:null`.
2. The existing minimal game-state adapter only projects Bench when an upstream BenchPerception is already available.
3. Historical M6.5 Bench validation passed 55/55, but the current production raw Bench producer remains incomplete/YELLOW.
4. The HZZS migration order remains `HUD/Shop -> Board Occupancy -> Bench producer`; migration must preserve frozen behavior and must not invent thresholds or algorithms.
5. H4-B is already merged/frozen. H4-C must start from merge commit `ac61d37f9de3523f49932454bd77d0acb31235fa`.

## Existing structured Bench contract

The existing adapter semantics support these fields when upstream Bench data exists:

- `available`
- `slots[]`
- per slot: `slotIndex`, `contentType`, `identity`, `hero`, `starLevel`

The minimal projected Bench output is:

- `slot`
- `state`
- `hero`
- `star`

## Frozen migration semantics

### Slot identity

- Published Bench slot indexing is **0-based**.
- Slot ordering must remain stable.
- Missing slots must not shift later slots.
- Duplicate/out-of-range slot indices are invalid evidence, not permission to reindex heuristically.

### Content state

Allowed logical states:

- `HERO`
- `EMPTY`
- `UNKNOWN`

`UNKNOWN` is not equivalent to `EMPTY`.

No visual/evidence result may be coerced from unknown/unmatched into empty.

### Hero identity

- Identity resolution must reuse the existing authoritative Shop/GameData exact-resolution path where available.
- No fuzzy hero guessing is authorized in H4-C.
- Cost/traits or other hero metadata may only come from GameData, never from visual guessing.

### Star level

Allowed values:

- `1`
- `2`
- `3`
- `null`

Any unsupported/non-numeric star input must fail closed to `null` and surface an appropriate partial/error diagnostic rather than inventing a value.

### Status/fail-closed behavior

H4-C should distinguish at least:

- complete structured observation
- partial structured observation
- unavailable/unknown
- invalid/error

Missing slots, identity unmatched, duplicate slot evidence, unsupported star values, or malformed structured input must never silently become a valid EMPTY result.

## Explicitly not authorized by current evidence

H4-C must **not** implement or claim production maturity for:

- native Bench occupancy CV detection from `CapturedFrame`
- Bench slot geometry research/calibration beyond already frozen H2 ROI contract
- Bench OCR
- Bench star visual detector
- automatic HERO/EMPTY pixel classification
- new thresholds/weights/features
- Board Identity
- Unit Ledger / owned-count aggregation
- Decision
- Actions / Gesture / move / sell / buy / reroll

If implementation requires any of the above, stop with `BLOCKED_SOURCE_EVIDENCE` rather than guessing.

## H2/H3/H4 integration constraints

- Use H2 `BENCH` ROI as the authoritative high-level HZZS ROI contract.
- Any Bench observation published for a frame must carry the current accepted frame sequence/metadata.
- No pixel buffer, Bitmap, Mat, frame lease, or mutable capture object may escape into durable Shadow State.
- Session start/stop must reset any Bench retained state.
- Do not add second capture paths.
- Overlay remains off by default.

## Safety baseline

```text
ACTION_ENABLED=false
REAL_ACTION_REACHABLE=false
OVERLAY_DEFAULT_ENABLED=false
ACTION_EXECUTED=0
```

## H4-C acceptance boundary

H4-C may be declared PASS only for the **structured Bench contract migration** and typed Shadow State integration. It must not be described as a production-ready raw Bench vision producer until separate source evidence exists for that capability.
