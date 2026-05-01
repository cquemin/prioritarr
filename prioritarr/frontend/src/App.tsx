import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { ClipboardList, Settings, Star } from 'lucide-react'
import { SeriesPage } from './pages/SeriesPage'
import { AuditPage } from './pages/AuditPage'
import { SettingsPage } from './pages/SettingsPage'
import { useEventStream } from './hooks/useEventStream'
import { useSettings } from './hooks/queries'
import { navigate, useEnsureHash, useRoute, type Page } from './hooks/useHashRoute'
import { HealthBanner } from './components/HealthBanner'

// Downloads was its own tab; as of the unified-series-view change
// every download surface lives inside the Series drawer alongside the
// series it belongs to.
const NAV: Array<{ view: Page; icon: React.ReactNode; label: string }> = [
  { view: 'series', icon: <Star size={20} />, label: 'Series + Downloads' },
  { view: 'audit', icon: <ClipboardList size={20} />, label: 'Audit' },
  { view: 'settings', icon: <Settings size={20} />, label: 'Settings' },
]

export default function App() {
  const queryClient = useMemo(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000 } } }),
    [],
  )

  return (
    <QueryClientProvider client={queryClient}>
      <Root />
    </QueryClientProvider>
  )
}

function Root() {
  // The deployment is protected by Authelia at the Traefik edge (same
  // pattern as sonarr/radarr) so no in-app auth wall is needed. The
  // backend's optional X-Api-Key is left unset on the server side.
  return <Shell />
}

function Shell() {
  useEnsureHash()
  const route = useRoute()
  const view = route.page
  const queryClient = useQueryClient()
  const { status, recent } = useEventStream(queryClient)
  const settings = useSettings()
  const dryRun = (settings.data as any)?.dryRun === true

  return (
    <div className="h-screen flex flex-col bg-surface-0 text-text-primary">
      {dryRun && (
        <div className="bg-amber-900/40 border-b border-amber-700 px-4 py-1 text-center text-xs text-amber-300">
          PRIORITARR_DRY_RUN is enabled — actions are logged but not applied upstream.
        </div>
      )}
      <HealthBanner />

      <div className="flex-1 flex min-h-0">
        <nav className="w-12 sm:w-20 bg-surface-1 border-r border-surface-3 flex flex-col items-center py-2 sm:py-4 gap-1 shrink-0">
          {NAV.map((n) => (
            <button
              key={n.view}
              onClick={() => navigate({ page: n.view })}
              title={n.label}
              className={`p-2 sm:p-3 rounded hover:bg-surface-3 ${view === n.view ? 'bg-surface-3 text-accent' : ''}`}
            >
              {n.icon}
            </button>
          ))}
          <div className="mt-auto flex flex-col items-center gap-1 text-xs">
            <div
              className={`w-2 h-2 rounded-full ${
                status === 'open' ? 'bg-green-500' : status === 'connecting' ? 'bg-amber-500' : 'bg-red-500'
              }`}
              title={`SSE ${status}`}
            />
          </div>
        </nav>
        <main className="flex-1 overflow-auto">
          {view === 'series' && <SeriesPage />}
          {view === 'audit' && <AuditPage />}
          {view === 'settings' && <SettingsPage />}
        </main>
      </div>
      <EventTicker events={recent} />
    </div>
  )
}

function EventTicker({ events }: { events: Array<{ id: number | null; type: string }> }) {
  const [expanded, setExpanded] = useState(false)
  if (events.length === 0) return null
  const top = events.slice(0, expanded ? 20 : 5)
  return (
    <div className="bg-surface-1 border-t border-surface-3 text-xs font-mono cursor-pointer" onClick={() => setExpanded(!expanded)}>
      <div className="px-4 py-1 opacity-70">events · {events.length} · click to {expanded ? 'collapse' : 'expand'}</div>
      <div className="px-4 pb-1 space-x-3 overflow-hidden">
        {top.map((e, i) => (
          <span key={i} className="opacity-80">
            <span className="text-accent">{e.type}</span>
            {e.id != null && <span className="opacity-50"> #{e.id}</span>}
          </span>
        ))}
      </div>
    </div>
  )
}

