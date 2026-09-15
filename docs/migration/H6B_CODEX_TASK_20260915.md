# Codex Task — H6-B Coordinate / Gesture Resolver

Base branch: `jinchan-h6b-coordinate-gesture-resolver`
Expected base lineage: H6-A authoritative merge `0dc84debc04e962310dd7205ed445ec77a0fc078`

Read first:
- `docs/migration/H6B_COORDINATE_GESTURE_RESOLVER_SOURCE_EVIDENCE_20260915.md`
- H6-A action contract/safety gate source and tests
- `domain/automation/AutomationModels.kt` only for the pure `GestureSpec` contract

## Goal

Implement a PURE, NON-EXECUTING JinChan coordinate/gesture resolver. It consumes an H6-A `ApprovedJinChanAction` plus an explicit immutable calibrated coordinate profile and returns either a resolved `GestureSpec` or a typed BLOCKED result.

This task MUST NOT enable or dispatch real actions.

## Required design

Add minimal Kotlin under `data/jinchan/action` (names may be adjusted only for project conventions):
- `JinChanCoordinateProfile`
- normalized point value type with strict finite [0,1] validation
- `JinChanGestureResolver`
- `JinChanGestureResolveResult.Resolved/Blocked`
- stable blocked reason enum
- resolver provenance/profile id

Profile coordinates are OPTIONAL and missing values fail closed. Do not invent any production coordinates.

Support mapping only where sufficient explicit calibration exists:
- BuyShopSlot(slot 0..4) -> click calibrated shop slot
- RefreshShop -> click calibrated refresh target
- BuyXp -> click calibrated XP target
- MoveUnit -> drag from explicit known source logical location to calibrated Board/Bench destination
- SellUnit -> drag from explicit known source logical location to calibrated sell target; because `SELL_ZONE_CALIBRATED=false`, default/current production profile MUST NOT provide a sell target

If H6-A `ApprovedJinChanAction` does not contain enough source-location information for Move/Sell, introduce a separate immutable resolver input carrying the already-confirmed source `UnitLocation`; do NOT mutate the frozen H6-A contract and do NOT query pixels/ledger inside the resolver.

Use H5 logical location types. Board/Bench indices must remain consistent with frozen contracts. Reject UNKNOWN/NONE/invalid locations.

Gesture rules:
- click: `GestureSpec(startX,startY)` with no end coordinates
- move/sell: `GestureSpec(startX,startY,endX,endY,durationMs=...)`
- duration must be a named resolver policy constant, deterministic and within existing GestureSpec constraints; do not claim it is production-calibrated. Prefer a conservative neutral value used only as a pure specification until live calibration.
- no display-pixel conversion in H6-B

## Hard fail-closed rules

No ROI-center fallback. No inferred grid centers. No magic screen coordinates. No wildcard profile. No implicit default calibration. Missing calibration => typed BLOCKED.

At minimum cover blocked reasons:
PROFILE_UNAVAILABLE, TARGET_UNCALIBRATED, SOURCE_LOCATION_UNAVAILABLE, DESTINATION_UNCALIBRATED, SELL_TARGET_UNCALIBRATED, INVALID_LOGICAL_LOCATION, UNSUPPORTED_ACTION_MAPPING.

## Forbidden dependencies / calls

Production H6-B code must not reference or call:
- `AutomationAction`
- `GestureArbiter`
- `GestureDispatcher`
- `GestureDispatcherFactory`
- `HzzsAccessibilityService`
- `dispatchGesture`
- Shizuku / Root / shell input
- capture / OCR / CV
- Decision execution
- ledger mutation

`GestureSpec` is the ONLY automation-domain type H6-B may emit/use.

## Tests

Add JVM unit tests covering at least:
1. no profile -> BLOCKED
2. BUY invalid/missing slot calibration -> BLOCKED
3. calibrated BUY -> click GestureSpec
4. REROLL missing/calibrated
5. BUY_XP missing/calibrated
6. MOVE missing source -> BLOCKED
7. MOVE UNKNOWN/NONE source -> BLOCKED
8. MOVE missing destination calibration -> BLOCKED
9. calibrated BOARD/BENCH move -> drag GestureSpec
10. SELL with no calibrated sell target -> SELL_TARGET_UNCALIBRATED
11. calibrated synthetic test-only sell target -> drag spec (test proves resolver contract only; do not add production sell calibration)
12. normalized point rejects NaN/infinite/out-of-range
13. deterministic repeated resolution
14. production H6-B sources contain no forbidden transport/execution references except `GestureSpec`

Use the repository's JUnit4 convention (`org.junit.Test`, `org.junit.Assert.*`).

Run project quality checks and targeted/full tests available in environment. If local Android/JDK toolchain is blocked, report BLOCKED_ENVIRONMENT and rely on GitHub Actions; do not alter production semantics to work around environment issues.

## Safety acceptance

Final task report must explicitly state:
`ACTION_ENABLED=false`
`REAL_ACTION_REACHABLE=false`
`ACTION_EXECUTED=0`
`SELL_ZONE_CALIBRATED=false`

Do not implement H6-C. Do not wire the resolver to GestureArbiter/dispatcher. Do not merge.