# H6-B Coordinate / Gesture Resolver — Source Evidence

Date: 2026-09-15
Base: `0dc84debc04e962310dd7205ed445ec77a0fc078`
Status: PLANNING / ACTION OFF

## Frozen inputs

- H6-A is PASS / MERGED / FROZEN.
- `JinChanActionIntent` is semantic and pixel-independent: BuyShopSlot(slot), MoveUnit(uid,destination), SellUnit(uid), RefreshShop, BuyXp.
- HZZS `GestureSpec` uses full-screen normalized [0,1] coordinates. Null end coordinates mean click; both end coordinates mean swipe/drag.
- Existing HZZS dispatcher is the only later normalized -> real-display-pixel boundary. H6-B must not duplicate display-pixel conversion.
- Canonical JinChan perception space remains landscape 3120x1440; business/state locations remain logical/normalized.

## Old JinChan source / handoff audit

Authoritative historical Action Protocol V1 FIX1 confirms semantic contracts only:
- BUY slot 0..4
- SELL explicit BENCH slot or BOARD {row,col}
- LEVEL one XP purchase
- REROLL one shop refresh
- MOVE BOARD {row,col} -> {row,col}
- DEPLOY BENCH slot -> BOARD {row,col}
- BENCH BOARD {row,col} -> BENCH slot
- WAIT no-op

The protocol explicitly excludes screen coordinates and touch implementation.

Current migration source audit does not provide a trustworthy production coordinate table for BUY / REROLL / LEVEL / BOARD / BENCH / SELL hit targets. Existing ROI rectangles are perception regions and MUST NOT be reinterpreted as action hitboxes.

Critical frozen evidence: `SELL_ZONE_CALIBRATED=false`. Gold ROI is not a sell hitbox.

## H6-B decision

H6-B is a pure resolver boundary, not a calibration phase and not an executor.

It may convert an H6-A `ApprovedJinChanAction` into a `GestureSpec` only when an explicit immutable coordinate profile contains the required calibrated target(s). Missing target evidence returns a typed BLOCKED result. No fallback centers, ROI centers, guessed grid geometry, wildcard coordinates, or silent defaults are allowed.

Initial coordinate profile fields are optional and fail closed:
- shopSlots: exactly five optional normalized points indexed 0..4
- boardCells: optional logical Board location -> normalized point
- benchSlots: exactly nine optional normalized points indexed 0..8
- refreshShop: optional normalized point
- buyXp: optional normalized point
- sellTarget: optional normalized point; remains absent while SELL_ZONE_CALIBRATED=false

MOVE/SELL source coordinates must be resolved from the approved UID's known H6-A provenance/ownership context or from an explicit source location carried into a later resolver input. H6-B must not re-identify a UID and must not infer its location from pixels.

## Required result model

- RESOLVED: contains the original approved action + pure `GestureSpec` + resolver provenance/profile id.
- BLOCKED: contains the original approved action + stable typed reason.

Minimum blocked reasons:
- PROFILE_UNAVAILABLE
- TARGET_UNCALIBRATED
- SOURCE_LOCATION_UNAVAILABLE
- DESTINATION_UNCALIBRATED
- SELL_TARGET_UNCALIBRATED
- INVALID_LOGICAL_LOCATION
- UNSUPPORTED_ACTION_MAPPING

## Safety / scope

H6-B MUST NOT:
- call GestureArbiter / GestureDispatcher / GestureDispatcherFactory
- construct AutomationAction
- call Accessibility / Shizuku / Root / shell input
- call dispatchGesture
- capture frames, OCR, CV, or mutate H5 ledger/state
- enable actions
- invent coordinate constants

Acceptance safety remains:
`ACTION_ENABLED=false`
`REAL_ACTION_REACHABLE=false`
`ACTION_EXECUTED=0`

H6-C / real dispatch wiring is NOT authorized by H6-B.