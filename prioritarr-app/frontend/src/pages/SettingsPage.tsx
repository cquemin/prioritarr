import { useEffect, useMemo, useState } from 'react'

import {
  useLibrarySync,
  useMappings,
  usePriorityPreview,
  useRefreshMappings,
  useResetSettings,
  useResetThresholds,
  useSaveSettings,
  useSaveThresholds,
  useSeriesList,
  useSettings,
  useThresholds,
  type EditableSettings,
  type PriorityPreviewEntry,
  type PriorityThresholds,
} from '../hooks/queries'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'
import { useAuth } from '../hooks/useAuth'

// Single source of truth for the editable threshold fields so the form
// and the sandbox know the same set of knobs, labels, and step sizes.
const THRESHOLD_FIELDS: Array<{
  key: keyof PriorityThresholds
  label: string
  hint: string
  step: number
  min: number
  max?: number
}> = [
  { key: 'p1WatchPctMin', label: 'P1 · watch% min', hint: '0–1. watchPct ≥ this OR unwatched ≤ p3_unwatched_max', step: 0.01, min: 0, max: 1 },
  { key: 'p1DaysSinceWatchMax', label: 'P1 · last watch max (days)', hint: 'Inclusive upper bound for last_watch', step: 1, min: 0 },
  { key: 'p1DaysSinceReleaseMax', label: 'P1 · last release max (days)', hint: 'P1 requires fresh release within this many days', step: 1, min: 0 },
  { key: 'p1HiatusGapDays', label: 'P1 · hiatus gap (days)', hint: 'Gap between last two releases that marks "post-hiatus"', step: 1, min: 0 },
  { key: 'p1HiatusReleaseWindowDays', label: 'P1 · hiatus release window (days)', hint: 'Wider release window used when post-hiatus', step: 1, min: 0 },
  { key: 'p2WatchPctMin', label: 'P2 · watch% min', hint: '0–1. OR-combined with unwatched ≤ p3_unwatched_max', step: 0.01, min: 0, max: 1 },
  { key: 'p2DaysSinceWatchMax', label: 'P2 · last watch max (days)', hint: 'Upper bound for the "lapsed" window', step: 1, min: 0 },
  { key: 'p3WatchPctMin', label: 'P3 · watch% min', hint: '0–1. OR-combined with unwatched ≤ p3_unwatched_max', step: 0.01, min: 0, max: 1 },
  { key: 'p3UnwatchedMax', label: 'P3 · unwatched max', hint: 'Absolute-count gate shared by P1/P2/P3', step: 1, min: 0 },
  { key: 'p3DaysSinceWatchMax', label: 'P3 · last watch max (days)', hint: 'Upper bound for "actively watching"', step: 1, min: 0 },
  { key: 'p4MinWatched', label: 'P4 · min watched', hint: 'Below this → P5', step: 1, min: 0 },
]

export function SettingsPage() {
  const { logout } = useAuth()
  const settings = useSettings()
  const mappings = useMappings()
  const refresh = useRefreshMappings()

  if (settings.isLoading) return <p className="p-6 opacity-70">Loading…</p>
  const s = settings.data as any
  const m = mappings.data as any

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <ServiceCredentialsPanel current={s} />

      <ThresholdsPanel />

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

      <LibrarySyncPanel />

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

/**
 * Threshold editor + sandbox preview. The form holds the *draft* values;
 * nothing is persisted until "Save". The sandbox runs the server-side
 * preview endpoint on the current draft values (so the user sees the
 * effect before committing).
 */
function ThresholdsPanel() {
  const live = useThresholds()
  const save = useSaveThresholds()
  const reset = useResetThresholds()
  const preview = usePriorityPreview()

  const [draft, setDraft] = useState<PriorityThresholds | null>(null)
  const [selectedSeriesIds, setSelectedSeriesIds] = useState<number[]>([])
  const [query, setQuery] = useState('')

  // Seed the draft with the server's current values once loaded,
  // and reset on live-value changes (Save / Reset causes live refetch).
  useEffect(() => {
    if (live.data) setDraft(live.data)
  }, [live.data])

  const dirty = useMemo(() => {
    if (!draft || !live.data) return false
    return THRESHOLD_FIELDS.some((f) => draft[f.key] !== live.data![f.key])
  }, [draft, live.data])

  // Re-run preview whenever draft thresholds or selection changes,
  // debounced 300ms so rapid keystrokes coalesce into one backend call.
  // Selection changes also pass through the same debounce — clicking two
  // series quickly fires one preview, not two.
  useEffect(() => {
    if (!draft || selectedSeriesIds.length === 0) return
    const t = setTimeout(() => {
      preview.mutate({ seriesIds: selectedSeriesIds, thresholds: draft })
    }, 300)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, selectedSeriesIds])

  if (live.isLoading || !draft) {
    return (
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4">
        <h2 className="font-semibold">Priority thresholds</h2>
        <p className="text-xs opacity-70 mt-1">Loading…</p>
      </div>
    )
  }

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-semibold">Priority thresholds</h2>
          <p className="text-xs opacity-70 mt-0.5">
            OR-gated: a series counts as "engaged" when either the watch-percent
            threshold is met OR the absolute unwatched count is within the
            allowance. Edits take effect on next refresh (cache is cleared on save).
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={async () => {
              // Force draft to whatever Reset returns — the useQuery
              // refetch alone doesn't always re-fire the seeding effect
              // (structural-sharing keeps the reference equal when the
              // user hadn't saved anything custom).
              const next = await reset.mutateAsync()
              setDraft(next)
            }}
            disabled={reset.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 disabled:opacity-50"
            title="Drop the DB override and restore values from config.yaml + env"
          >
            {reset.isPending ? 'Resetting…' : 'Reset to defaults'}
          </button>
          <button
            type="button"
            disabled={!dirty || save.isPending}
            onClick={() => save.mutate(draft)}
            className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-40"
          >
            {save.isPending ? 'Saving…' : dirty ? 'Save thresholds' : 'Saved'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
        {THRESHOLD_FIELDS.map((f) => (
          <label key={f.key} className="flex flex-col text-xs space-y-1">
            <span className="opacity-80">{f.label}</span>
            <input
              type="number"
              value={draft[f.key]}
              step={f.step}
              min={f.min}
              max={f.max}
              onChange={(e) =>
                setDraft({ ...draft, [f.key]: e.target.valueAsNumber })
              }
              className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm"
            />
            <span className="opacity-50">{f.hint}</span>
          </label>
        ))}
      </div>

      <SandboxPicker
        query={query}
        setQuery={setQuery}
        selectedIds={selectedSeriesIds}
        setSelectedIds={setSelectedSeriesIds}
      />

      {selectedSeriesIds.length > 0 && (
        <div className="space-y-3">
          <div className="text-xs opacity-70">
            Preview under draft thresholds
            {preview.isPending && <span className="ml-2 opacity-60">recomputing…</span>}
          </div>
          {/* Render the most recent entries even while a new mutation is
              in flight — the cards stay visible (greyed) instead of
              disappearing during the 300ms+API roundtrip. */}
          {preview.data?.entries.map((entry) => (
            <SandboxCard key={entry.seriesId} entry={entry} loading={preview.isPending} />
          ))}
          {preview.error && (
            <div className="text-xs text-red-400 font-mono">
              {String((preview.error as Error).message ?? preview.error)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function SandboxPicker({
  query, setQuery, selectedIds, setSelectedIds,
}: {
  query: string
  setQuery: (v: string) => void
  selectedIds: number[]
  setSelectedIds: (v: number[]) => void
}) {
  const seriesList = useSeriesList({ limit: 500 })
  const rows = ((seriesList.data?.records as unknown) as Array<{ id: number; title: string }>) ?? []
  const results = useMemo(() => {
    if (query.length < 2) return []
    const q = query.toLowerCase()
    return rows.filter((r) => r.title.toLowerCase().includes(q)).slice(0, 8)
  }, [rows, query])
  const selected = rows.filter((r) => selectedIds.includes(r.id))

  return (
    <div className="space-y-2 pt-2 border-t border-surface-3">
      <h3 className="text-sm font-medium">What-if sandbox</h3>
      <p className="text-xs opacity-70">
        Pick up to 3 series. The detail below recomputes live against the
        threshold values above, without writing anything.
      </p>
      <div className="flex gap-2 flex-wrap">
        {selected.map((r) => (
          <button
            key={r.id}
            type="button"
            onClick={() => setSelectedIds(selectedIds.filter((i) => i !== r.id))}
            className="px-2 py-1 text-xs rounded bg-accent hover:bg-accent/80"
            title="Remove from sandbox"
          >
            {r.title} ×
          </button>
        ))}
        {selectedIds.length < 3 && (
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search series…"
            className="bg-surface-0 border border-surface-3 rounded px-2 py-1 text-sm"
          />
        )}
      </div>
      {results.length > 0 && (
        <div className="bg-surface-0 border border-surface-3 rounded max-h-48 overflow-auto">
          {results.map((r) => {
            const already = selectedIds.includes(r.id)
            return (
              <button
                key={r.id}
                type="button"
                disabled={already || selectedIds.length >= 3}
                onClick={() => {
                  setSelectedIds([...selectedIds, r.id])
                  setQuery('')
                }}
                className="w-full text-left px-2 py-1 text-xs hover:bg-surface-3 disabled:opacity-40 flex justify-between"
              >
                <span>{r.title}</span>
                <span className="opacity-50">#{r.id}{already ? ' · selected' : ''}</span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

function SandboxCard({ entry, loading = false }: { entry: PriorityPreviewEntry; loading?: boolean }) {
  const prev = entry.previous
  const next = entry.preview
  const changed = !prev || prev.priority !== next.priority
  return (
    <div className={`bg-surface-0 border border-surface-3 rounded-md p-3 space-y-2 relative transition-opacity ${loading ? 'opacity-60' : ''}`}>
      {loading && (
        <div className="absolute top-2 right-2 text-xs opacity-70 flex items-center gap-1">
          <span className="inline-block w-3 h-3 border-2 border-text-muted border-t-accent rounded-full animate-spin" />
          recomputing
        </div>
      )}
      <div className="flex items-center gap-2">
        <span className="font-medium text-sm">{entry.title}</span>
        <span className="opacity-40 text-xs">#{entry.seriesId}</span>
        <span className="ml-auto flex items-center gap-2">
          {prev && (
            <>
              <span className={`px-2 py-0.5 text-xs rounded border ${PRIORITY_CLASS[prev.priority]}`}>
                {PRIORITY_LABELS[prev.priority] ?? `P${prev.priority}`}
              </span>
              <span className="opacity-50">→</span>
            </>
          )}
          <span className={`px-2 py-0.5 text-xs rounded border ${PRIORITY_CLASS[next.priority]} ${changed ? 'ring-2 ring-accent' : ''}`}>
            {PRIORITY_LABELS[next.priority] ?? `P${next.priority}`}
          </span>
        </span>
      </div>
      <div className="text-xs grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 opacity-90 font-mono">
        <span className="text-text-muted">Why (new)</span>
        <span className="whitespace-pre-wrap">{next.reason}</span>
        {prev && (
          <>
            <span className="text-text-muted">Why (current)</span>
            <span className="whitespace-pre-wrap opacity-60">{prev.reason}</span>
          </>
        )}
        <span className="text-text-muted">Aired / watched</span>
        <span>{entry.monitoredEpisodesWatched} / {entry.monitoredEpisodesAired} ({Math.round(entry.watchPct * 100)}%) · {entry.unwatched} unwatched</span>
        <span className="text-text-muted">Days since watch / release</span>
        <span>{entry.daysSinceWatch ?? '—'}d / {entry.daysSinceRelease ?? '—'}d</span>
      </div>
      {entry.downloads.length > 0 && (
        <div className="text-xs space-y-1 pt-2 border-t border-surface-3">
          <div className="opacity-70">Downloads under new rules:</div>
          {entry.downloads.map((d) => (
            <div key={`${d.client}-${d.clientId}`} className="flex gap-2 items-center">
              <span className="px-1.5 py-0.5 bg-surface-3 rounded font-mono">{d.client}</span>
              <span className="font-mono opacity-70 truncate">{d.clientId.slice(0, 12)}</span>
              <span className="ml-auto opacity-80">
                now: {d.currentlyPausedByUs ? 'paused' : 'running'}
              </span>
              <span className={`${d.wouldBePaused !== d.currentlyPausedByUs ? 'text-amber-400' : 'opacity-70'}`}>
                → {d.wouldBePaused ? 'paused' : 'running'}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Editable upstream-service credentials + a few app-level toggles.
 *
 * Stored as a DB override on top of the env baseline. Changes only
 * take effect after a container restart because clients are
 * constructed once at boot and held in AppState.
 *
 * Secret fields show as a masked placeholder; leaving them blank on
 * save means "no change" (the existing value persists). Typing
 * anything overwrites.
 */
function ServiceCredentialsPanel({ current }: { current: any }) {
  const save = useSaveSettings()
  const reset = useResetSettings()
  // Empty draft — only fields the user actually touches get sent.
  const [draft, setDraft] = useState<EditableSettings>({})
  const [revealSecrets, setRevealSecrets] = useState(false)

  if (!current) {
    return (
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4">
        <h2 className="font-semibold">Service credentials</h2>
        <p className="text-xs opacity-70 mt-1">Loading…</p>
      </div>
    )
  }

  const dirty = Object.keys(draft).length > 0
  const fields: Array<{
    key: keyof EditableSettings
    label: string
    placeholder?: string
    secret?: boolean
    type?: 'text' | 'url' | 'checkbox' | 'select'
    options?: string[]
  }> = [
    { key: 'sonarrUrl',         label: 'Sonarr URL',           type: 'url' },
    { key: 'sonarrApiKey',      label: 'Sonarr API key',       secret: true },
    { key: 'tautulliUrl',       label: 'Tautulli URL',         type: 'url' },
    { key: 'tautulliApiKey',    label: 'Tautulli API key',     secret: true },
    { key: 'qbitUrl',           label: 'qBittorrent URL',      type: 'url' },
    { key: 'qbitUsername',      label: 'qBittorrent username' },
    { key: 'qbitPassword',      label: 'qBittorrent password', secret: true },
    { key: 'sabUrl',            label: 'SABnzbd URL',          type: 'url' },
    { key: 'sabApiKey',         label: 'SABnzbd API key',      secret: true },
    { key: 'plexUrl',           label: 'Plex URL',             type: 'url' },
    { key: 'plexToken',         label: 'Plex token',           secret: true },
    { key: 'traktClientId',     label: 'Trakt client ID' },
    { key: 'traktAccessToken',  label: 'Trakt access token',   secret: true },
    { key: 'uiOrigin',          label: 'UI origin (for deep-links)', type: 'url' },
    { key: 'logLevel',          label: 'Log level', type: 'select', options: ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'] },
    { key: 'dryRun',            label: 'Dry-run (log actions, no upstream writes)', type: 'checkbox' },
  ]

  const set = (k: keyof EditableSettings, v: string | boolean | null) =>
    setDraft({ ...draft, [k]: v })

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-semibold">Service credentials</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Persisted as a DB override on top of the env baseline.
            Secret fields show <code>***</code> — leave blank to keep,
            type to overwrite.
            {current.hasOverrides && (
              <span className="text-amber-400 ml-2">
                Override active — restart prioritarr to apply changes.
              </span>
            )}
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setRevealSecrets(!revealSecrets)}
            className="px-3 py-1 rounded text-sm bg-surface-3"
            title="Toggle masked vs unmasked secret inputs (UI-only)"
          >
            {revealSecrets ? 'Hide' : 'Show'} secrets
          </button>
          <button
            type="button"
            onClick={async () => {
              if (!confirm('Drop the DB override and revert to env-loaded settings? Restart required.')) return
              await reset.mutateAsync()
              setDraft({})
            }}
            disabled={reset.isPending || !current.hasOverrides}
            className="px-3 py-1 rounded text-sm bg-surface-3 disabled:opacity-40"
          >
            {reset.isPending ? 'Resetting…' : 'Reset to env'}
          </button>
          <button
            type="button"
            disabled={!dirty || save.isPending}
            onClick={async () => {
              await save.mutateAsync(draft)
              setDraft({})
            }}
            className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-40"
          >
            {save.isPending ? 'Saving…' : dirty ? 'Save changes' : 'Saved'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {fields.map((f) => {
          const live = (current as any)[f.key]
          if (f.type === 'checkbox') {
            const draftVal = draft[f.key] as boolean | undefined
            const value = draftVal ?? Boolean(live)
            return (
              <label key={f.key} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={value}
                  onChange={(e) => set(f.key, e.target.checked)}
                />
                <span>{f.label}</span>
                {draftVal !== undefined && draftVal !== Boolean(live) && (
                  <span className="text-amber-400 text-xs">(modified)</span>
                )}
              </label>
            )
          }
          if (f.type === 'select') {
            const draftVal = draft[f.key] as string | undefined
            const value = draftVal ?? (live ?? '')
            return (
              <label key={f.key} className="flex flex-col text-xs space-y-1">
                <span className="opacity-80">{f.label}</span>
                <select
                  value={value}
                  onChange={(e) => set(f.key, e.target.value)}
                  className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm"
                >
                  {f.options!.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              </label>
            )
          }
          // Text / url / secret string field
          const draftVal = draft[f.key] as string | undefined
          const placeholder = f.secret
            ? (live ? '••••• (leave blank to keep)' : '(not set)')
            : (live ?? '(not set)')
          return (
            <label key={f.key} className="flex flex-col text-xs space-y-1">
              <span className="opacity-80">
                {f.label}
                {draftVal !== undefined && draftVal !== '' && (
                  <span className="text-amber-400 ml-1">(modified)</span>
                )}
              </span>
              <input
                type={f.secret && !revealSecrets ? 'password' : (f.type === 'url' ? 'url' : 'text')}
                value={draftVal ?? ''}
                onChange={(e) => set(f.key, e.target.value || null)}
                placeholder={placeholder}
                className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm"
              />
              {!f.secret && live && draftVal === undefined && (
                <span className="opacity-50">current: <code>{String(live)}</code></span>
              )}
            </label>
          )
        })}
      </div>
      {save.error && (
        <div className="text-xs text-red-400 font-mono">
          {String((save.error as Error).message ?? save.error)}
        </div>
      )}
    </div>
  )
}

/**
 * Library-wide Plex ⇆ Trakt sync. Two buttons: Preview (dry-run, never
 * commits) and Sync (real). Both yield the same shape of report; the
 * "show details" toggle expands a per-series breakdown listing the
 * actual episodes that were (or would be) pushed in each direction.
 */
function LibrarySyncPanel() {
  const sync = useLibrarySync()
  const [showDetails, setShowDetails] = useState(false)

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-semibold">Cross-source watch sync (Plex ⇆ Trakt)</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Mirrors watch state both ways for every series — episodes
            Plex knows about get scrobbled to Trakt and vice versa.
            Sequential (one HTTP call per series). Safe to re-run.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => sync.mutate({ dryRun: true, limit: 20 })}
            disabled={sync.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50 whitespace-nowrap"
            title="Sample the first 20 series in dry-run mode — completes in seconds"
          >
            {sync.isPending && sync.variables?.dryRun ? 'Previewing…' : 'Preview (sample 20)'}
          </button>
          <button
            onClick={() => {
              if (!confirm('Sync watch state for ALL series? This may take several minutes.')) return
              sync.mutate({})
            }}
            disabled={sync.isPending}
            className="px-3 py-1 rounded text-sm bg-accent hover:opacity-90 disabled:opacity-50 whitespace-nowrap"
          >
            {sync.isPending && !sync.variables?.dryRun ? 'Syncing library…' : 'Sync entire library'}
          </button>
        </div>
      </div>
      {sync.error && (
        <div className="text-xs text-red-400 font-mono">
          {String((sync.error as Error).message ?? sync.error)}
        </div>
      )}
      {sync.data && (
        <LibrarySyncReport report={sync.data} showDetails={showDetails} setShowDetails={setShowDetails} />
      )}
    </div>
  )
}

function LibrarySyncReport({
  report, showDetails, setShowDetails,
}: {
  report: import('../hooks/queries').LibrarySyncReport
  showDetails: boolean
  setShowDetails: (v: boolean) => void
}) {
  const skipped = report.perSeries.filter((p) => p.skippedReason).length
  const errored = report.perSeries.filter((p) => p.errors.length > 0).length
  const verb = report.dryRun ? 'would push' : 'pushed'
  // Only series that contributed something to the totals — the
  // detail view doesn't need 400+ "0/0" rows for shows where nothing
  // changed.
  const interesting = report.perSeries.filter(
    (p) => p.plexAdded > 0 || p.traktAdded > 0 || p.errors.length > 0 || p.skippedReason,
  )

  return (
    <div className="space-y-2">
      <div className="text-sm">
        {report.dryRun && <span className="text-amber-400 mr-2">(dry-run)</span>}
        {report.totalSeries} series checked
        {report.totalSeries <= 20 && report.dryRun && (
          <span className="opacity-60 ml-1">(sample)</span>
        )}
        {' · '}
        <span className="text-green-400">{verb} {report.plexAddedTotal}</span> to Plex,
        <span className="text-green-400"> {report.traktAddedTotal}</span> to Trakt
        {(skipped > 0 || errored > 0) && (
          <span className="text-amber-400 ml-2">
            · {skipped} skipped, {errored} with errors
          </span>
        )}
      </div>
      {interesting.length > 0 && (
        <button
          type="button"
          onClick={() => setShowDetails(!showDetails)}
          className="text-xs underline opacity-70 hover:opacity-100"
        >
          {showDetails ? 'Hide' : 'Show'} per-series detail ({interesting.length})
        </button>
      )}
      {showDetails && (
        <div className="space-y-1 pt-2 border-t border-surface-3 max-h-96 overflow-auto">
          {interesting.map((p) => (
            <SeriesSyncRow key={p.seriesId} entry={p} verb={verb} />
          ))}
        </div>
      )}
    </div>
  )
}

function SeriesSyncRow({ entry, verb }: {
  entry: import('../hooks/queries').SeriesSyncReport
  verb: string
}) {
  const [open, setOpen] = useState(false)
  const hasDetail = entry.pushedToPlex.length > 0 || entry.pushedToTrakt.length > 0 || entry.errors.length > 0
  return (
    <div className="text-xs">
      <button
        type="button"
        onClick={() => hasDetail && setOpen(!open)}
        disabled={!hasDetail}
        className="w-full flex items-center gap-2 py-1 px-2 hover:bg-surface-2 rounded disabled:cursor-default text-left"
      >
        <span className="font-medium flex-1 truncate">{entry.title}</span>
        {entry.skippedReason && <span className="text-amber-400">skipped</span>}
        {entry.plexAdded > 0 && <span className="text-green-400">→Plex {entry.plexAdded}</span>}
        {entry.traktAdded > 0 && <span className="text-green-400">→Trakt {entry.traktAdded}</span>}
        {entry.errors.length > 0 && <span className="text-red-400">{entry.errors.length} errs</span>}
        {hasDetail && (
          <span className="opacity-50 font-mono">{open ? '▾' : '▸'}</span>
        )}
      </button>
      {open && hasDetail && (
        <div className="ml-4 my-1 space-y-1 font-mono opacity-90 text-[11px]">
          {entry.skippedReason && (
            <div className="text-amber-400">{entry.skippedReason}</div>
          )}
          {entry.pushedToPlex.length > 0 && (
            <div>
              <span className="text-text-muted">Plex {verb}: </span>
              {entry.pushedToPlex.map((e) => `S${String(e.season).padStart(2,'0')}E${String(e.number).padStart(2,'0')}`).join(', ')}
            </div>
          )}
          {entry.pushedToTrakt.length > 0 && (
            <div>
              <span className="text-text-muted">Trakt {verb}: </span>
              {entry.pushedToTrakt.map((e) => `S${String(e.season).padStart(2,'0')}E${String(e.number).padStart(2,'0')}`).join(', ')}
            </div>
          )}
          {entry.errors.map((e, i) => (
            <div key={i} className="text-red-400">• {e}</div>
          ))}
        </div>
      )}
    </div>
  )
}
