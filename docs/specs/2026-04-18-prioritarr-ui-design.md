# Spec D — Prioritarr React UI

**Author:** cquemin + Claude
**Status:** Draft
**Created:** 2026-04-18
**Dependencies:** Spec C (v2 backend) — must be shipped and merged; the UI reads `/api/v2/*` exclusively.

---

## 1. Goal

A single-page web UI served from the same container as the Kotlin backend, giving the operator:

- **A dashboard** showing every monitored series with its current priority, a filterable/sortable table at the scale we have (~100–200 series).
- **Per-series detail** view: current snapshot, cache metadata, action buttons (recompute, open audit subset), recent event history.
- **A downloads pane** showing every tracked download across qBit/SAB with inline pause/resume/boost/demote/untrack controls.
- **An audit log view** with server-side filters (series, action, since).
- **Live updates** via the SSE stream — the list re-sorts when a recompute fires, downloads flip status when an action completes, a small ticker shows the last 5 events.
- **A settings page** with redacted runtime config read-only + a banner when `PRIORITARR_DRY_RUN=true`.

The UI is a **pure client of the v2 API**. Zero business logic, zero state reshaping beyond normalisation for display. If the backend doesn't expose it, the UI doesn't invent it.

## 2. Why now

- Spec C is shipped, so the read + control surface is locked.
- The shadow soak (Spec B §Task 15) is still underway; the UI can exercise the Kotlin backend without disrupting the soak because it's read-only against a side instance.
- A UI forces real end-to-end auth + SSE wiring, which are the two pieces most likely to have nuanced bugs nobody sees from `curl` alone.

## 3. Out of scope (tracked elsewhere)

- Multi-user / login — single shared API key, same as the backend. Key is entered once and stored in `localStorage`.
- Theme switcher — dark theme only, matches Sonarr's Paper/Black palette.
- Internationalisation — English only.
- Mobile-first layout — works at tablet+ (1024px+). Phone support is a later problem.
- Bundling extras (charts, analytics) — this is an operator console, not a dashboard product.

## 4. Stack

| Concern | Choice | Rationale |
|---|---|---|
| Framework | **React 19** | Server Components aside, the new auto-batching + `use()` hook simplify data-fetching ergonomics. Same stack as autobrr, which we evaluated in the original UI decomposition discussion. |
| Language | **TypeScript 5.x** (strict) | `strict: true`, `noUncheckedIndexedAccess: true`. Catches the same class of bugs Kotlin's null-safety catches on the backend. |
| Build | **Vite 5** | Instant HMR; production output is static ESM. No Next.js — we don't need SSR, routes are all auth-gated, and shipping Node in the container is overkill. |
| Server state | **TanStack Query v5** | Cache + dedupe + SSE-driven invalidations. Industry standard. |
| Routing | **TanStack Router v1** | Type-safe routes, great codegen. Lighter than React Router v6 for our handful of routes. |
| Tables | **TanStack Table v8** | Only for the Series and Downloads tables, which need sort + filter + virtualisation (200-row series table borderline needs it; downloads will stay < 50 rows so optional there). |
| Styling | **Tailwind CSS v4** | Utility-first; v4 drops the PostCSS step and ships a native-speed Rust-based compiler. Design tokens in `tailwind.config.js`. |
| Component primitives | **Radix UI** (headless) + hand-rolled skins | Dropdowns, dialogs, tabs — Radix provides a11y-correct primitives; we style them with Tailwind. |
| Forms | **TanStack Form v0.x** | Lighter than RHF + Zod for the ~5 forms we need. |
| SSE client | Browser `EventSource` | Standard API, zero deps. Wraps in a tiny hook that handles reconnect + Last-Event-ID. |
| Icons | **Lucide** (tree-shakeable SVG) | Consistent stroke width, icon vocabulary Sonarr users recognise. |
| Test | **Vitest** + **Testing Library** | Vitest shares Vite's transform, 2x faster than Jest; RTL for user-facing behaviour tests. |
| E2E | **Playwright** (headless Chromium only in CI) | One happy-path test against a backend running in `PRIORITARR_TEST_MODE=true`. |
| Lint | **ESLint 9 flat config** + **Prettier** | Stock. No custom rules v1. |

## 5. Layout

Single-pane-app with a persistent left rail (≈ 80px wide) holding icons for each main view + a bottom section showing connection status + dry-run banner.

```
┌────────┬────────────────────────────────────────────────────────┐
│  P     │   Series (249)     [search] [priority filter]          │
│  ★     │   ─────────────────────────────────────────────────    │
│  ⬇     │   │ Priority │ Title         │ Cached │ Downloads │   │
│  📜    │   │    P1    │ Attack on...  │   5m   │     1     │   │
│  ⚙     │   │    P3    │ Bungo Stra... │  16m   │     0     │   │
│        │   │  ...                                               │
│ ●live  │                                                        │
│ DRYRUN │   [Event ticker — last 5 events, collapsed by default] │
└────────┴────────────────────────────────────────────────────────┘
```

Routes:

| Path | Icon | Content |
|---|---|---|
| `/` → `/series` | star | series list |
| `/series/:id` | | series detail pane in a slide-over |
| `/downloads` | down-arrow | managed downloads table |
| `/audit` | scroll | audit log list |
| `/settings` | gear | redacted settings + dry-run banner + API key management |
| `/login` | — | shown when no API key in localStorage |

Route state is URL-addressable — filter and sort choices go to the query string so users can share or bookmark views.

## 6. Data flow

### 6.1 Query structure

Every API call is a TanStack Query hook:

```ts
useSeriesList({ offset, limit, sort, sortDir })   // /api/v2/series
useSeries(id)                                      // /api/v2/series/{id}
useDownloads({ client, offset, limit, sort })      // /api/v2/downloads
useAudit({ seriesId?, action?, since?, offset, limit })
useSettings()                                      // /api/v2/settings (read-only)
useMappings()                                      // /api/v2/mappings
```

All queries use a single shared `QueryClient` with `defaultOptions.queries.staleTime = 30s` — matches the SSE heartbeat. A missing SSE event won't make the data stale for longer than 30s.

### 6.2 Mutations

```ts
useRecomputeSeries(id)                         // POST /api/v2/series/{id}/recompute
useDownloadAction(client, clientId, action)    // POST .../actions/{pause|resume|boost|demote}
useUntrackDownload(client, clientId)           // DELETE .../{client}/{clientId}
useRefreshMappings()                           // POST /api/v2/mappings/refresh
```

On success, each mutation publishes a pre-computed invalidation list (e.g. `[['series'], ['audit']]`) so the relevant query caches refresh immediately. The UI doesn't wait for the SSE echo — it uses the mutation response for optimistic update, and the SSE event just confirms.

### 6.3 SSE subscriber

A single `useEventStream()` hook opens one `EventSource` connection for the app's lifetime. It:

1. Reads the API key from `localStorage`, passes it as `X-Api-Key` via a header-augmenting `EventSource` polyfill (the native browser API can't set headers — use `event-source-polyfill` or a fetch-based wrapper).
2. Persists the last event id in `localStorage` and reconnects with `Last-Event-ID` on transient network errors.
3. Routes events to TanStack Query cache invalidations:
   - `priority-recomputed` → invalidate `['series']` + `['series', id]`
   - `download-action` / `download-untracked` → invalidate `['downloads']`
   - `mapping-refreshed` → invalidate `['mappings']`
   - `audit-appended` → invalidate `['audit']`
   - `heartbeat` → update a small `lastHeartbeatAt` atom, no query effect

### 6.4 Auth flow

1. On first load, check `localStorage.prioritarr_api_key`.
2. If missing → render the `/login` page with a single text input + "Connect" button. On submit, `GET /api/v2/settings` with the key; on 200, persist + redirect to `/`.
3. On any 401 from a later query → clear the stored key, redirect to `/login`, show a toast "API key rejected".
4. Logout button in settings clears the key and reloads.

There is no token refresh, cookie, or session — just a single shared secret in localStorage. Threat model: same-origin attacker with XSS already lost; this isn't the boundary to defend.

## 7. Non-trivial components

### 7.1 Series table

- Virtual scrolling kicks in > 100 rows (TanStack Virtual).
- Columns: priority (coloured chip), title (clickable → detail), cached-at (relative), managed-download count, actions (recompute button).
- Server-side sort via `?sort=priority|title|id&sort_dir=asc|desc`.
- Client-side filtering by title substring (debounced 150ms, not sent to the server — we already have the page).

### 7.2 Downloads table

- No virtualisation — < 50 rows typical.
- Columns: client badge, clientId (truncated), series title, priority chip, paused badge, row-level action buttons.
- Actions issue a mutation, optimistic update, SSE confirms.

### 7.3 Series detail

Slide-over modal (URL `?detail=<id>` modifier) that keeps the list behind it. Shows:
- Summary block (title, tvdbId, priority chip, cached at, expires at).
- "Recompute" button → calls the mutation, closes the modal on success.
- Last 10 audit entries for this series (calls `useAudit({ seriesId })` at `limit=10`).

### 7.4 Audit log

Simple infinite-scroll list (TanStack Query's `useInfiniteQuery`). Filter chips at the top for action type + series select + since-date picker. Each row is a single line: `ts · action · series · details` with expandable JSON payload.

### 7.5 Event ticker

A persistent footer strip showing the 5 most recent events from SSE. Collapsed by default, expands on click. Colour-codes by event type (info/warning/error).

## 8. Where does the UI live?

### 8.1 Repo layout

```
prioritarr-app/
├── backend/   ← Spec B + C
└── frontend/  ← NEW (Spec D)
    ├── index.html
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    ├── tailwind.config.js
    ├── src/
    │   ├── main.tsx
    │   ├── App.tsx
    │   ├── api/              # generated client from openapi.json
    │   ├── hooks/            # useSeriesList, useEventStream, etc.
    │   ├── components/
    │   ├── pages/
    │   └── test/
    └── e2e/
```

### 8.2 Serving in production

Two options, tradeoff on container count:

**Option A — bundled fat container (recommended).** The Dockerfile adds a third stage: `node:22-alpine` runs `pnpm build` producing `dist/`, the runtime stage copies `dist/` into `/app/static/`, and Ktor serves it via `staticResources`:
```kotlin
staticResources("/", "static")
```
All `/api/*`, `/health`, `/ready` routes win the router race; every other path falls through to the static asset (or index.html for client-side routing). Single container, same image name `ghcr.io/cquemin/prioritarr-app`.

**Option B — nginx sidecar.** Separate `ghcr.io/cquemin/prioritarr-ui` image, nginx serves static, reverse-proxies `/api/*` to the backend container. More moving parts, no benefit for our single-user single-host case.

Recommend A. Build-time overhead is ~15s.

### 8.3 Development

`vite dev` runs the frontend on `localhost:5173` with `/api` + `/health` + `/ready` proxied to `localhost:8001` (the shadow container from Spec B). CORS is already configured (Spec C §10) to allow `localhost:5173`.

## 9. OpenAPI → TypeScript client generation

The committed `openapi.json` is source of truth. A build step runs `openapi-typescript` (or `openapi-fetch` + codegen) to produce `src/api/generated.ts` with typed request/response pairs. This gives the UI end-to-end type safety from backend → frontend without hand-writing response types.

CI guard: a new step runs the codegen and fails if the generated output differs from what's committed — catches drift between `openapi.json` and the TypeScript that claims to speak it.

## 10. Testing

### 10.1 Unit / component

- Vitest + React Testing Library. Every non-trivial component has a `.test.tsx` that mocks the API client and asserts user-facing behaviour.
- Coverage target: statements 70%, branches 60%. Not 90% — tests of trivial layout glue hurt more than they help.

### 10.2 SSE

A dedicated test fixture runs a real `EventSource` against a minimal node-wrt fake that emits scripted events. Verifies:
- First event arrives and invalidates the right query.
- Reconnect sets `Last-Event-ID` correctly.
- Heartbeat events don't spam invalidations.

### 10.3 E2E

One Playwright scenario in CI, against the Kotlin backend with `PRIORITARR_TEST_MODE=true`:

1. Reset via `/api/v1/_testing/reset`.
2. Inject a series mapping + push a fake OnGrab payload.
3. Load the UI; enter the API key.
4. Assert the series appears in the list with P? priority.
5. Click Recompute; assert the priority updates without a page reload (SSE path).
6. Open the downloads page; assert the tracked download appears.

## 11. Risks

### 11.1 EventSource + auth headers

Browsers' native `EventSource` doesn't let you set request headers. Workarounds:
- **Query-string token** — backend already supports `?lastEventId=` so adding `?apiKey=` is trivial, but leaks the key into server access logs. Avoid.
- **Polyfill** (`event-source-polyfill`) — sends headers via XHR. Works everywhere except Safari's strict mode corner cases. Acceptable.
- **Fetch-based SSE library** (`@microsoft/fetch-event-source`) — cleaner; uses `fetch()` under the hood, so custom headers Just Work and so does explicit retry/backoff control. Recommend this one.

### 11.2 Tailwind v4 maturity

Tailwind v4 is new (2025). Ecosystem tooling (e.g. Prettier plugin) is catching up but occasionally lags. Fallback: v3.4 if we hit blocking bugs; the config migration is mechanical.

### 11.3 TanStack Router v1 lock-in

Router v1 is recent; API stability is high but the community footprint is smaller than React Router. If we later need to swap, the routes are flat enough (5 pages) that the migration is hours not days.

### 11.4 Bundle size

React 19 + TanStack suite + Tailwind runtime + Radix primitives lands around 180–220 KB gzipped. Acceptable for a self-hosted tool. If it creeps past 400 KB, revisit Radix (cherry-pick components) or replace TanStack Router with wouter.

### 11.5 DX during the shadow soak

The backend is at `localhost:8001`, not `:8000`, during shadow. `vite.config.ts` proxies can't tell, but developers need to `VITE_BACKEND_URL=http://localhost:8001 pnpm dev` the first time. Document in the frontend README.

## 12. Acceptance

- [ ] `pnpm build` produces a static `dist/` < 400 KB gzipped.
- [ ] Bundled into `ghcr.io/cquemin/prioritarr-app:latest` — same image name as the backend-only variant after Spec C merge; nothing new in the image tag vocabulary.
- [ ] Every route renders without console errors or warnings in dev.
- [ ] API key flow: enter → persisted → a 401 later redirects back to `/login`.
- [ ] SSE: trigger a recompute via the UI; the list row updates within 500ms without a reload.
- [ ] SSE reconnect: kill the backend briefly, bring it back; UI re-subscribes with `Last-Event-ID`, no duplicate events.
- [ ] Unit tests pass locally + in CI.
- [ ] One Playwright E2E scenario passes in CI.
- [ ] `openapi-typescript` drift check is green in CI.
- [ ] Lighthouse score ≥ 90 on Performance + Accessibility on the series page.

## 13. Effort estimate

- Vite + Tailwind + TanStack scaffold + CI: **1 day**
- API client codegen + query hooks: **1 day**
- SSE hook + reconnect + cache invalidation plumbing: **1 day**
- Layout + routing + left rail + dry-run banner: **0.5 day**
- Series list page + detail slide-over: **1.5 days**
- Downloads page + inline actions: **1 day**
- Audit page + infinite scroll + filters: **1 day**
- Settings page + API key management: **0.5 day**
- Unit + E2E tests: **1.5 days**
- Dockerfile stage + serving: **0.5 day**
- Polish / a11y / lighthouse: **1 day**

**Total: ~10 engineering-days.** Comparable to Spec C. Lower fundamental risk since the surface is UI-only, but more moving frontend pieces means more places for trivial bugs.

## 14. Open questions

1. **Do we need dark/light toggle v1?** Recommend: no. Dark-only ships. Add a toggle if someone complains.
2. **Should the series table default to server-side sort or client-side?** Server for correctness at scale; client for fewer round-trips. Recommend server-side — 200 rows isn't worth keeping in memory AND the backend already supports sort params.
3. **Where does the openapi-typescript codegen output go — generated and committed, or generated in CI?** Recommend commit it (same "generated-then-committed" pattern as openapi.json). Drift-check in CI; regen locally via `pnpm gen`.
4. **Any theming hooks users might want?** Not a v1 concern. Tailwind CSS custom properties leave room for a theme layer later without refactors.

---

*Next step if approved: write `docs/plans/2026-04-XX-ui-v1.md` with the usual per-task TDD breakdown.*
