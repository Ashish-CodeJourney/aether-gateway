# Redis key schema

Reference for every Redis key the gateway reads or writes. Shared
contract across `gateway-quota` (M3), `gateway-cache` (M4), and
`gateway-router` (M2's breaker hint). Copied from PRD section 12.2 and
kept in sync with the actual implementation; if a key's shape changes
during implementation, update this file and the milestone's `STATUS.md`
entry in the same change.

| Key | Type | Purpose | TTL | Written by | Read by |
|---|---|---|---|---|---|
| `q:rps:{keyId}` | Hash (`tokens`, `last_refill`) | Token bucket state for per-key RPS limiting | 2x the bucket's window | `gateway-quota` Lua script | `gateway-quota` Lua script |
| `q:tokens:{keyId}:{yyyyMM}` | String (counter) | Monthly token consumption for budget enforcement | 40 days | `gateway-quota` (on reservation and reconciliation) | `gateway-quota` (on budget check) |
| `q:reserved:{requestId}` | String (reserved token count) | Pessimistic reservation pending reconciliation (ADR-006) | 5 minutes | `gateway-quota` (at dispatch time) | `gateway-quota` (at reconciliation time) |
| `q:conc:{keyId}` | Set (request IDs) | In-flight request IDs for the concurrency cap (F5.3) | 5 minutes | `gateway-quota` (add on start, remove on completion) | `gateway-quota` (size check) |
| `cb:hint:{provider}:{model}` | String (state marker) | Advisory circuit breaker hint, fast-open only (ADR-004) | 30 seconds | `gateway-router` (on local breaker OPEN transition) | `gateway-router` (other replicas, to accelerate their own open) |
| `cache:exact:{ns}:{hash}` | String (JSON response body) | Hot exact-match cache ahead of Postgres, introduced Phase 07 | route TTL | `gateway-cache` | `gateway-cache` |
| `cfg:routing:version` | String (integer counter) | Config generation counter for hot reload (F2.7) | none (persists until changed) | `gateway-router` (on reload) | `gateway-router` (to detect stale in-memory policy, if polling is used) |

## Notes

- The token bucket (`q:rps:{keyId}`) is read, refilled, checked, and
  decremented in a **single Lua script**, never as separate round trips;
  doing it as three calls is a race under concurrent load (PRD section
  12.2's explicit warning, and Phase 06's top named risk).
- `q:reserved:{requestId}`'s 5-minute TTL is the safety net for a request
  that fails or crashes before reconciliation runs; the TTL must be set
  on every write, not just the happy path.
- `cb:hint:{provider}:{model}` may only ever be used to open a breaker
  faster (fast-open). It must never be read to justify closing a breaker;
  only local, direct recovery evidence may do that (ADR-004).
- `cache:exact:{ns}:{hash}`, `cfg:routing:version` are introduced by
  Phase 07 and Phase 05 respectively; listed here now since the schema is
  fixed in Phase 02 even though the code that uses them lands later.
