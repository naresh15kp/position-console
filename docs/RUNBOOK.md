# Runbook

Run from the bundle directory with the same Compose project name.

| Symptom | Checks | What to look for |
| --- | --- | --- |
| Slow ingest | `docker compose logs --since 5m api-a api-b`. Inspect authenticated `GET /api/v1/stats` and `docker compose stats --no-stream`. | Compare accepted and duplicate counts across a timed window. A large rejection count warrants inspection of the protected rejection table. In PostgreSQL inspect `pg_stat_activity` for asset-row lock waits and slow statements; unrelated assets should not block one another. |
| Console stops updating | Check the browser's network panel for the assets and alerts polls. Run `docker compose ps` and `docker compose logs --since 5m edge api-a api-b`. | Look for 401 (session expired), 503 (no ready replica) and 500 (application error). Match the response request ID to application logs. Successful refreshes replace stale snapshots. |
| Claim hangs | Compare edge and API logs by time. In the DB container inspect `pg_stat_activity` for `wait_event_type = 'Lock'` and use `pg_blocking_pids(pid)`. | Commands lock one alert row. Identify the blocking transaction before taking action. Re-read the alert and audit before retrying a timed-out command. |