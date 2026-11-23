# Position Console

A Spring Boot position-ingest API, PostgreSQL store and offline React operator console. See [INSTALL.md](INSTALL.md) for deployment and [the runbook](docs/RUNBOOK.md) for diagnosis.

## Build and run

Use a Linux Docker engine with Compose. Run `sh build-images.sh 1.0.0` from the v1.0.0 checkout. Copy `deployment.env.example` to `.env`, supply a database password and a shared token signing secret of at least 32 bytes, and put the candidate users file at `users.json`. Run `docker compose up -d`. The console and API are at http://localhost:8080. Start the supplied replay script with `FEED_PASSWORD` set in the environment.

## Architecture and rule checks

The edge sends API requests to two interchangeable replicas and serves the SPA through a local web container. The replicas share only PostgreSQL and mounted configuration. The ingest service parses outside the transaction and stores track, positions, alerts, rejects and accounting in one batch transaction. The UI requests bounded snapshots from the shared store, so a successful poll after reconnect renders current state even if it lands on the other replica.

| Rule | Mechanism | Contract check |
|---|---|---|
| R1 | Report ID primary key and conflict suppression before other mutations | Parallel identical deliveries sum to one stored report |
| R2 | Database transaction advisory lock and strict comparison of observed timestamps | Late in-zone report appears in track while the position stays outside |
| R3 | Partial unique index covers OPEN and CLAIMED | Exit and re-entry while active and after resolution |
| R4 | Alert row lock and in-transaction status and owner checks | Two distinct operators claim concurrently |
| R5 | Audit trigger in the same transaction, with UPDATE/DELETE/TRUNCATE guard | Exact audit actions before and after retries |
| R6 | Validate separately, then store rejection timestamp, reason and content | Mixed valid and invalid lines preserve line numbers and store the valid report |

Run `python tests/contract.py users.json` on a local deployment with two operators. The script creates a uniquely named synthetic asset. Use a disposable database for verification.

## Decisions

1. Use a transaction-scoped PostgreSQL advisory lock for the first version. A lock in one replica would allow races. The global lock bounds write concurrency and may limit throughput.
2. Keep immutable tracks separate from the current-position index. Rebuilding current state from the entire track would put work on every viewport query.
3. Enforce alert uniqueness with a partial index and serialize competing commands on the specific alert. An application-only precheck has a race across replicas.
4. Use JTS prepared geometry and a longitude frame that follows each ring across the dateline. A point-in-rectangle check misses holes and nonrectangular zones.
5. Use full bounded snapshots and a canvas map for this dataset. A persistent event stream would require a delivery and resume protocol shared by replicas.

For identical observed instants the existing current position wins. The track keeps distinct report IDs even at identical times. Internally the sub-microsecond remainder preserves event ordering. The API renders milliseconds in UTC.

## MariaDB portability

Replace the transaction advisory lock with a locked mutex row on InnoDB, or introduce a deterministic per-asset locking order. Use a generated nullable active key in a unique index, so resolved rows are excluded. Replace `ON CONFLICT` with an insert that handles only the duplicate-key outcome and preserve exact counts. Use UTC datetime plus fractional precision and carry over the sub-microsecond remainder. Audit triggers and per-alert row locks remain applicable. Re-test isolation and retries on the chosen MariaDB release.

## Building within an air gap

Mirror approved, checksum-verified base images, Maven artifacts and npm packages into internal registries using the site's media-transfer process. Build on a dedicated Linux amd64 worker with access to those mirrors and no public egress, using pinned lockfiles and reproducible build inputs. Generate the image archive and SHA-256 manifest in staging. Verify it on a clean isolated host and promote the same archive used in the test. Transfer patches with their dependency inventory and checksums through the same process.