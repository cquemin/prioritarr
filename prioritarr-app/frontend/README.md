# prioritarr UI

React 19 + TypeScript + Vite 5 + Tailwind v4 single-page operator console for the prioritarr v2 API. Bundled into `ghcr.io/cquemin/prioritarr-app:latest` — the Kotlin backend serves it at `/` alongside `/api/v2/*`.

## Dev

```bash
# Install deps (legacy-peer-deps because TypeScript 6 isn't a declared peer
# on some of the older TanStack tools).
npm ci --legacy-peer-deps

# Point the Vite proxy at a running backend. Defaults to http://localhost:8001
# (the shadow container from Spec B).
VITE_BACKEND_URL=http://localhost:8001 npm run dev
```

Open http://localhost:5173, enter the API key you set via `PRIORITARR_API_KEY` on the backend.

## Scripts

| Script | Does |
|---|---|
| `npm run dev` | Vite dev server with `/api`, `/health`, `/ready`, `/openapi.json` proxied to the backend |
| `npm run build` | Type-check (`tsc -b`) then `vite build` into `dist/` |
| `npm test` | Vitest unit tests (React Testing Library) |
| `npm run test:e2e` | Playwright happy-path E2E (expects a backend at `PLAYWRIGHT_BASE_URL`) |
| `npm run gen:api` | Regenerate `src/api/generated.ts` from the repo-root `openapi.json` |
| `npm run lint` | ESLint |

## Regenerating the API client

The TypeScript client is generated from the committed `openapi.json`. After the backend changes the spec, run:

```bash
npm run gen:api
```

`src/api/generated.ts` is gitignored — CI regenerates it in the `frontend-build` job.

## Stack summary

- **Routing:** hand-rolled view-switcher in `App.tsx` (left-rail icons → `useState`). TanStack Router is in the dep tree for a future refactor, but 4 views didn't justify pulling it in yet.
- **Server state:** TanStack Query v5 with 30 s `staleTime` matching the SSE heartbeat.
- **SSE:** `@microsoft/fetch-event-source` — browser `EventSource` can't set custom headers. `useEventStream` persists `Last-Event-ID` in localStorage and reconnects on network errors.
- **Styling:** Tailwind v4 via `@tailwindcss/vite` (no PostCSS step). Design tokens in `src/styles/globals.css` under `@theme`.
- **Bundle budget:** ≤ 400 KB gzipped (Spec D §12). Currently ~78 KB.

## Known limitations (deferred)

- No theme switcher (dark-only), no mobile layout, no i18n.
- No series detail slide-over yet (Task 7 in the plan scope but skipped for the MVP — add a `useSeries(id)` hook callsite under a Radix Dialog when needed).
- No infinite scroll on audit; a single `limit=100` page is enough at our scale.
