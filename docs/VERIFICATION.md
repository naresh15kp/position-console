# Verification evidence

Results are recorded only after the named command completed. Credentials were read from the mounted users file and were not printed or committed.

## Local environment

- Microsoft Windows 11 Home 10.0.26300
- Intel Core i7-8550U, 4 cores/8 logical processors, 11.9 GiB RAM
- OpenJDK 21.0.2, Maven 3.9.9, PostgreSQL 16.8, Python 3.11.9
- Two native API processes on ports 18081/18082 sharing one isolated PostgreSQL cluster
- Candidate feed: 225,748 nonblank lines; replay batch 250; four HTTP connections

These are development measurements, not substitutes for the required isolated Linux amd64 bundle rehearsal.

Docker Desktop 29.9.3 supplied a Linux x86_64 engine with Compose 5.7.1 and WSL data stored on the E: drive. It was used as the local archive rehearsal host; an independent physical Linux host remains desirable before publication.

## Baseline results

| Check | Result |
|---|---|
| Java unit and DB-invariant tests | Passed with `IT_DB_URL` enabled |
| React strict type-check and production build | Passed |
| Direct two-replica rule scenario | Passed duplicate identity, conflicting payload, late/equal event time, active alert, concurrent claim, release/resolve idempotency, audit and mixed bad input |
| Fixed API negative paths | Passed auth, roles, media type, RFC 9457 content type/code, validation and request-ID echo |
| Clean full replay | 225,748 received; 215,965 accepted; 8,920 duplicates; 863 rejected; 0 HTTP errors/retries/failed batches; 9,544 lines/s |
| Second full replay | 0 accepted; 224,885 duplicates; 863 rejected; 0 HTTP errors/retries/failed batches; 43,830 lines/s |
| Entity totals across second replay | accepted 215,965 → 215,965; assets 3,000 → 3,000; alerts 253 → 253 |
| Required viewport measurement | 250 requests, one client, exactly 1,000 assets/response, p95 56.42 ms, max 64.23 ms |
| Additional viewport stress | 250 requests, four clients, exactly 1,000 assets/response, p95 216.79 ms, max 404.34 ms |
| 5x console profile | Full 225,748-line replay at 1,502 lines/s; 3,000 assets/12 zones; 72 ms maximum main-thread long task; zero tasks over 200 ms; no external requests |
| Disconnect/reconnect | Browser offline for 30 seconds; visible `Reconnecting`; recovered to `Live` without reload with 3,001 assets/12 zones |
| Accessibility/browser host check | Zero Axe WCAG 2.1 A/AA violations; keyboard reached an asset button with a visible outline; no request to a non-local host |
| linux/amd64 images | API, web, edge and PostgreSQL 16.8 inspected as `linux/amd64`; API UID 10001, web `nginx`, edge `haproxy` |
| Runtime hardening | API/web/edge run with read-only root filesystems, all capabilities dropped, no privileged mode, and a private Compose network |
| Two-replica edge routing | HAProxy logs show alternating `api/a` and `api/b` for ingest, reads, lifecycle commands and negative contract checks |
| Offline archive rehearsal | 227.2 MiB archive; removed all four runtime images, loaded only `bundle/images.tar.gz`, started a fresh project with `pull_policy: never`, then passed contract and browser checks |

The required target is at least 2,000 ingest lines/s and viewport p95 below 100 ms for up to 1,000 matching assets. The local baseline passes both under the target's unspecified viewport concurrency. The extra four-client stress number is retained rather than presented as a pass.

## CR-1 and rolling-upgrade results

| Check | Result |
|---|---|
| Clean v1.1.0 install | V2 completed with `reports.source_type` physically `NOT NULL`; baseline contract and source-type classification/filter checks passed |
| Small rolling-upgrade traffic probe | 3,625 requests, 0 failures: 1,094 ingests and 2,531 reads |
| Populated v1.0.0 preparation replay | Same 225,748-line feed and expected counts, 0 HTTP errors; 1,718 lines/s on the later Docker Desktop run |
| Populated rolling-upgrade traffic probe | 2,433 requests, 0 failures: 1,041 ingests and 1,392 reads while A, then B and the web console were replaced |
| Interrupted backfill | A 3-second statement timeout stopped after 75,000 committed rows; 141,082 rows remained null and the checkpoint was preserved |
| Resumed backfill | Completed in 6.84 seconds; 216,082 legacy rows processed, including writes made before V2 committed |
| Verification/finalization | 0 null and 0 misclassified rows; check validated; physical `NOT NULL` set; old-writer trigger enabled |
| Binary rollback | A v1.0.0 API was recreated and became healthy against the finalized v1.1.0 schema, then was rolled forward again |
| Post-upgrade contracts | Baseline two-replica scenario, source-type behavior, fixed negative paths, Maven tests and packaged V1/V2 migration check passed |
| Post-upgrade browser | No external requests or Axe A/AA violations; visible keyboard focus; recovered after a 30-second outage; 179 ms maximum long task |
| Post-upgrade viewport | 1,000 assets/response; v1.1.0 p95 102.74 ms and rolled-back v1.0.0 p95 108.54 ms on Windows Docker Desktop |
| Combined offline archive | Approximately 249 MiB; checksum verified; required docs/templates present; no credentials, brief or candidate-pack paths |
| Archive-only clean installs | Deleted all six runtime image tags, loaded only the embedded archive, then passed clean v1.1.0 physical `NOT NULL`/contract checks and clean v1.0.0 contract checks |
| Archive-only rolling upgrade | Populated v1.0.0 rolled to v1.1.0 under 2,199 requests with 0 failures; 113 legacy rows backfilled; verification/finalization and source-type checks passed |

The later Docker replay and viewport samples miss the brief's performance targets and are not represented as passes. The same baseline code previously reached 9,544 lines/s and 56.42 ms p95 in the native local topology. On the populated Docker database, PostgreSQL began flattening the viewport join into a full-history hash join: direct execution was 64.69 ms. The query now preserves indexed current-report lookups and directly executes in 18.18 ms, but end-to-end Docker p95 remained 102.74 ms. The matching v1.0.0 rollback result of 108.54 ms indicates CR-1 is not the source of that residual overhead. Investigate container CPU scheduling, cgroup throttling, JSON serialization and Docker Desktop I/O with server-side request timing on the required physical Linux host.

## Reproduction

1. Run `mvn clean package`; inspect the baseline JAR and verify that it contains only `V1__base.sql`.
2. Start PostgreSQL 16.8 with an empty database and two API processes using the same database and token secret.
3. Run `python tests/contract.py users.json API_A API_B` and `python tests/api-contract.py users.json API_A`.
4. Set `IT_DB_URL`, `IT_DB_USER` and `IT_DB_PASSWORD`; run `mvn test`.
5. Run the supplied `replay.py --speed max --connections 4` against the full feed, capture totals/rate, record stats, replay again and compare accepted/assets/alerts.
6. Run `python tests/viewport.py users.json API_A 250 1`. The script derives and verifies a real bbox with at most 1,000 matches and checks every response count.
7. Run `npm ci` in `tests/browser`, set `CHROMIUM_PATH`, start the console, then run `node verify-console.mjs users.json`. On a fresh database run `node profile-5x.mjs users.json` while replaying the supplied feed at 5x.

## Pending evidence before public submission

- Perform a manual screen-reader announcement check; automated Axe, keyboard focus and runtime-host checks pass.
- Repeat the final combined v1.0.0/v1.1.0 release archive rehearsal on an independent physical Linux x86_64 host with external networking disabled; the local archive-only rehearsal used Docker Desktop's Linux amd64 engine.
