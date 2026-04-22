import { useLibrarySync, useMappings, useRefreshMappings, useSettings } from '../hooks/queries'
import { useAuth } from '../hooks/useAuth'

export function SettingsPage() {
  const { logout } = useAuth()
  const settings = useSettings()
  const mappings = useMappings()
  const refresh = useRefreshMappings()
  const librarySync = useLibrarySync()

  if (settings.isLoading) return <p className="p-6 opacity-70">Loading…</p>
  const s = settings.data as any
  const m = mappings.data as any

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
        {s && Object.entries(s).map(([k, v]) => (
          <div key={k} className="flex justify-between border-b border-surface-3 last:border-0 py-1">
            <span className="opacity-60 font-mono">{k}</span>
            <span className="font-mono break-all">{String(v ?? '—')}</span>
          </div>
        ))}
      </div>

      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold">Plex ↔ Sonarr mappings</h2>
          <button
            onClick={() => refresh.mutate()}
            disabled={refresh.isPending}
            className="px-3 py-1 rounded text-sm bg-accent hover:opacity-90 disabled:opacity-50"
          >
            {refresh.isPending ? 'Refreshing…' : 'Refresh mappings'}
          </button>
        </div>
        {m && (
          <div className="text-xs opacity-70">
            {Object.keys(m.plexKeyToSeriesId ?? {}).length} mappings · tautulli{' '}
            {m.tautulliAvailable ? 'available' : 'unreachable'}
            {m.lastRefreshStats && (
              <> · last refresh: cached={m.lastRefreshStats.cached}, tvdb={m.lastRefreshStats.tvdb}, title={m.lastRefreshStats.title}, unmatched={m.lastRefreshStats.unmatched}</>
            )}
          </div>
        )}
      </div>

      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="font-semibold">Cross-source watch sync (Plex ⇆ Trakt)</h2>
            <p className="text-xs opacity-70 mt-0.5">
              Mirrors watch state both ways for every series in the library —
              episodes Plex knows about get scrobbled to Trakt, and vice versa.
              Sequential, ~one HTTP call per series. Safe to re-run.
            </p>
          </div>
          <button
            onClick={() => {
              if (!confirm('Sync watch state for ALL series? This may take several minutes.')) return
              librarySync.mutate()
            }}
            disabled={librarySync.isPending}
            className="px-3 py-1 rounded text-sm bg-accent hover:opacity-90 disabled:opacity-50 whitespace-nowrap"
          >
            {librarySync.isPending ? 'Syncing library…' : 'Sync entire library'}
          </button>
        </div>
        {librarySync.error && (
          <div className="text-xs text-red-400 font-mono">
            {String((librarySync.error as Error).message ?? librarySync.error)}
          </div>
        )}
        {librarySync.data && (
          <div className="text-xs opacity-80">
            Done · {librarySync.data.totalSeries} series ·
            <span className="text-green-400"> Plex+{librarySync.data.plexAddedTotal}</span>,
            <span className="text-green-400"> Trakt+{librarySync.data.traktAddedTotal}</span>
            {librarySync.data.dryRun && <span className="ml-2 text-amber-400">(dry-run)</span>}
            {(() => {
              const skipped = librarySync.data.perSeries.filter((p) => p.skippedReason).length
              const errored = librarySync.data.perSeries.filter((p) => p.errors.length > 0).length
              if (skipped === 0 && errored === 0) return null
              return (
                <span className="ml-2 text-amber-400">
                  · {skipped} skipped, {errored} with errors
                </span>
              )
            })()}
          </div>
        )}
      </div>

      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 flex justify-between items-center">
        <div className="text-sm opacity-70">API key stored in localStorage.</div>
        <button
          onClick={logout}
          className="px-4 py-1 rounded bg-red-900/60 hover:bg-red-700 text-sm"
        >
          Log out
        </button>
      </div>
    </div>
  )
}
