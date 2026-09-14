# H5-C Ownership Snapshot — Source Evidence

Base: `jinchan-clean-base` @ `d49de551ad4fada583849cc47b57e0d0a861644d`

## Frozen inputs

H5-C consumes H5-A `JinChanUnitLedger.snapshot()` only. H5-B is responsible for conservative location reconciliation; H5-C does not infer movement or perception facts.

The ledger already exposes immutable unit records with stable UID, canonical/nullable `heroKey`, nullable `starLevel`, `equivalentCopies`, lifecycle state, location, source, and revision metadata.

## Ownership semantics

Ownership means ledger-confirmed units, not Shop observations and not raw visual occupancy.

Only ACTIVE ledger units are candidates for current ownership. CONSUMED units are historical and must not count as owned.

Known hero ownership can be aggregated only when `heroKey` is known. Unknown identity remains explicit and must never be guessed or silently assigned to a hero.

Locations remain explicit: BOARD, BENCH, UNKNOWN, NONE. H5-C may expose counts by known location, but UNKNOWN/NONE must not be rewritten as Board/Bench.

Star semantics reuse H5-A exactly: star 1/2/3 corresponds to 1/3/9 equivalent copies; invalid or unknown star remains unknown and must not be converted to one star.

## Required output

Produce a deterministic immutable ownership snapshot suitable for future Decision input. It should include at least:
- source ledger revision;
- deterministic active-unit projections preserving stable UID;
- known-hero aggregates;
- Board/Bench ownership counts where location is known;
- unresolved identity/star/location diagnostics or counts;
- no perception/frame/pixel/action references.

Known-hero aggregation must not count UNKNOWN identity under a fabricated key.

## Safety and scope

H5-C is a pure projection/aggregation layer. It must not mutate `JinChanUnitLedger`.

Out of scope:
- raw Bench CV/OCR;
- Board visual identity;
- Shop purchase confirmation;
- movement reconciliation changes;
- GameData research or hero remapping;
- Decision scoring;
- Actions/gestures;
- H6.

Safety remains:
`ACTION_ENABLED=false`
`REAL_ACTION_REACHABLE=false`
`OVERLAY_DEFAULT_ENABLED=false`
`ACTION_EXECUTED=0`

## Acceptance principle

Given the same ledger snapshot, H5-C must return the same ownership snapshot. UNKNOWN remains UNKNOWN, CONSUMED does not count as current ownership, and the projection never changes ledger revision/state.

## Implemented projection contract

`JinChanOwnershipProjector` accepts either an immutable `UnitLedgerSnapshot` or a `JinChanUnitLedger`. The ledger overload
captures exactly one snapshot before delegating to the pure projection. Output units are ordered by UID, known-hero totals
are ordered by hero key, and unknown-star units contribute to unit and unresolved counts without contributing fabricated
equivalent copies.
