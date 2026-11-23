#!/bin/sh
set -eu
version=${1:?Usage: sh build-images.sh VERSION}
docker build --platform linux/amd64 -t "position-console-api:$version" .
docker build --platform linux/amd64 -t "position-console-web:$version" web
docker build --platform linux/amd64 -t position-console-edge:1.0.0 edge
docker pull --platform linux/amd64 postgres:16.8-alpine