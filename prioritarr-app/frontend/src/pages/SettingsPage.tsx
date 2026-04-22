import { useEffect, useMemo, useState } from 'react'

import {
  useLibrarySync,
  useMappings,
  usePriorityPreview,
  useRefreshMappings,
  useResetThresholds,
  useSaveThresholds,
  useSeriesList,
  useSettings,
  useThresholds,
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
            onClick={() => reset.mutate()}
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
          {preview.data?.entries.map((entry) => (
            <SandboxCard key={entry.seriesId} entry={entry} />
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

function SandboxCard({ entry }: { entry: PriorityPreviewEntry }) {
  const prev = entry.previous
  const next = entry.preview
  const changed = !prev || prev.priority !== next.priority
  return (
    <div className="bg-surface-0 border border-surface-3 rounded-md p-3 space-y-2">
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
