#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

IMAGE_NAME=raritan-akc-bridge
IMAGE_TAG=phase1

echo "=== building $IMAGE_NAME:$IMAGE_TAG ==="
docker build -f docker/Dockerfile.bridge -t "$IMAGE_NAME:$IMAGE_TAG" .

echo "=== saving + compressing ==="
docker save "$IMAGE_NAME:$IMAGE_TAG" | gzip > "$IMAGE_NAME-$IMAGE_TAG.tar.gz"
ls -lh "$IMAGE_NAME-$IMAGE_TAG.tar.gz"
echo
echo "Ship $IMAGE_NAME-$IMAGE_TAG.tar.gz + docker/OT-BRIDGE-README.md"
