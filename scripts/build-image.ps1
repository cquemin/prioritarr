# Builds + tags the prioritarr image with a git-derived version.
# Run from anywhere; resolves the repo root from the script location.
# Usage: .\scripts\build-image.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$appVersion = (git describe --tags --always --dirty).Trim()
$gitSha     = (git rev-parse --short HEAD).Trim()
$image      = "ghcr.io/cquemin/prioritarr"

Write-Host "Building $image  version=$appVersion  sha=$gitSha"
docker buildx build --load --provenance=false --sbom=false `
  --build-arg APP_VERSION=$appVersion --build-arg GIT_SHA=$gitSha `
  -f prioritarr/Dockerfile `
  -t "$($image):$appVersion" -t "$($image):latest" .
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
Write-Host "Built $image`:$appVersion and :latest"
