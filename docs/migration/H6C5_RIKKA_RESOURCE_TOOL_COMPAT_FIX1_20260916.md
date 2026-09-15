# H6-C5 FIX1 — Rikka Observation Resource → Tool Compatibility Adapter

- Date: 2026-09-16
- Base: `c6fd86a009dfbc1cdd831449b659ff0efa2d889f` (jinchan-h6c-execution-wiring, H6-C5 Build #68)
- Branch: `jinchan-h6c5-rikka-observation-tool-fix1`
- Scope: MCP compatibility adapter only (no architecture change)

## CAUSE

RikkaHub Agent injects MCP Tools but does not expose MCP Resource read/list.

On the device the HZZS MCP server is already connected (49/49 tools) and the agent can
call `get_status` / `get_runtime_snapshot` / `inspect`, and `submit_rikka_decision_v1` is
registered in its tool table. However the agent has no MCP Resources capability, so it
cannot read the already published observation through:

```
app://rikka/observation/v1/latest
```

`get_runtime_snapshot` / `inspect` are **not** formal `RikkaObservationV1` read interfaces,
so they cannot be used as a substitute. Blocking layer:
`MCP_RESOURCE_TO_RIKKAHUB_AGENT_COMPATIBILITY`.

## FIX

Expose existing `RikkaObservationV1` publication through read-only
`get_rikka_observation_v1`.

The new tool reads the *same* `RikkaBridgeStore` instance and the *same* immutable
publication that the MCP resource already serves:

```
get_rikka_observation_v1
        ↓
existing RikkaBridgeStore
        ↓
existing latest PairedRikkaEvidence
        ↓
existing RikkaObservationV1
        ↓
existing observation.toJson()
```

Empty publication fails closed:

```json
{ "available": false, "observation": null, "reason": "NO_RIKKA_OBSERVATION" }
```

Existing publication:

```json
{ "available": true, "observation": { "schemaVersion": 1, "...": "unchanged" }, "reason": "OK" }
```

## Invariants

- `ARCHITECTURE_CHANGED=false`
- `RESOURCE_REMOVED=false` — `app://rikka/observation/v1/latest` is retained and untouched
- `SECOND_OBSERVATION_SOURCE=false` — no new cache/store, no re-assembly, no re-read of
  `JinChanUnitLedger`, no `StateFlow` latest repair, no cross-frame/cross-revision repair,
  no perception re-run, no ownership re-projection, no provenance/UID rewrite
- `UNKNOWN` / `INVALID` field statuses are preserved verbatim
- `ACTION_PATH_CHANGED=false`
- `REAL_ACTION_REACHABLE=false`
- `ACTION_EXECUTED=0`
- `AppConfig.ACTION_ENABLED=false` unchanged
- `get_rikka_observation_v1` is `McpToolRisk.READ` — no high-risk gate, no phone approval,
  no disclaimer, no automation permission, no gesture/accessibility dependency

## Files

- `app/src/main/java/top/azek431/hzzs/mcp/executor/RikkaBridgeExecutor.kt`
- `app/src/main/java/top/azek431/hzzs/mcp/McpToolCatalog.kt`
- `app/src/main/java/top/azek431/hzzs/mcp/McpToolLabels.kt`
- `app/src/test/java/top/azek431/hzzs/mcp/RikkaObservationToolTest.kt`

## Preservation

`submit_rikka_decision_v1`, `RikkaDecisionV1`, `RikkaObservationV1`, `RikkaBridgeStore`
publication semantics, same-evidence contract, paired immutable `JoinedState`, UID
validation, provenance validation, H6-A, H6-B, H6-C1..C4D and the C5 dry-run hard stop are
all unchanged. H6-C6 is out of scope.
