# Verification evidence

Results are recorded only after the named command completed. Credentials were read from the mounted users file and were not printed or committed.

## Local environment

- Microsoft Windows 11 Home 10.0.26300
- Intel Core i7-8550U, 4 cores/8 logical processors, 11.9 GiB RAM
- OpenJDK 21.0.2, Maven 3.6.3, PostgreSQL 16.8, Python 3.11.9
- Two native API processes on ports 18081/18082 sharing one isolated PostgreSQL cluster
- Candidate feed: 225,748 nonblank lines; replay batch 250; four HTTP connections

These are development measurements, not substitutes for the required isolated Linux amd64 bundle rehearsal.

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

The required target is at least 2,000 ingest lines/s and viewport p95 below 100 ms for up to 1,000 matching assets. The local baseline passes both under the target's unspecified viewport concurrency. The extra four-client stress number is retained rather than presented as a pass.

## Reproduction

1. Run `mvn clean package`; inspect the baseline JAR and verify that it contains only `V1__base.sql`.
2. Start PostgreSQL 16.8 with an empty database and two API processes using the same database and token secret.
3. Run `python tests/contract.py users.json API_A API_B` and `python tests/api-contract.py users.json API_A`.
4. Set `IT_DB_URL`, `IT_DB_USER` and `IT_DB_PASSWORD`; run `mvn test`.
5. Run the supplied `replay.py --speed max --connections 4` against the full feed, capture totals/rate, record stats, replay again and compare accepted/assets/alerts.
6. Run `python tests/viewport.py users.json API_A 250 1`. The script derives and verifies a real bbox with at most 1,000 matches and checks every response count.

## Pending acceptance evidence

- Build/inspect all linux/amd64 images and rehearse clean v1.0.0/v1.1.0 installs from the actual archive without network access.
- Run traffic through packaged HAProxy and prove both API replicas receive the concurrency scenarios.
- Capture a browser trace during 5x replay; target no main-thread task over 200 ms.
- Capture the visible disconnect and automatic convergence after a 30-second outage.
- Complete keyboard/screen-reader/WCAG 2.1 AA evidence and verify the browser contacts no external host.
- Rehearse the populated, traffic-bearing v1.0.0 → v1.1.0 rolling upgrade with zero failed requests.
