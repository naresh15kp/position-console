# Offline installation and upgrade

The target is Linux x86_64 with Docker Engine and Compose. It needs no registry access, Java, Node, Python, Maven or image build. The API and console share <http://localhost:8080>.

After extraction, create `.env` beside `compose.yaml` with `DB_PASSWORD` and a shared random `TOKEN_SECRET` of at least 32 bytes. Put the deploy-time accounts array at `users.json`, readable by UID 10001. Keep both files outside source control and support bundles.

## Start v1.1.0 on an empty database

```sh
sha256sum -c SHA256SUMS
tar -xzf position-console-v1.1.0-offline.tar.gz
cd bundle
docker load -i images.tar.gz
docker compose --env-file .env --env-file version-1.1.0.env up -d --wait
```

V2 sees the empty report table and installs the final NOT NULL state. `docker compose ps` should show all five services healthy.

## Start v1.0.0 on an empty database

Use the first four commands above, then:

```sh
docker compose --env-file .env --env-file version-1.0.0.env up -d --wait
```

## Upgrade a running v1.0.0

Keep edge/database running. Stop if a command fails. V2 expands the schema when API A starts.

```sh
docker compose --env-file .env --env-file version-1.0.0.env exec -T edge route drain a
docker compose --env-file .env --env-file version-mixed.env up -d --wait --no-deps api-a
docker compose --env-file .env --env-file version-mixed.env exec -T edge route enable a
docker compose --env-file .env --env-file version-mixed.env exec -T edge route drain b
docker compose --env-file .env --env-file version-1.1.0.env up -d --wait --no-deps api-b
docker compose --env-file .env --env-file version-1.1.0.env exec -T edge route enable b
docker compose --env-file .env --env-file version-1.1.0.env up -d --wait --no-deps web
docker compose --env-file .env --env-file version-1.1.0.env exec -T db psql -U positions -d positions -f - < upgrade/backfill-source-type.sql
docker compose --env-file .env --env-file version-1.1.0.env exec -T db psql -U positions -d positions -f - < upgrade/verify-source-type.sql
docker compose --env-file .env --env-file version-1.1.0.env exec -T db psql -U positions -d positions -f - < upgrade/finalize-source-type.sql
```

The two verification counts must be zero before finalization. To stop/resume, cancel and rerun the backfill command. Roll back a binary by draining it, recreating that service with the preceding version env file, waiting for health and re-enabling it; keep the additive schema/trigger. Detailed locks and stage conditions are in `docs/CR-1.md`.

No container uses privileged mode, host networking, added capabilities, the Docker socket or a mount outside the bundle directory.
