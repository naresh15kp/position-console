# Position Console

A Java 21/Spring Boot position-ingest API, PostgreSQL 16.8 store and offline React/TypeScript operator console. Two stateless API replicas share PostgreSQL behind HAProxy. See [INSTALL.md](INSTALL.md), the [runbook](docs/RUNBOOK.md) and [verification evidence](docs/VERIFICATION.md).

## Build and run

Use a Linux amd64 Docker engine with Compose. From a clean v1.0.0 checkout run `sh build-images.sh 1.0.0`. Copy `deployment.env.example` to `.env`, set a database password and shared token secret of at least 32 bytes, and put the deploy-time users array at `users.json`. Run `docker compose --env-file .env --env-file version-1.0.0.env up -d`. The console and API share <http://localhost:8080>.

For native verification, use Java 21 and run `mvn clean package`, not only `mvn package`; the clean step prevents resources from another checked-out version remaining in `target`. Run `npm ci && npm run build` in `web`. Start two JAR processes on different ports against one PostgreSQL database, then run:

```text
python tests/contract.py users.json http://127.0.0.1:18081 http://127.0.0.1:18082
python tests/api-contract.py users.json http://127.0.0.1:18081
python tests/viewport.py users.json http://127.0.0.1:18081 250 1
```

Set `IT_DB_URL`, `IT_DB_USER` and `IT_DB_PASSWORD` before `mvn test` to enable the database-bypass invariant tests. Credentials are never command-line literals in recorded evidence.

## Architecture and data flow

The edge round-robins API requests and serves the local SPA. API replicas share only PostgreSQL and mounted account configuration. The ingest reader validates at most 5 MB/1,000 non-empty NDJSON lines outside the transaction. One service transaction bulk-inserts new report IDs, creates and locks affected asset rows in sorted order, advances current state strictly by event time, inserts entry alerts, stores rejections and records batch counts.

Report identity is a primary key. Asset rows are the cross-replica mutex: unrelated assets remain concurrent, while different reports for one asset serialize. Alert commands lock only their alert row. The UI polls bounded snapshots from shared state, so the first successful poll after reconnect converges regardless of which replica serves it. Current zone membership is derived when current position changes and stored with that row, avoiding repeated geometry work on viewport reads.

| Rule | Enforcing mechanism | Test that proves it |
|---|---|---|
| R1 exactly-once effect | Report-ID primary key; only IDs returned by `INSERT ... ON CONFLICT DO NOTHING` reach asset/alert work | `tests/contract.py` races duplicate and conflicting-payload IDs directly through both replicas; full second replay accepts zero |
| R2 event-time order | Sorted per-asset row locks and strict `observedAt` comparison; immutable report track | Direct-replica newer/late/equal-time scenario keeps late/equal reports in track without moving current or alerting |
| R3 one active alert | Partial unique `(asset_id, zone_id)` index for OPEN/CLAIMED | Entry while active, resolution, exit and re-entry scenario plus database-bypass duplicate insert |
| R4 one owner | Alert `FOR UPDATE` lock and in-transaction owner/status checks | Two operators claim through different replicas; exactly one 200 and one `409 ALERT_ALREADY_CLAIMED` |
| R5 audit trail | State-change trigger in the command transaction; audit UPDATE/DELETE/TRUNCATE guards | Exact lifecycle/idempotency actions plus database-bypass rollback and immutability tests |
| R6 bad input isolation | Per-line parsing; bulk persisted raw/code/time/request ID; checked batch accounting | Mixed malformed/missing/valid request preserves non-empty line numbers and accepts valid reports |

## Important decisions and interpretations

1. **Lock affected assets, not all ingest.** A process lock fails across replicas and a global database advisory lock met correctness but limited measured throughput. Sorted asset-row locking avoids deadlocks while preserving concurrency between unrelated assets.
2. **Separate immutable track and current state.** Viewport reads do not reconstruct 3,000 current positions from 215,000 reports. The database verifies that each current report belongs to its asset.
3. **Use constraints as final concurrency guards.** Report identity and active-alert uniqueness remain true even if a future caller bypasses service prechecks.
4. **Use JTS without PostGIS.** Prepared polygons preserve holes/multipolygons and an unwrapped longitude frame handles the antimeridian without another operational component.
5. **Use bounded polling and canvas rendering.** At this dataset size polling makes reconnect convergence and two-replica routing simple. A push stream would require shared sequence/resume semantics; browser profiling remains an acceptance gate.

The brief says “inside,” so JTS `contains` makes a boundary point outside. ADVISORY zones are returned for display/membership but never alert. For equal `observedAt`, the current report already holding the asset row wins; another unique report at that instant remains in deterministic track order and does not move current state or alert. No interpolation is performed.

## Supplied-data findings

The supplied feed contains 225,748 nonblank lines for 3,000 assets. A clean replay records 215,965 accepted unique reports, 8,920 valid duplicates and 863 rejected lines. The maximum supplied line is 100,014 characters. Five duplicate IDs have conflicting payloads; identity is defined only by `reportId`, so the first committed valid payload wins. The zone file includes a hole (Z-03), a multipolygon (Z-06), overlap (Z-04/Z-05), clockwise exterior winding (Z-02), an antimeridian polygon (Z-08), advisory zones and a very small restricted polygon (Z-11). Focused tests cover each geometry behavior.

## PostgreSQL and MariaDB

PostgreSQL-specific mechanisms are `ON CONFLICT ... RETURNING`, partial unique indexes, deferred constraints/triggers, row-lock behavior and Flyway's PostgreSQL coordination. On MariaDB/InnoDB, use duplicate-key handling that distinguishes only report identity, a generated nullable “active key” under a unique index, a locked asset mutex row in deterministic order, and equivalent audit triggers. Store UTC fractional time plus the sub-microsecond remainder, then rerun deadlock, retry and isolation tests on the chosen release.

## Building within an air gap

Mirror approved checksum-verified base images, Maven artifacts and npm packages into internal registries through the site's media-transfer process. Build on a Linux amd64 worker with only those mirrors, pinned lockfiles and no public egress. Generate and checksum the image archive in staging, verify it on a clean isolated host, and promote the exact archive tested. Patches follow the same dependency-inventory and checksum process.

## Evidence, limitations and AI assistance

Measured commands, environments and pending checks are in [docs/VERIFICATION.md](docs/VERIFICATION.md). Current Windows evidence exceeds the ingest and required single-client viewport targets; the four-client viewport stress result exceeds 100 ms and is reported separately. Isolated Linux archive rehearsal, 5x browser trace, 30-second outage capture and WCAG audit remain pending, so v1.0.0 is not yet tagged.

GitHub Copilot was used to read local inputs, implement code/tests and review results. Three concrete corrections to generated output are already evidenced:

1. A redundant composite report unique key caused `ON CONFLICT (report_id)` to leak two 500s during replay. PostgreSQL logs identified the second conflict arbiter; it was replaced by a deferred ownership trigger, then replayed with zero HTTP errors.
2. A non-clean Maven build retained the later V2 migration in the v1.0.0 JAR. Flyway startup logs exposed schema version 2; verification now uses `mvn clean package` and checks baseline JAR migration entries.
3. The first denormalized-membership query selected only `reports.*`, causing viewport 500s. The end-to-end viewport script caught it; the join now projects `assets.zone_ids`, and contract plus viewport suites pass.
4. The generated Vite proxy used `/api`, which also intercepted the local `api.ts` module and rendered a blank development page. The browser test exposed the 404; the proxy now matches only `/api/`, and the full accessibility/reconnect profile passes.
5. The generated Compose hardening omitted the API health check while edge required `service_healthy`, so startup refused to proceed. The archive rehearsal exposed it; the health check was restored to the API anchor and the complete topology reaches healthy.
6. HAProxy rejected the generated config because it lacked a final newline. Container logs identified the exact parse error; the file was corrected and the rebuilt non-root/read-only edge passes health and drain tests.
7. The Windows working tree converted the generated bundle script to CRLF despite its Linux target. Running it in a Linux CLI container exposed `set: illegal option`; release scripts were normalized and the actual archive then built successfully.

Candidate review/interview preparation effort remains to be added by the candidate. AI/session timings are not substituted for that value.
