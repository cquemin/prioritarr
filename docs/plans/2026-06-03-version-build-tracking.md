# Version & Build Tracking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive one version string from git, stamp it into the jar, expose it at runtime (`GET /version` + startup log + UI), and set it as Docker image labels/tags — working identically for local dev, local Docker, and CI.

**Architecture:** Version is resolved in Gradle from `-PappVersion`/`-PgitSha` (injected) with a `git describe` fallback for plain local runs. A Gradle task writes `build-info.properties` into the jar; a runtime `BuildInfo` object reads it. The Dockerfile takes `APP_VERSION`/`GIT_SHA` build-args (because `.git` is not in the build context) and sets OCI labels. A local build script and the CI workflow both compute the version from git and inject it the same way.

**Tech Stack:** Kotlin/Gradle (Kotlin DSL), Ktor, kotlinx.serialization, React/Vite/TS frontend, Docker buildx, GitHub Actions.

**Spec:** `docs/specs/2026-06-03-version-build-tracking-design.md`

---

## Conventions

- **Gradle root:** `D:\git\prioritarr\prioritarr` (module `:backend`). Tests (PowerShell):
  `& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test --tests "<glob>"`
- Full suite before each commit: `& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test`
- Commit from `D:\git\prioritarr`. Branch `feat/version-build-tracking` (already checked out).
- Test style mirrors `…/auth/ApiKeyAuthTest.kt` (`testApplication`) and the kotlin.test idioms used throughout.

## File map

| File | Responsibility | Task |
|---|---|---|
| `prioritarr/backend/build.gradle.kts` | version/gitSha resolution + `generateBuildInfo` task | 1 |
| `…/backend/app/BuildInfo.kt` (new) | runtime loader of `build-info.properties` | 1 |
| `…/backend/schemas/Meta.kt` (new) | `VersionResponse` DTO | 2 |
| `…/backend/app/VersionRoute.kt` (new) | `Route.versionRoute()` | 2 |
| `…/backend/app/Module.kt` | register `versionRoute()` | 2 |
| `…/backend/Main.kt` | startup version log line | 2 |
| `prioritarr/Dockerfile` | `ARG` build-args + OCI labels | 3 |
| `scripts/build-image.ps1`, `scripts/build-image.sh` (new) | local versioned build | 4 |
| `prioritarr/frontend/src/api/version.ts` (new) + `App.tsx` | UI version indicator | 5 |
| `.github/workflows/release.yml` (renamed) | CI build-args + tags/labels + triggers | 6 |
| `README.md` | deploy command + version section | 7 |

`…` = `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend`.

---

### Task 1: Version resolution + build-info stamping + `BuildInfo`

**Files:**
- Modify: `prioritarr/backend/build.gradle.kts`
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/BuildInfo.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/app/BuildInfoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `BuildInfoTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.app

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test fun fields_are_non_blank() {
        // Loaded from the generated build-info.properties on the test
        // classpath; falls back to "unknown" (still non-blank) if absent.
        assertTrue(BuildInfo.version.isNotBlank(), "version blank")
        assertTrue(BuildInfo.gitSha.isNotBlank(), "gitSha blank")
        assertTrue(BuildInfo.buildTime.isNotBlank(), "buildTime blank")
    }
}
```

- [ ] **Step 2: Run it, verify FAIL (`BuildInfo` unresolved)**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test --tests "*.BuildInfoTest"
```
Expected: FAIL — compile error, `BuildInfo` unresolved.

- [ ] **Step 3: Create `BuildInfo.kt`**

```kotlin
package org.yoshiz.app.prioritarr.backend.app

import java.util.Properties

/**
 * Build provenance baked into the jar at build time by the
 * `generateBuildInfo` Gradle task (writes build-info.properties into
 * resources). Falls back to "unknown" so unit tests / IDE runs without
 * the generated resource don't crash.
 */
object BuildInfo {
    private val props: Properties = Properties().apply {
        BuildInfo::class.java.classLoader
            .getResourceAsStream("build-info.properties")
            ?.use { load(it) }
    }
    val version: String = props.getProperty("version", "unknown")
    val gitSha: String = props.getProperty("gitSha", "unknown")
    val buildTime: String = props.getProperty("buildTime", "unknown")
}
```

- [ ] **Step 4: Wire version resolution + `generateBuildInfo` into `build.gradle.kts`**

Replace the line `version = "0.3.0"` with the resolution block below, and add the task + sourceSet wiring. Place the helper/resolution near the top (after `group = …`):

```kotlin
fun runGit(vararg args: String): String? = runCatching {
    val proc = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)   // prioritarr/ — git walks up to ../.git
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
}.getOrNull()

val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
    ?: runGit("describe", "--tags", "--always", "--dirty")
    ?: "0.0.0-dev"
val gitSha: String = (findProperty("gitSha") as String?)?.takeIf { it.isNotBlank() }
    ?: runGit("rev-parse", "--short", "HEAD")
    ?: "unknown"

version = appVersion
```

Then add (anywhere after the `application { }` block) the build-info generator and wire it into resources:

```kotlin
val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo")

val generateBuildInfo by tasks.registering {
    inputs.property("version", appVersion)
    inputs.property("gitSha", gitSha)
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("build-info.properties").asFile
        f.parentFile.mkdirs()
        val buildTime = java.time.OffsetDateTime
            .now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
        f.writeText("version=$appVersion\ngitSha=$gitSha\nbuildTime=$buildTime\n")
    }
}

sourceSets.named("main") { resources.srcDir(buildInfoDir) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }
```

> Note: `archiveVersion.set("")` on shadowJar stays — the jar remains `prioritarr.jar`; the version lives in `build-info.properties`, labels, and tags.

- [ ] **Step 5: Run it, verify PASS**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test --tests "*.BuildInfoTest"
```
Expected: PASS. (The generated `build-info.properties` is on the test classpath, so fields are real, non-blank values.)

- [ ] **Step 6: Sanity-check the generated file + property override**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:processResources -PappVersion=9.9.9-test -PgitSha=deadbee
Get-Content "D:\git\prioritarr\prioritarr\backend\build\generated\buildInfo\build-info.properties"
```
Expected: shows `version=9.9.9-test`, `gitSha=deadbee`, a `buildTime`. (Confirms the injection path Docker/CI will use.)

- [ ] **Step 7: Commit**

```powershell
git -C D:\git\prioritarr add prioritarr/backend/build.gradle.kts prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/BuildInfo.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/app/BuildInfoTest.kt
git -C D:\git\prioritarr commit -m "feat(build): derive version from git and stamp build-info into the jar"
```

---

### Task 2: `GET /version` endpoint + startup log

**Files:**
- Create: `…/backend/schemas/Meta.kt`
- Create: `…/backend/app/VersionRoute.kt`
- Modify: `…/backend/app/Module.kt` (routing block)
- Modify: `…/backend/Main.kt` (startup log)
- Test: `…/backend/app/VersionRouteTest.kt`

- [ ] **Step 1: Write the failing test**

Create `VersionRouteTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionRouteTest {
    @Test fun returns_200_with_version_fields() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { versionRoute() }
        }
        val resp = client.get("/version")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue("\"version\"" in body, "missing version: $body")
        assertTrue("\"gitSha\"" in body, "missing gitSha: $body")
        assertTrue("\"buildTime\"" in body, "missing buildTime: $body")
    }
}
```

- [ ] **Step 2: Run it, verify FAIL (`versionRoute` unresolved)**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test --tests "*.VersionRouteTest"
```
Expected: FAIL — `versionRoute` / `VersionResponse` unresolved.

- [ ] **Step 3: Create the DTO `schemas/Meta.kt`**

```kotlin
package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable

@Serializable
data class VersionResponse(
    val version: String,
    val gitSha: String,
    val buildTime: String,
)
```

- [ ] **Step 4: Create `app/VersionRoute.kt`**

```kotlin
package org.yoshiz.app.prioritarr.backend.app

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.yoshiz.app.prioritarr.backend.schemas.VersionResponse

/**
 * Top-level, unauthenticated build-provenance endpoint — sits beside
 * /health and /openapi.json (NOT under /api/v2), so it carries no
 * api_key and is outside the openapi contract.
 */
fun Route.versionRoute() {
    get("/version") {
        call.respond(
            VersionResponse(
                version = BuildInfo.version,
                gitSha = BuildInfo.gitSha,
                buildTime = BuildInfo.buildTime,
            ),
        )
    }
}
```

- [ ] **Step 5: Run it, verify PASS**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test --tests "*.VersionRouteTest"
```
Expected: PASS.

- [ ] **Step 6: Register the route in `Module.kt`**

In `Module.kt`, inside `routing { … }`, immediately after the `get("/openapi.json") { … }` block (ends ~line 164), add:

```kotlin
        versionRoute()
```

(`versionRoute` is in the same package `org.yoshiz.app.prioritarr.backend.app` as `prioritarrModule`, so no import is needed. If the module function is in a different package, add `import org.yoshiz.app.prioritarr.backend.app.versionRoute`.)

- [ ] **Step 7: Add the startup log line in `Main.kt`**

In `Main.kt`, inside `fun main()` (starts ~line 161), after `val settings = run { … }` resolves (~line 168), add:

```kotlin
    logger.info(
        "prioritarr {} ({}, built {})",
        org.yoshiz.app.prioritarr.backend.app.BuildInfo.version,
        org.yoshiz.app.prioritarr.backend.app.BuildInfo.gitSha,
        org.yoshiz.app.prioritarr.backend.app.BuildInfo.buildTime,
    )
```

- [ ] **Step 8: Full suite (compile + no regressions)**

```powershell
& "D:\git\prioritarr\prioritarr\gradlew.bat" :backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```powershell
git -C D:\git\prioritarr add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/Meta.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/VersionRoute.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/app/VersionRouteTest.kt
git -C D:\git\prioritarr commit -m "feat(api): add GET /version endpoint + startup version log"
```

---

### Task 3: Dockerfile build-args + OCI labels

**Files:**
- Modify: `prioritarr/Dockerfile`

- [ ] **Step 1: Pass build-args into the Gradle stage**

In `prioritarr/Dockerfile`, in **Stage 2 (`FROM gradle:8-jdk21 AS build`)**, before the `RUN gradle … :backend:shadowJar` line, add the ARGs, and change the gradle invocation to pass them:

Replace:
```dockerfile
RUN gradle --no-daemon :backend:shadowJar
```
with:
```dockerfile
ARG APP_VERSION=0.0.0-dev
ARG GIT_SHA=unknown
RUN gradle --no-daemon -PappVersion=$APP_VERSION -PgitSha=$GIT_SHA :backend:shadowJar
```

- [ ] **Step 2: Set OCI labels in the runtime stage**

In **Stage 3 (`FROM eclipse-temurin:21-jre-alpine`)**, after the `WORKDIR /app` line, add (ARGs must be re-declared per stage):

```dockerfile
ARG APP_VERSION=0.0.0-dev
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="prioritarr" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$GIT_SHA" \
      org.opencontainers.image.source="https://github.com/cquemin/prioritarr"
```

- [ ] **Step 3: Verify the build accepts the args + labels land**

```powershell
cd D:\git\prioritarr
docker buildx build --load --provenance=false --sbom=false `
  --build-arg APP_VERSION=9.9.9-test --build-arg GIT_SHA=deadbee `
  -f prioritarr/Dockerfile -t prioritarr:labeltest .
docker inspect prioritarr:labeltest --format '{{json .Config.Labels}}'
docker run --rm prioritarr:labeltest sh -c "java -jar /app/prioritarr.jar & sleep 6; wget -qO- http://localhost:8000/version; kill %1" 2>$null
```
Expected: labels show `version=9.9.9-test`, `revision=deadbee`; `/version` reports `9.9.9-test` / `deadbee`. Then clean up: `docker rmi prioritarr:labeltest`.

> If the inline `docker run` smoke is awkward, it's enough to confirm the labels via `docker inspect`; `/version` is already covered by the Task 2 unit test and the rollout step.

- [ ] **Step 4: Commit**

```powershell
git -C D:\git\prioritarr add prioritarr/Dockerfile
git -C D:\git\prioritarr commit -m "build(docker): inject APP_VERSION/GIT_SHA, set OCI image labels"
```

---

### Task 4: Local build scripts

**Files:**
- Create: `scripts/build-image.ps1`
- Create: `scripts/build-image.sh`

- [ ] **Step 1: Create `scripts/build-image.ps1`**

```powershell
# Builds + tags the prioritarr image with a git-derived version.
# Run from the repo root (D:\git\prioritarr). Usage: .\scripts\build-image.ps1
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
```

- [ ] **Step 2: Create `scripts/build-image.sh`** (parity / CI reference / non-Windows)

```bash
#!/usr/bin/env bash
# Builds + tags the prioritarr image with a git-derived version.
# Run from the repo root. Usage: ./scripts/build-image.sh
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
```

- [ ] **Step 3: Verify the PS script runs end-to-end**

```powershell
cd D:\git\prioritarr; .\scripts\build-image.ps1
docker inspect ghcr.io/cquemin/prioritarr:latest --format '{{index .Config.Labels "org.opencontainers.image.version"}}  {{index .Config.Labels "org.opencontainers.image.revision"}}'
```
Expected: prints the `git describe` version + short sha; both image tags exist.

- [ ] **Step 4: Commit**

```powershell
git -C D:\git\prioritarr add scripts/build-image.ps1 scripts/build-image.sh
git -C D:\git\prioritarr commit -m "build: add versioned local image build scripts (buildx --load)"
```

---

### Task 5: UI version indicator

**Files:**
- Create: `prioritarr/frontend/src/api/version.ts`
- Modify: `prioritarr/frontend/src/App.tsx` (the `Shell` sidebar nav)

- [ ] **Step 1: Create the fetch helper `version.ts`**

`/version` is top-level (not in the generated openapi client), so fetch it raw using the same `API_BASE` the typed client uses:

```ts
import { useEffect, useState } from 'react'
import { API_BASE } from './client'

export type VersionInfo = { version: string; gitSha: string; buildTime: string }

export function useVersion(): VersionInfo | null {
  const [v, setV] = useState<VersionInfo | null>(null)
  useEffect(() => {
    let alive = true
    fetch(`${API_BASE}/version`, { headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => { if (alive && data) setV(data as VersionInfo) })
      .catch(() => { /* non-fatal: footer just stays hidden */ })
    return () => { alive = false }
  }, [])
  return v
}
```

- [ ] **Step 2: Render it at the bottom of the sidebar in `App.tsx`**

Add the import at the top of `App.tsx`:
```ts
import { useVersion } from './api/version'
```

In the `Shell` component, the sidebar is `<nav className="w-12 sm:w-20 …">`. Add a `VersionBadge` component and render it as the last child of that `<nav>` (after the nav buttons, so it sits at the bottom). Define the component near the bottom of the file:

```tsx
function VersionBadge() {
  const v = useVersion()
  if (!v) return null
  return (
    <div
      className="mt-auto pt-2 text-[9px] leading-tight text-text-muted text-center break-all px-1"
      title={`${v.version} · ${v.gitSha} · built ${v.buildTime}`}
    >
      <span className="hidden sm:inline">{v.version}</span>
      <span className="sm:hidden">ⓥ</span>
    </div>
  )
}
```

Then inside the `<nav>…</nav>` add `<VersionBadge />` as the final element. The nav is a `flex flex-col` column, so the badge's `mt-auto` pushes it to the bottom. (If `text-text-muted` is not a defined token in this project's Tailwind theme, use `text-text-secondary` or an existing muted token — check `index.css`/theme; `text-text-primary` is known to exist.)

- [ ] **Step 3: Type-check the frontend**

```powershell
cd D:\git\prioritarr\prioritarr\frontend; npx tsc -b --noEmit
```
Expected: exit 0 (no type errors).

- [ ] **Step 4: Commit**

```powershell
git -C D:\git\prioritarr add prioritarr/frontend/src/api/version.ts prioritarr/frontend/src/App.tsx
git -C D:\git\prioritarr commit -m "feat(ui): show running version in the sidebar (fetches /version)"
```

---

### Task 6: CI workflow — build-args, tags/labels, enable triggers

**Files:**
- Rename + modify: `.github/workflows/release.yml.disabled` → `.github/workflows/release.yml`

- [ ] **Step 1: Read the existing workflow**

Read `.github/workflows/release.yml.disabled`. It has jobs `frontend-build`, `openapi-lint`, `app-contract-tests`, and a Docker build/push job. Keep the test/lint/contract jobs as-is (they're gates).

- [ ] **Step 2: Rename the file**

```powershell
git -C D:\git\prioritarr mv .github/workflows/release.yml.disabled .github/workflows/release.yml
```

- [ ] **Step 3: Enable triggers**

Replace the commented `on:` block at the top with:

```yaml
on:
  push:
    branches: [main]
    tags: ['v*']
    paths:
      - 'prioritarr/**'
      - 'contract-tests/**'
      - 'openapi.json'
      - '.github/workflows/**'
  workflow_dispatch:
```

- [ ] **Step 4: Replace the Docker build/push job with a version-aware one**

Ensure the build-push job depends on the gate jobs and computes tags/labels + build-args. Replace the existing build/push job body with:

```yaml
  build-push:
    needs: [frontend-build, openapi-lint, app-contract-tests]
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0          # full history so git describe works

      - name: Compute version
        id: ver
        run: |
          echo "app_version=$(git describe --tags --always --dirty)" >> "$GITHUB_OUTPUT"
          echo "git_sha=$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Docker metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository }}
          tags: |
            type=ref,event=branch,suffix=,prefix=,value=edge,enable=${{ github.ref == 'refs/heads/main' }}
            type=raw,value=edge,enable=${{ github.ref == 'refs/heads/main' }}
            type=sha,format=short
            type=semver,pattern={{version}}
            type=raw,value=latest,enable=${{ startsWith(github.ref, 'refs/tags/v') }}
          labels: |
            org.opencontainers.image.title=prioritarr
            org.opencontainers.image.version=${{ steps.ver.outputs.app_version }}
            org.opencontainers.image.revision=${{ steps.ver.outputs.git_sha }}

      - name: Set up Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          file: prioritarr/Dockerfile
          push: true
          provenance: false
          sbom: false
          build-args: |
            APP_VERSION=${{ steps.ver.outputs.app_version }}
            GIT_SHA=${{ steps.ver.outputs.git_sha }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
```

> If the existing file already has a build job named differently, replace its steps with the above (keeping a single build-push job). The `type=ref/raw value=edge` lines are redundant on purpose across metadata-action versions; if `actionlint`/the run complains about a tag line, drop the `type=ref,...,value=edge` line and keep `type=raw,value=edge,...`.

- [ ] **Step 5: Lint the workflow YAML locally (best-effort)**

```powershell
cd D:\git\prioritarr
docker run --rm -v "${PWD}:/repo" rhysd/actionlint:latest -color /repo/.github/workflows/release.yml
```
Expected: no errors. (If `actionlint` image is unavailable, skip — it's validated by the real run in Step 7.)

- [ ] **Step 6: Commit**

```powershell
git -C D:\git\prioritarr add .github/workflows/release.yml
git -C D:\git\prioritarr commit -m "ci: enable release workflow with version build-args, tags + OCI labels"
```

- [ ] **Step 7: (Post-merge, manual) validate one run**

After merging to `main`, in GitHub → Actions → "Build and Push Docker Image" → Run workflow (`workflow_dispatch`). Confirm green and that `ghcr.io/cquemin/prioritarr:edge` + `:sha-<short>` were pushed.

---

### Task 7: Docs — deploy command + version section

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the local build command with the script**

In `README.md`, find the deploy section that shows `docker build … -t ghcr.io/cquemin/prioritarr:latest .` (around the `image: ghcr.io/cquemin/prioritarr:latest` reference / setup section). Replace the bare `docker build` instruction with:

```markdown
Build + tag the image with a git-derived version (uses buildx `--load` so
`:latest` always updates in the local daemon):

    ./scripts/build-image.ps1      # Windows
    ./scripts/build-image.sh       # macOS/Linux

Then redeploy the container:

    docker compose -f media-stack-v3.yml up -d --no-deps --force-recreate prioritarr

Check what's running:

    curl -s http://localhost:8000/version    # {version, gitSha, buildTime}
    docker inspect prioritarr --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'
```

- [ ] **Step 2: Add a short "Versioning" note**

Add a brief subsection (near the build/deploy docs) stating: version is `git describe --tags --always --dirty`, injected into the jar (`/version`), the image labels (`org.opencontainers.image.version/revision`), and the image tag; CI tags `:edge`/`:sha-…` on main and `:<semver>`/`:latest` on a `v*` tag.

- [ ] **Step 3: Commit**

```powershell
git -C D:\git\prioritarr add README.md
git -C D:\git\prioritarr commit -m "docs: document versioned build/deploy + /version"
```

---

## Rollout (after all tasks, done by the controller — not a subagent)

1. Merge `feat/version-build-tracking` → `main`; run full `:backend:test` on the merge.
2. `./scripts/build-image.ps1` then `docker compose -f media-stack-v3.yml up -d --no-deps --force-recreate prioritarr`.
3. Verify: `curl http://localhost:8000/version` shows the `git describe` value; `docker inspect prioritarr` shows the `revision` label; UI sidebar shows the version.
4. Tag the release: `git -C D:\git\prioritarr tag v0.4.0 && git push origin v0.4.0` (this release adds versioning + P1 fast-grab). Subsequent `git describe` → clean SemVer.
5. Optional: trigger the CI workflow once (`workflow_dispatch`) to confirm the GHCR push path.

---

## Self-review notes

- **Spec coverage:** §1 version derivation → Task 1; §2 stamping/BuildInfo → Task 1; §3 runtime surface (endpoint+log+UI) → Tasks 2 & 5; §4 Dockerfile → Task 3; §5 local script → Task 4; §6 CI → Task 6; §7 testing → Tasks 1,2 (+rollout); docs → Task 7. All covered.
- **Type/name consistency:** `BuildInfo.{version,gitSha,buildTime}` (Task 1) used identically in `VersionRoute` (Task 2) and `Main.kt` log (Task 2); `VersionResponse` fields match `VersionInfo` TS type (Task 5) and the test assertions (Task 2); build-args `APP_VERSION`/`GIT_SHA` consistent across Dockerfile (Task 3), scripts (Task 4), CI (Task 6); Gradle props `appVersion`/`gitSha` consistent between `build.gradle.kts` (Task 1) and every injector.
- **Assumptions flagged inline:** the exact existing build-job shape in `release.yml.disabled` (Task 6 says read+replace), and the muted Tailwind token name (Task 5 says verify against theme).
