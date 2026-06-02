#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

IMAGE_NAME=raritan-akc-diagnose
IMAGE_TAG=latest
OUT_TAR=raritan-akc-diagnose.tar

echo "=== building $IMAGE_NAME:$IMAGE_TAG ==="
docker build \
    -f docker/Dockerfile.diagnose \
    -t "$IMAGE_NAME:$IMAGE_TAG" \
    .

echo
echo "=== saving to $OUT_TAR ==="
docker save "$IMAGE_NAME:$IMAGE_TAG" -o "$OUT_TAR"
ls -lh "$OUT_TAR"

echo
echo "=== compressing ==="
gzip -f "$OUT_TAR"
ls -lh "${OUT_TAR}.gz"

echo
echo "done. ship ${OUT_TAR}.gz + docker/OT-TEST-README.md to OT host."
