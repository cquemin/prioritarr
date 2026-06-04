# Version & Build Tracking — Design

**Date:** 2026-06-03
**Status:** Approved (design)

## Problem

prioritarr has no per-build version provenance. `build.gradle.kts` hardcodes
`version = "0.3.0"`, the fat jar is named `prioritarr.jar` (version stripped),
the Dockerfile stamps no git SHA or labels, and images are only ever tagged
`:latest`. There is no `/version` endpoint and no version shown in the UI.

Consequence (hit on 2026-06-03): a `docker build` that produced buildx
attestations left the daemon's usable `:latest` pointing at a 5-week-old
image, so `docker compose up` silently redeployed stale code — and nothing
in `docker inspect`, the logs, or the UI revealed which commit was running.

## Goals

- One version string, derived from **git**, used identically across local
  dev builds, local Docker builds, and CI.
- That version (plus git SHA and build time) is **stamped into the jar**,
  **exposed at runtime** (`GET /version` + startup log + UI), and set as
  **OCI image labels** and **image tags**.
- A local build script and a CI workflow that both inject the version the
  same way and cannot fall into the stale-`:latest` trap.
- Works whether or not `.git` is present in the build context (it is **not**
  inside the Docker build — see Constraint).

## Non-goals

- Automated changelog / release-PR tooling (release-please, git-cliff) —
  deferred to a later phase.
- Image signing / SBOM / multi-arch (cosign etc.) — later phase.
- A Gradle release plugin (axion-release) — chosen against; plain
  `git describe` keeps zero new dependencies and identical CI/local logic.

## Key constraint

The Dockerfile build does `COPY prioritarr/ /src/prioritarr/` — it does **not**
copy `.git`. So git-derived versioning **cannot** run inside the image build.
The version is therefore derived **outside** the build (host or CI, where git
is available) and injected via `--build-arg` → Gradle property. A
`git describe` fallback inside Gradle covers plain local `./gradlew` runs
where `.git` *is* present.

## Design

### 1. Version derivation (Gradle, no plugin)

In `prioritarr/backend/build.gradle.kts`, replace the hardcoded version with a
resolved value:

- `version` = `-PappVersion` (if non-blank) **else** `git describe --tags
  --always --dirty` **else** `"0.0.0-dev"`.
- `gitSha` (extra property) = `-PgitSha` (if non-blank) **else**
  `git rev-parse --short HEAD` **else** `"unknown"`.

Git is shelled out via `ProcessBuilder` wrapped in `runCatching` so a missing
git binary or `.git` directory degrades to the fallbacks rather than failing
the build. There is a baseline tag (`v0.1.0`), so off-tag dev builds yield
`v0.1.0-<n>-g<sha>`.

`shadowJar` keeps producing `prioritarr.jar` (unversioned filename is fine —
the version lives in the stamped metadata, labels, and tags).

### 2. Build-info stamping into the jar

A Gradle task `generateBuildInfo` writes `build-info.properties` into a
generated resources directory wired into the `main` sourceSet:

```
version=<resolved version>
gitSha=<resolved short sha>
buildTime=<ISO-8601 UTC at task execution>
```

`processResources` depends on it. The task declares `version` + `gitSha` as
inputs so it re-runs when they change; `buildTime` is stamped at execution
(accepted minor cache churn).

A runtime `BuildInfo` object (backend `…/app/BuildInfo.kt`) loads
`/build-info.properties` from the classpath once, exposing `version`,
`gitSha`, `buildTime` (with safe `"unknown"` defaults if the file is absent,
e.g. in unit tests run without the generated resource).

### 3. Runtime surface

- **Endpoint:** `GET /version` registered as a top-level route **next to
  `/health` / `/ready` / `/openapi.json`** — i.e. **outside** the
  `authenticate("api_key") { route("/api/v2") { … } }` block. It is therefore
  unauthenticated and **not** under `/api/v2`, so it needs no api_key and is
  exempt from the openapi-lint "every /api/v2 op has api_key" check and the
  openapi drift check. Returns `{ "version", "gitSha", "buildTime" }`.
- **Startup log:** one INFO line at boot, e.g.
  `prioritarr <version> (<gitSha>, built <buildTime>)`.
- **UI:** a small version indicator added to the app shell (sidebar/header).
  It **fetches `GET /version` at runtime** (via the Traefik-prefixed path the
  UI already uses for same-origin calls) and renders `v<version> · <gitSha>`.
  Runtime fetch (not a Vite build-time constant) means the UI always reflects
  the running backend and the UI build stage needs no version build-arg.

### 4. Dockerfile

- **Gradle stage:** add `ARG APP_VERSION` and `ARG GIT_SHA`; invoke
  `gradle --no-daemon -PappVersion=$APP_VERSION -PgitSha=$GIT_SHA
  :backend:shadowJar`.
- **Final stage:** re-declare the ARGs (ARGs don't cross stages) and set:
  ```
  LABEL org.opencontainers.image.title="prioritarr"
  LABEL org.opencontainers.image.version="$APP_VERSION"
  LABEL org.opencontainers.image.revision="$GIT_SHA"
  LABEL org.opencontainers.image.source="https://github.com/cquemin/prioritarr"
  LABEL org.opencontainers.image.created="$BUILD_TIME"   # optional ARG
  ```
- **UI stage:** unchanged.

### 5. Local build script

`scripts/build-image.ps1` (Windows daily driver) and `scripts/build-image.sh`
(parity / reference for CI + non-Windows):

1. `APP_VERSION = git describe --tags --always --dirty`
2. `GIT_SHA = git rev-parse --short HEAD`
3. `docker buildx build --load --provenance=false --sbom=false
   --build-arg APP_VERSION=$APP_VERSION --build-arg GIT_SHA=$GIT_SHA
   -f prioritarr/Dockerfile
   -t ghcr.io/cquemin/prioritarr:$APP_VERSION
   -t ghcr.io/cquemin/prioritarr:latest .`

`--load --provenance=false --sbom=false` bakes in the 2026-06-03 fix so the
build always lands a normal single image in the daemon store (no stale
`:latest`). This script replaces the bare `docker build …` in the README /
deploy docs.

### 6. CI (`release.yml`)

Rename `.github/workflows/release.yml.disabled` → `release.yml`. Keep the
existing gate jobs (`frontend-build`, `openapi-lint`, `app-contract-tests`).
Add a `build-push` job that runs **after** the gates pass:

- Triggers: enable `push: branches: [main]` and tag pushes (`push: tags:
  ['v*']`) in addition to `workflow_dispatch`.
- `docker/metadata-action` derives tags + OCI labels:
  - on `main` → `:edge` and `:sha-<short>`
  - on `v*` tag → `:<semver>` and `:latest`
- Compute `APP_VERSION` (= `${{ steps.meta.outputs.version }}` or
  `git describe`) and `GIT_SHA` (= `${{ github.sha }}` short), pass as
  build-args.
- `docker/build-push-action` with `provenance: false`, `push: true`,
  registry `ghcr.io`, auth via `GITHUB_TOKEN` (`packages: write`).

Repo stays private for now; pushing to GHCR private is fine.

### 7. Testing

- **`GET /version`:** a Ktor `testApplication` test asserts 200 and a JSON
  body containing `version`, `gitSha`, `buildTime` keys.
- **`BuildInfo`:** a unit test asserts it loads without throwing and returns
  non-null fields (defaults to `"unknown"` when the generated resource is
  absent in the test classpath).
- **Version derivation:** verified by building with `-PappVersion=9.9.9-test`
  and asserting the jar's `build-info.properties` / `/version` reports it
  (covered by a build + curl in manual verification; not a unit test since it
  exercises Gradle logic).
- **CI workflow:** validated by one `workflow_dispatch` run after merge.

## Rollout

1. Implement on `feat/version-build-tracking`.
2. Merge to `main`; build + redeploy via the new `scripts/build-image.ps1`;
   confirm `GET /version` reports the expected `git describe` value and the
   image carries the OCI `revision` label.
3. Tag `v0.4.0` (this release adds versioning + the P1 fast-grab feature),
   so subsequent `git describe` yields clean SemVer.
4. Optionally trigger the CI workflow once via `workflow_dispatch` to confirm
   the GHCR push path.

## Risks

- **`buildTime` in the jar** slightly reduces Gradle build-cache reuse for
  `processResources`. Acceptable; the task is cheap.
- **No git tag reachable** would make `git describe --tags` fall back to a
  bare sha; mitigated by the existing `v0.1.0` tag and the planned `v0.4.0`.
- **CI GHCR auth** requires `permissions: packages: write` and the default
  `GITHUB_TOKEN`; if org settings block it, switch to a PAT secret.
