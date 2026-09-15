# H6-C4D — Rikka Observation / Decision Bridge（2026-09-16）

## Architecture

Rikka AI remains the sole high-level strategy and planning brain. HZZS synchronously turns the current capture iteration into perception/state, reconciles unit locations, projects ownership, assembles one joined snapshot, and exposes a transport-safe observation through the existing Streamable HTTP MCP server. No local strategy, scoring, planning, second transport, or execution path was added.

## Same-evidence ownership wiring

`VisionRuntimeController` calls `publishFrame` and immediately passes that returned value to the session-local `JinChanProductionEvidenceJoiner`. The joiner uses the authoritative typed Board/Bench values, calls `JinChanObservationReconciler.reconcile`, and accepts only `INIT`, `NO_CHANGE`, or `RECONCILED_MOVE`. Ambiguous, malformed, held, unavailable, stale, or otherwise unresolved reconciliation blocks publication.

After successful reconciliation the ledger is snapshotted exactly once for decision evidence. `JinChanOwnershipProjector` consumes that value; the exact projection instance and frame sequence are placed into `JinChanActionContext` and `JinChanSameEvidenceAssembler`. There is no current/latest ledger reread, StateFlow repair, cross-frame join, or hero-to-location inference.

## RikkaObservationV1

The pure mapper projects the exact `JinChanJoinedStateSnapshot`, rather than creating a second game state. V1 includes schema version; session/evidence/revision/capture provenance; typed Shop, Gold, Level, Exp, Board, Bench and orientation state with field status; active units and known-hero aggregates; and unresolved identity/star/location quality counts. `OwnedUnit.uid` and immutable locations are preserved. `UNKNOWN`, `INVALID`, null values, and source reasons are serialized without guessing.

The schema contains no bitmap, pixels, captured-frame lease, OCR engine, mutable ledger, StateFlow, gesture, execution coordinates, coordinator, dispatcher, or action id.

## Existing MCP reuse

The existing server exposes resource `app://rikka/observation/v1/latest` and write tool `submit_rikka_decision_v1`. `RikkaBridgeStore` retains only the latest valid immutable observation plus its exact paired joined snapshot. No HTTP/WebSocket server and no outbound JSON-RPC client request were added.

## RikkaDecisionV1 and validation

V1 accepts `Action`, `NoAction`, or `Blocked`; all carry exactly `sessionId`, `evidenceSequence`, and `ownershipRevision`. Action is the thinnest adapter over existing semantic `JinChanActionIntent`; C4D accepts only Move/Sell, both addressed by UID.

Validation compares provenance against the stored pair. Move/Sell requires exactly one ACTIVE matching UID in the paired ownership snapshot, and its source location is read only from that snapshot. A later ledger mutation cannot affect validation. Valid Action, NoAction, and Blocked all terminate at a typed result: no C3 request and no action execution.

## Safety invariants

- `AppConfig.ACTION_ENABLED=false`; real action remains unreachable and executed count remains zero.
- No C3 call, gesture, coordinate calibration, sell calibration, planner, hero value engine, or legacy strategy was introduced.
- Existing H6-A through H6-C4C contracts remain unchanged.
- Overlay defaults remain unchanged/off at the relevant clean-base boundary.

## Tests and results

`RikkaBridgeTest` covers production same-evidence joining, unavailable reconciliation blocking, immutable pairing, UID preservation, UNKNOWN/INVALID serialization, payload/source prohibitions, latest-store reads, exact/stale/revision provenance, unknown UID, source-location derivation, later-ledger isolation, NoAction/Blocked termination, and absence of execution/C3 calls. Existing H6 tests remain in their original files.

Validation commands and their actual results are recorded in the implementation handoff/PR; environment failures are reported as `BLOCKED_ENV`, never as a pass.
