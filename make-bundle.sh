#!/bin/sh
set -eu

archive=position-console-v1.1.0-offline.tar.gz
rm -rf bundle
mkdir -p bundle/docs
for image in \
  position-console-api:1.0.0 \
  position-console-api:1.1.0 \
  position-console-web:1.0.0 \
  position-console-web:1.1.0 \
  position-console-edge:1.0.0 \
  postgres:16.8-alpine
do
  test "$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}')" = linux/amd64
done
cp compose.yaml deployment.env.example users.example.json version-*.env \
  README.md INSTALL.md openapi.yaml bundle/
cp docs/RUNBOOK.md docs/CR-1.md docs/VERIFICATION.md bundle/docs/
cp -r upgrade bundle/
docker save \
  position-console-api:1.0.0 \
  position-console-api:1.1.0 \
  position-console-web:1.0.0 \
  position-console-web:1.1.0 \
  position-console-edge:1.0.0 \
  postgres:16.8-alpine |
  gzip > bundle/images.tar.gz
tar -czf "$archive" bundle
test "$(wc -c < "$archive")" -lt 2000000000
sha256sum "$archive" > SHA256SUMS
