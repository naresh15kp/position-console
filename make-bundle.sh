#!/bin/sh
set -eu

archive=position-console-v1.0.0-offline.tar.gz
rm -rf bundle
mkdir bundle
for image in \
  position-console-api:1.0.0 \
  position-console-web:1.0.0 \
  position-console-edge:1.0.0 \
  postgres:16.8-alpine
do
  test "$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}')" = linux/amd64
done
cp compose.yaml deployment.env.example version-1.0.0.env INSTALL.md bundle/
mkdir bundle/docs
cp docs/RUNBOOK.md docs/VERIFICATION.md bundle/docs/
docker save \
  position-console-api:1.0.0 \
  position-console-web:1.0.0 \
  position-console-edge:1.0.0 \
  postgres:16.8-alpine |
  gzip > bundle/images.tar.gz
tar -czf "$archive" bundle
test "$(wc -c < "$archive")" -lt 2000000000
sha256sum "$archive" > SHA256SUMS
