# Verification evidence

Results are recorded only after the named command completed. Credentials were read from the mounted users file and were not printed or committed.

## Local environment

- Microsoft Windows 11 Home 10.0.26300
- Intel Core i7-8550U, 4 cores/8 logical processors, 11.9 GiB RAM
- OpenJDK 21.0.2, Maven 3.6.3, PostgreSQL 16.8, Python 3.11.9
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
- Rehearse the populated, traffic-bearing v1.0.0 → v1.1.0 rolling upgrade with zero failed requests.
- Repeat the final combined v1.0.0/v1.1.0 release archive rehearsal on an independent Linux x86_64 host with external networking disabled.
