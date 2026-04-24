import { useEffect, useSyncExternalStore } from 'react'

/**
 * Tiny hash-based router. Hash routing is deliberate: the backend's
 * SPA fallback serves index.html for any /prioritarr/* path, and
 * hash fragments never reach the server, so this works without any
 * route-table changes on either side.
 *
 * Routes understood:
 *   #/                -> { page: 'series' }
 *   #/series          -> { page: 'series' }
 *   #/series/123      -> { page: 'series', seriesId: 123 }
 *   #/audit           -> { page: 'audit' }
 *   #/settings        -> { page: 'settings' }
 *
 * The parse function is intentionally unforgiving: unknown paths fall
 * back to the series page so a bad URL can't wedge the UI.
 */

export type Page = 'series' | 'audit' | 'settings'

export interface Route {
  page: Page
  seriesId: number | null
}

function parseHash(hash: string): Route {
  const stripped = hash.startsWith('#') ? hash.slice(1) : hash
  const path = stripped.startsWith('/') ? stripped.slice(1) : stripped
  const [head, ...rest] = path.split('/')
  switch (head) {
    case '':
    case 'series': {
      const idStr = rest[0]
      const id = idStr ? Number(idStr) : null
      return { page: 'series', seriesId: Number.isFinite(id) ? id : null }
    }
    case 'audit':
      return { page: 'audit', seriesId: null }
    case 'settings':
      return { page: 'settings', seriesId: null }
    default:
      return { page: 'series', seriesId: null }
  }
}

/** Subscribe to window.location.hash changes. */
function subscribeHash(callback: () => void) {
  window.addEventListener('hashchange', callback)
  return () => window.removeEventListener('hashchange', callback)
}
function getHash() { return window.location.hash }
// Server snapshot (SSR hypothetical — we never render on server, but
// useSyncExternalStore demands one).
const serverHash = () => ''

export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribeHash, getHash, serverHash)
  return parseHash(hash)
}

/**
 * Imperatively push a new route. Use for programmatic nav after an
 * action (e.g. clicking a row, closing a drawer). Browser Back
 * undoes it naturally.
 */
export function navigate(route: Partial<Route> & { page: Page }) {
  const path = route.seriesId ? `/${route.page}/${route.seriesId}` : `/${route.page}`
  window.location.hash = path
}

/**
 * Ensure the URL has *some* valid hash on first mount so Back always
 * has a landing target and refresh stays on the same page.
 */
export function useEnsureHash() {
  useEffect(() => {
    if (!window.location.hash) {
      window.location.hash = '/series'
    }
  }, [])
}
