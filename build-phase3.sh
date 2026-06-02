#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

IMAGE_NAME=raritan-akc-phase3
IMAGE_TAG=latest
OUT_TAR="${IMAGE_NAME}.tar"

echo "=== building $IMAGE_NAME:$IMAGE_TAG ==="
docker build -f docker/Dockerfile.phase3 -t "$IMAGE_NAME:$IMAGE_TAG" .

echo
echo "=== saving + compressing → ${OUT_TAR}.gz ==="
docker save "$IMAGE_NAME:$IMAGE_TAG" | gzip > "${OUT_TAR}.gz"
ls -lh "${OUT_TAR}.gz"

echo
echo "done."
echo "run mit:"
echo "  docker run --rm -p 6080:6080 -p 5900:5900 -p 8081:8081 \\"
echo "    -e RARITAN_IP=10.180.42.160 -e RARITAN_PASS=… \\"
echo "    $IMAGE_NAME:$IMAGE_TAG"
echo "dann http://<host>:6080/vnc.html öffnen."
