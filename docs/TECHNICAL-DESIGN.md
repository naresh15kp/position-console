# Position Console Technical Design

## 1. Purpose

Position Console is an offline position-monitoring system for approximately 3,000 moving assets. It accepts an at-least-once, parallel, out-of-order report feed; maintains immutable tracks and current positions; detects entry into restricted geographic zones; and supports an audited operator workflow for claiming and resolving alerts.

The system is designed for two stateless API instances sharing one PostgreSQL database. Correctness therefore depends on database transactions, row locks, constraints and triggers rather than process-local state.

## 2. Requirement summary

The requested delivery has four major parts:

1. **Backend:** Java 21 and Spring Boot 3 API implementing the fixed HTTP contract.
2. **Frontend:** React 18 and strict TypeScript console with a live map and alert workflow.
3. **Offline operations:** Docker Compose deployment containing PostgreSQL, two API instances, a load balancer and the console, with no runtime internet dependency.
4. **Versioned change:** a no-downtime v1.0.0-to-v1.1.0 rollout that adds a mandatory stored report source type while traffic continues.

The most important acceptance conditions are data integrity under concurrent duplicate delivery, bounded performance, operator usability, air-gapped installation and a reproducible rolling upgrade.

## 3. Scope

### Included

- Bearer-token authentication and role authorization
- Streaming NDJSON batch ingest
- Immutable asset tracks and materialized current positions
- Restricted/advisory GeoJSON zone handling
- Entry alert creation
- Claim, release and resolve workflow
- Append-only alert audit trail
- Bounded asset, track, alert and audit queries
- Live polling console with canvas map
- Two API replicas behind HAProxy
- Offline linux/amd64 release bundle
- Forward-only Flyway migrations
- Restartable source-type backfill and rolling deployment

### Deliberately excluded

- Interpolation between reported positions
- External map tiles or CDN resources
- A message broker or distributed cache
- User administration APIs
- Zone editing APIs
- Kubernetes deployment

These exclusions keep the air-gapped operational footprint small and avoid components that are unnecessary for the required scale.

## 4. System context

```text
Position feed / operator browser
                |
                v
        +----------------+
        | HAProxy :8080  |
        +-------+--------+
                |
       +--------+---------+
       |                  |
       v                  v
+-------------+    +-------------+
| API replica |    | API replica |
|      A      |    |      B      |
+------+------+    +------+------+
       |                  |
       +--------+---------+
                |
                v
        +---------------+
        | PostgreSQL 16 |
        +---------------+

HAProxy also routes browser content to the local React web container.
```

All replicas use the same database, token secret, account file and zone configuration. An API instance can be replaced without transferring in-memory application state.

## 5. Component design

### 5.1 HAProxy edge

The edge container:

- exposes the complete system on port 8080;
- serves the local SPA through the web container;
- balances API requests across both healthy API replicas;
- supports draining one replica during a rolling upgrade;
- performs no authentication or business logic.

### 5.2 Spring Boot API

| Component | Responsibility |
|---|---|
| `Api` | Fixed HTTP routes, request parameter bounds and role entry points |
| `Auth` | Account loading, credential checks, signed bearer tokens and role enforcement |
| `Ingest` | Streaming validation, report insertion, current-state advancement and alert detection |
| `Queries` | Bounded reads, paging and transactional alert commands |
| `Zones` | GeoJSON validation, antimeridian normalization and point-in-polygon evaluation |
| `Problems` | Stable RFC 9457-style problem responses |

Controllers perform no direct database access. Transaction boundaries are on service methods.

### 5.3 PostgreSQL

PostgreSQL is both the durable store and the concurrency coordinator. It provides:

- report identity through the `reports` primary key;
- per-asset serialization through locked `assets` rows;
- one-active-alert enforcement through a partial unique index;
- per-alert command serialization through `FOR UPDATE`;
- atomic state and audit writes through transactions and triggers;
- forward-only schema management through Flyway.

### 5.4 React console

The console contains:

- sign-in using configured operator or supervisor accounts;
- a responsive canvas map of current assets and zones;
- selectable asset details and recent track;
- non-map asset-by-zone lists;
- alert filtering, paging and lifecycle actions;
- visible live/reconnecting state;
- throttled screen-reader announcements for newly observed open alerts.

The browser uses bounded polling rather than a push channel. This keeps reconnection deterministic with two stateless API instances: every successful poll replaces the relevant snapshot from shared database state.

## 6. Data model

### `reports`

Immutable accepted reports.

Key fields:

- `report_id`: global identity and primary key
- `asset_id`: owning asset
- `observed_at`: PostgreSQL microsecond timestamp component
- `observed_ns`: remaining 0-999 nanoseconds
- coordinates and optional report attributes
- `source_type`: mandatory classification after v1.1.0 finalization

The `(asset_id, observed_at, observed_ns, report_id)` index supports deterministic track reads.

### `assets`

Materialized current position for each known asset.

It stores:

- current report identity and event timestamp;
- current coordinates;
- serialized current zone memberships.

The row is also the database mutex for concurrent reports affecting that asset. A deferred constraint trigger proves that the current report belongs to the same asset.

### `alerts`

Alert lifecycle state and ownership:

- `OPEN`
- `CLAIMED`
- `RESOLVED`

A partial unique index on `(asset_id, zone_id)` for `OPEN` and `CLAIMED` prevents more than one active alert even when concurrent transactions race.

### `alert_audit`

Append-only lifecycle history. A trigger inserts audit rows in the same transaction as alert state changes. Separate triggers reject update, delete and truncate operations.

### `ingest_rejections`

Stores rejected non-empty input lines with:

- request ID;
- line number;
- stable rejection code;
- escaped raw line;
- server receive time.

### `ingest_batches`

Stores aggregate batch counts. A check constraint requires:

```text
received = accepted + duplicates + rejected
```

### `source_type_backfill`

A singleton checkpoint records progress through the v1.1.0 source-type backfill so the operation can be interrupted and resumed.

## 7. Ingest flow

1. HAProxy routes the request to either API replica.
2. `Auth` validates the bearer token and requires the `INGEST` role.
3. `Ingest.read` checks media type and streams at most 5 MB and 1,000 non-empty lines.
4. Each line is independently parsed and validated outside the database transaction.
5. Invalid lines are retained as rejections; they do not prevent valid lines from continuing.
6. `Ingest.store` starts one service transaction.
7. The first occurrence of each report ID in the batch is selected and sorted.
8. Reports are bulk-inserted with `ON CONFLICT (report_id) DO NOTHING`.
9. Only rows returned by the insert are eligible to change current state or raise alerts.
10. Missing asset rows are created.
11. Affected asset rows are locked in sorted order.
12. New reports are processed in delivery order:
    - older or equal event times remain track-only;
    - a strictly newer event time advances current state;
    - entry into each restricted zone creates an alert candidate.
13. Current asset rows and alert candidates are persisted.
14. Invalid lines and batch totals are stored.
15. The transaction commits, making all effects visible together.

## 8. Integrity rules

| Rule | Design mechanism |
|---|---|
| Exactly-once effect | `report_id` primary key and insert-returning gate prevent duplicate downstream effects |
| Event-time ordering | Locked asset row plus strict `newTime > currentTime` comparison |
| One active alert | Partial unique index for active statuses |
| One owner | Alert row lock and status/owner checks inside one transaction |
| Atomic audit | Database trigger executes in the alert-change transaction |
| Bad-input isolation | Per-line parse results and separate persisted rejection records |

Database constraints are the final guard so the rules remain true if future application code bypasses a service precheck.

## 9. Zone processing

Zones are loaded from bundled GeoJSON at application startup.

The loader:

- accepts polygons and multipolygons;
- preserves interior holes;
- validates coordinates and geometry;
- separates restricted zones from all displayed zones;
- unwraps adjacent longitude coordinates into a continuous frame;
- prepares JTS geometries for repeated point lookup.

Point checks test the original longitude and copies shifted by plus/minus 360 degrees. This allows polygons crossing the antimeridian to match points represented on either side of it.

JTS `contains` is used, so a point exactly on a boundary is outside. Advisory zones appear in membership and on the map but never generate alerts.

## 10. Alert workflow

### Open

The system opens an alert when a current position changes from outside a restricted zone, or no previous position, to inside it. Remaining inside does not create another alert.

### Claim

1. Lock the alert row.
2. Reject resolved alerts.
3. Return success without a new audit row if already owned by the caller.
4. Reject if another user owns it.
5. Set transaction-local audit actor.
6. Change `OPEN` to `CLAIMED`.

### Release

The owner or a supervisor can change `CLAIMED` to `OPEN`. Ownership fields are cleared.

### Resolve

The owner or a supervisor can change `CLAIMED` to `RESOLVED` with a non-blank 1-500 character note. Repeating resolution by the same user is idempotent.

## 11. API design

The external contract is defined by `openapi.yaml`.

API conventions:

- business routes use `/api/v1`;
- JSON names use camel case;
- timestamps are UTC ISO-8601 with milliseconds;
- failures use `application/problem+json` with stable codes;
- every list is bounded;
- alert and audit lists use cursor paging;
- asset and track queries fetch one extra sentinel row to report truncation without a count query;
- viewport bounding boxes support antimeridian crossing.

## 12. Authentication and authorization

Accounts are loaded from a deploy-time mounted JSON file. The file and passwords are not built into images or committed.

Login returns a compact signed token containing:

```text
base64url(username).expiryEpochSeconds.base64url(HMAC-SHA256)
```

Tokens expire after eight hours. Authentication verifies token structure, signature, expiry and continued existence of the configured account.

Roles:

- `INGEST`: report ingest and statistics
- `OPERATOR`: asset/alert reads and normal alert actions
- `SUPERVISOR`: operator capabilities plus management of alerts owned by another user

## 13. Frontend state and rendering

### State ownership

- Session state lives at the application root.
- Current positions and zones are snapshot state.
- Alerts are a paged snapshot keyed by status and cursor.
- Selected asset and track are local interaction state.
- Command and reconnect messages are transient UI state.

### Polling

- Assets poll every second.
- Alerts poll every 1.5 seconds.
- The next poll is scheduled after the current request completes, preventing overlapping requests.
- Request cancellation prevents unmounted or superseded effects from updating state.
- A successful poll after an outage replaces stale data and restores the `Live` indicator.

### Rendering

Canvas rendering avoids thousands of React DOM nodes. The map:

- projects longitude using the shortest wrapped delta;
- caps device-pixel-ratio scaling to bound canvas memory;
- uses even-odd polygon filling to preserve holes;
- shows heading-oriented asset symbols;
- supports pointer pan, zoom, reset and nearest-asset selection.

The asset-by-zone list provides an accessible non-map representation.

## 14. Performance design

### Ingest

- parse outside the transaction;
- bulk insert reports;
- deduplicate within the request;
- lock only affected assets;
- acquire asset locks in sorted order;
- batch current-state, alert and rejection writes.

### Viewport

- current positions are materialized in `assets`;
- a partial viewport index excludes assets without positions;
- a lateral lookup fetches only each matching current report;
- stored zone membership avoids geometry work during reads;
- result size is bounded.

### Frontend

- canvas rendering;
- bounded snapshots;
- no remote map resources;
- no animation loop when state is unchanged;
- polling frequency chosen for operational freshness without request overlap.

Measured results and known environment-dependent misses are recorded in `docs/VERIFICATION.md`.

## 15. Deployment and security

Docker Compose starts:

- PostgreSQL 16.8
- API replica A
- API replica B
- React/nginx web container
- HAProxy edge

Runtime controls include:

- pinned linux/amd64 images;
- read-only filesystems for application-facing containers;
- all Linux capabilities dropped;
- `no-new-privileges`;
- non-root API and web users;
- no privileged mode;
- no host networking;
- no Docker socket mount;
- no mount outside the bundle directory;
- deploy-time secrets through environment variables and mounted files.

Structured logs are written to standard output and include validated request IDs. API errors hide stack traces, SQL and internal implementation names.

## 16. Offline delivery

The v1.1.0 GitHub release contains:

- one compressed bundle below 2 GB;
- `SHA256SUMS` listing the bundle;
- all v1.0.0 and v1.1.0 application images;
- edge and PostgreSQL images;
- Compose configuration;
- environment and user templates;
- installation, runbook, verification and CR-1 documentation;
- upgrade and backfill scripts.

The target requires Docker Engine, the Compose plugin and standard archive/checksum tools only. It does not build or pull anything.

## 17. v1.1.0 change design

CR-1 adds mandatory `source_type` classification:

- `SAT*` becomes `SATELLITE`;
- `GND*` becomes `GROUND`;
- `MOB*` becomes `MOBILE`;
- all other or null values become `UNKNOWN`.

The expand-and-contract sequence is:

1. Add nullable column, classifier function, old-writer trigger, unvalidated constraint and checkpoint.
2. Roll API replicas one at a time.
3. Serve correct legacy results using `coalesce(source_type, classify_source(source))`.
4. Backfill in restartable primary-key pages, committing and pausing between pages.
5. Verify zero null and zero misclassified rows.
6. Validate the check and set physical `NOT NULL`.

Lock timeouts prevent deployment from waiting indefinitely for DDL locks. The trigger keeps writes from both binary versions valid during mixed-version operation. Full lock, pause, rollback and timing details are in `docs/CR-1.md`.

## 18. Failure handling

| Failure | Behavior |
|---|---|
| Duplicate delivery | Counted as duplicate; no state change |
| Invalid line | Persisted rejection; valid lines continue |
| API replica loss | HAProxy routes to the healthy replica |
| Database unavailable | Health endpoint fails and replica leaves rotation |
| Browser disconnection | Shows reconnecting and converges on a later successful poll |
| Concurrent claim | Row lock selects one winner; loser receives a specific conflict |
| Backfill interruption | Committed pages and checkpoint remain; rerun resumes |
| Upgrade lock contention | Two-second lock timeout aborts the step for safe retry |

## 19. Testing strategy

### Unit and component tests

- NDJSON validation and size/line limits
- polygon holes, multipolygons, overlaps, boundaries and antimeridian behavior
- database uniqueness, deferred ownership and immutable audit constraints

### Contract and concurrency tests

- authentication, role and media-type failures
- fixed response/problem shapes
- duplicate ingest across two direct replicas
- conflicting payload with the same report ID
- late and equal event times
- concurrent alert claims
- idempotent lifecycle commands
- rejection isolation and line numbering

### System tests

- full replay and second replay
- required viewport latency
- browser responsiveness at 5x feed speed
- reconnect after a 30-second outage
- accessibility and external-request checks
- clean v1.0.0 and v1.1.0 installs
- rolling upgrade under traffic
- interrupted/resumed backfill
- binary rollback
- archive-only installation

## 20. Trade-offs and future evolution

### Polling versus push

Polling was selected for deterministic convergence and simpler operation. At larger scale, a shared event stream with sequence numbers and resumable subscriptions could reduce repeated snapshots.

### JTS versus PostGIS

JTS avoids another database extension and is sufficient for the configured zone set. Dynamic or very large zone catalogs would favor spatial database indexes.

### Materialized current state

Duplicating current report fields in `assets` greatly reduces viewport work. The deferred ownership constraint and service transaction protect consistency.

### PostgreSQL portability

The implementation uses PostgreSQL-specific conflict handling, partial indexes, constraint triggers, row locking and transaction-local settings. A MariaDB version would use equivalent duplicate-key logic, generated active keys, deterministic `SELECT ... FOR UPDATE` locking and audit triggers.

### Known limitations

- The final archive rehearsal used Docker Desktop's linux/amd64 engine rather than an independent physical Linux host.
- Automated accessibility checks passed, but a manual screen-reader announcement check remains desirable.
- The account file contains deploy-time plaintext passwords because that format is part of the supplied operating model; production evolution should use password hashes or an approved identity provider.

## 21. Source references

- API implementation: `src/main/java/console`
- Database migrations: `src/main/resources/db/migration`
- Frontend: `web`
- API contract: `openapi.yaml`
- Deployment: `compose.yaml`
- Installation: `INSTALL.md`
- Change record: `docs/CR-1.md`
- Runbook: `docs/RUNBOOK.md`
- Verification evidence: `docs/VERIFICATION.md`

