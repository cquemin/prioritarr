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

/**
 * Settings sub-sections. The Settings page renders a sidebar that
 * navigates between these. URL shape: `#/settings/<section>`. Default
 * (just `#/settings`) lands on `general`.
 */
export type SettingsSection =
  | 'general'
  | 'connections'
  | 'bandwidth'
  | 'priority-rules'
  | 'jobs'
  | 'orphans'
  | 'mappings'
  | 'webhooks'

export interface Route {
  page: Page
  seriesId: number | null
  settingsSection: SettingsSection | null
  /** When inside `jobs`, the focused job id (for the detail page). */
  jobId: string | null
}

const SETTINGS_SECTIONS: ReadonlyArray<SettingsSection> = [
  'general', 'connections', 'bandwidth', 'priority-rules', 'jobs', 'orphans', 'mappings', 'webhooks',
]

function parseHash(hash: string): Route {
  const stripped = hash.startsWith('#') ? hash.slice(1) : hash
  const path = stripped.startsWith('/') ? stripped.slice(1) : stripped
  const [head, ...rest] = path.split('/')
  switch (head) {
    case '':
    case 'series': {
      const idStr = rest[0]
      const id = idStr ? Number(idStr) : null
      return { page: 'series', seriesId: Number.isFinite(id) ? id : null, settingsSection: null, jobId: null }
    }
    case 'audit':
      return { page: 'audit', seriesId: null, settingsSection: null, jobId: null }
    case 'settings': {
      const sectionStr = rest[0] as SettingsSection | undefined
      const section: SettingsSection = sectionStr && SETTINGS_SECTIONS.includes(sectionStr) ? sectionStr : 'general'
      const jobId = section === 'jobs' && rest[1] ? rest[1] : null
      return { page: 'settings', seriesId: null, settingsSection: section, jobId }
    }
    default:
      return { page: 'series', seriesId: null, settingsSection: null, jobId: null }
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
  const parts: string[] = [route.page]
  if (route.page === 'series' && route.seriesId) {
    parts.push(String(route.seriesId))
  } else if (route.page === 'settings' && route.settingsSection) {
    parts.push(route.settingsSection)
    if (route.settingsSection === 'jobs' && route.jobId) parts.push(route.jobId)
  }
  window.location.hash = '/' + parts.join('/')
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
