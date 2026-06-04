#!/usr/bin/env bash
# Builds + tags the prioritarr image with a git-derived version.
# Run from anywhere; resolves the repo root from the script location.
# Usage: ./scripts/build-image.sh
set -euo pipefail
cd "$(dirname "$0")/.."

APP_VERSION="$(git describe --tags --always --dirty)"
GIT_SHA="$(git rev-parse --short HEAD)"
IMAGE="ghcr.io/cquemin/prioritarr"

echo "Building $IMAGE  version=$APP_VERSION  sha=$GIT_SHA"
docker buildx build --load --provenance=false --sbom=false \
  --build-arg APP_VERSION="$APP_VERSION" --build-arg GIT_SHA="$GIT_SHA" \
  -f prioritarr/Dockerfile \
  -t "$IMAGE:$APP_VERSION" -t "$IMAGE:latest" .
echo "Built $IMAGE:$APP_VERSION and :latest"
