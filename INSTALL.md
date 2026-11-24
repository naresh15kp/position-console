# Offline installation

The target is Linux x86_64 with Docker Engine and the Compose plugin. It needs no Java, Node, Python, Maven, registry access or image build. The API and console share <http://localhost:8080>.

Before startup, create `.env` beside `compose.yaml` with `DB_PASSWORD` and a shared random `TOKEN_SECRET` of at least 32 bytes. Put the supplied or equivalent account array at `users.json` and make it readable by UID 10001. Keep both files out of source control and support bundles.

## Start v1.0.0 from the release

From the directory containing the downloaded release files:

```sh
sha256sum -c SHA256SUMS
tar -xzf position-console-v1.0.0-offline.tar.gz
cd bundle
docker load -i images.tar.gz
docker compose --env-file .env --env-file version-1.0.0.env up -d --wait
```

Provision `.env` and `users.json` after extraction and before the final command. `docker compose ps` should show PostgreSQL, both API replicas, the web console and edge as healthy. No container uses privileged mode, host networking, added capabilities, the Docker socket or a mount outside the bundle directory.

On the connected build host, build the exact release checkout with `sh build-images.sh 1.0.0`, then run `sh make-bundle.sh`. The target must use the resulting archive; do not rebuild there.
