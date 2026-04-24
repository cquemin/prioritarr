import { useEffect, useMemo, useState } from 'react'

import {
  useArchiveSweep,
  useBandwidth,
  useDeleteOrphans,
  useImportOrphan,
  useLibrarySync,
  useMappings,
  useOrphanSweep,
  useOrphans,
  usePriorityPreview,
  useResetBandwidth,
  useSaveBandwidth,
  useProbeOrphan,
  useRefreshMappings,
  useRenameOrphan,
  useResetSettings,
  useResetThresholds,
  useSaveSettings,
  useSaveThresholds,
  useSeriesList,
  useSettings,
  useThresholds,
  type BandwidthSettings,
  type EditableSettings,
  type OrphanAuditRow,
  type OrphanProbeResult,
  type PriorityPreviewEntry,
  type PriorityThresholds,
} from '../hooks/queries'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'
import { useAuth } from '../hooks/useAuth'

// Single source of truth for the editable threshold fields so the form
// and the sandbox know the same set of knobs, labels, and step sizes.
type ThresholdField =
  | {
      key: keyof PriorityThresholds
      kind?: 'number'
      label: string
      hint: string
      step: number
      min: number
      max?: number
    }
  | {
      key: keyof PriorityThresholds
      kind: 'checkbox'
      label: string
      hint: string
    }

const THRESHOLD_FIELDS: ThresholdField[] = [
  { key: 'p1WatchPctMin', label: 'P1 · watch% min', hint: '0–1. watchPct ≥ this OR unwatched ≤ p3 unwatched max', step: 0.01, min: 0, max: 1 },
  { key: 'p1DaysSinceWatchMax', label: 'P1 · last watch max (days)', hint: 'Inclusive upper bound for last-watch', step: 1, min: 0 },
  { key: 'p1DaysSinceReleaseMax', label: 'P1 · last release max (days)', hint: 'P1 requires fresh release within this many days', step: 1, min: 0 },
  { key: 'p1HiatusGapDays', label: 'P1 · hiatus gap (days)', hint: 'Gap between last two releases that marks "post-hiatus"', step: 1, min: 0 },
  { key: 'p1HiatusReleaseWindowDays', label: 'P1 · hiatus release window (days)', hint: 'Wider release window used when post-hiatus', step: 1, min: 0 },
  { key: 'p2WatchPctMin', label: 'P2 · watch% min', hint: '0–1. OR-combined with unwatched ≤ p3 unwatched max', step: 0.01, min: 0, max: 1 },
  { key: 'p2DaysSinceWatchMax', label: 'P2 · last watch max (days)', hint: 'Upper bound for the "lapsed" window', step: 1, min: 0 },
  { key: 'p3WatchPctMin', label: 'P3 · watch% min', hint: '0–1. OR-combined with unwatched ≤ p3 unwatched max', step: 0.01, min: 0, max: 1 },
  { key: 'p3UnwatchedMax', label: 'P3 · unwatched max', hint: 'Absolute-count gate shared by P1/P2/P3', step: 1, min: 0 },
  { key: 'p3DaysSinceWatchMax', label: 'P3 · last watch max (days)', hint: 'Upper bound for "actively watching"', step: 1, min: 0 },
  { key: 'p3DormantReleaseWindowDays', label: 'P3 · dormant release window (days)', hint: 'Caught-up show lapsed past the P2 window but a new episode dropped this recently → P3 "returning from dormancy". 0 disables.', step: 1, min: 0 },
  { key: 'p4MinWatched', label: 'P4 · min watched', hint: 'Below this → P5', step: 1, min: 0 },
  { key: 'p5WhenNothingToDownload', kind: 'checkbox', label: 'Collapse to P5 when nothing to download', hint: 'When every monitored-aired episode has a file, bypass the P1/P2/P3 bands and land in P5. Intended for "caught up, fully downloaded, waiting for next release" shows that shouldn\'t hold a queue slot.' },
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

      <BandwidthPanel />

      <ArchivePanel />

      <LibrarySyncPanel />

      <OrphanReaperPanel />

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
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Priority thresholds</h2>
          <p className="text-xs opacity-70 mt-0.5">
            OR-gated: a series counts as "engaged" when either the watch-percent
            threshold is met OR the absolute unwatched count is within the
            allowance. Edits take effect on next refresh (cache is cleared on save).
          </p>
        </div>
        {/* Action buttons stack vertically full-width on mobile, row on sm+. */}
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
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

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {THRESHOLD_FIELDS.map((f) => {
          if (f.kind === 'checkbox') {
            return (
              <label key={f.key} className="flex flex-col text-xs space-y-1 justify-between">
                <span className="opacity-80">{f.label}</span>
                <div className="flex items-center gap-2 py-1">
                  <input
                    type="checkbox"
                    checked={Boolean(draft[f.key])}
                    onChange={(e) => setDraft({ ...draft, [f.key]: e.target.checked })}
                  />
                  <span className="opacity-80">{draft[f.key] ? 'on' : 'off'}</span>
                </div>
                <span className="opacity-50">{f.hint}</span>
              </label>
            )
          }
          return (
            <label key={f.key} className="flex flex-col text-xs space-y-1">
              <span className="opacity-80">{f.label}</span>
              <input
                type="number"
                value={draft[f.key] as number}
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
          )
        })}
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
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
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
        {/* Action buttons stack vertically full-width on mobile, row on sm+. */}
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
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
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Cross-source watch sync (Plex ⇆ Trakt)</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Mirrors watch state both ways for every series — episodes
            Plex knows about get scrobbled to Trakt and vice versa.
            Sequential (one HTTP call per series). Safe to re-run.
          </p>
        </div>
        {/* Action buttons stack vertically full-width on mobile, row on sm+. */}
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
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

function hr(b: number): string {
  let n = b
  for (const u of ['B', 'KB', 'MB', 'GB', 'TB']) {
    if (n < 1024) return `${n.toFixed(1)}${u}`
    n /= 1024
  }
  return `${n.toFixed(1)}PB`
}

/**
 * OrphanReaper review surface. The recurring backend job auto-deletes
 * hardlinked-twin orphans and triggers Sonarr ManualImport for
 * importable ones; this panel:
 *
 *   1. Manual sweep buttons (Preview / Run) for on-demand reaper passes.
 *   2. Last sweep summary (counts + bytes per action class).
 *   3. The "kept" journal — every orphan the reaper couldn't safely
 *      auto-decide on, presented as a multi-select table with
 *      per-row rename / re-probe / import / delete actions and a
 *      bulk-delete for selected rows.
 */
function OrphanReaperPanel() {
  const sweep = useOrphanSweep()
  const orphans = useOrphans(500)

  // Dedupe by path — the reaper runs hourly so the same orphan can
  // appear in many audit rows. Show only the most-recent kept entry
  // per path so the table represents current state.
  const keptByPath = new Map<string, OrphanAuditRow>()
  for (const r of orphans.data ?? []) {
    if (r.action !== 'orphan_reaper_keep') continue
    const p = r.details?.path
    if (!p) continue
    const existing = keptByPath.get(p)
    if (!existing || existing.ts < r.ts) keptByPath.set(p, r)
  }
  const kept = Array.from(keptByPath.values()).sort((a, b) =>
    (b.details?.size_bytes ?? 0) - (a.details?.size_bytes ?? 0))
  const lastDelete = (orphans.data ?? []).find((r) => r.action === 'orphan_reaper_delete')
  const lastImport = (orphans.data ?? []).find((r) => r.action === 'orphan_reaper_import')

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Orphan reaper</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Sweeps the configured download folders, classifying each
            untracked file as <strong>delete</strong> (hardlinked twin in
            library / not-an-upgrade), <strong>import</strong> (Sonarr
            can match it cleanly), or <strong>keep</strong> (needs your
            judgement). Runs hourly; sweep on demand below.
          </p>
        </div>
        {/* Action buttons stack vertically full-width on mobile, row on sm+. */}
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
          <button
            onClick={() => sweep.mutate({ dryRun: true })}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 disabled:opacity-50 whitespace-nowrap"
            title="Run the reaper without writing — shows what would happen"
          >
            {sweep.isPending && sweep.variables?.dryRun ? 'Previewing…' : 'Preview sweep'}
          </button>
          <button
            onClick={() => {
              if (!confirm('Run the reaper? It will delete safe orphans + trigger Sonarr imports.')) return
              sweep.mutate({})
            }}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-50 whitespace-nowrap"
          >
            {sweep.isPending && !sweep.variables?.dryRun ? 'Sweeping…' : 'Sweep now'}
          </button>
        </div>
      </div>

      {sweep.error && (
        <div className="text-xs text-red-400 font-mono">
          {String((sweep.error as Error).message ?? sweep.error)}
        </div>
      )}

      {sweep.data && (
        <div className="text-sm">
          <span className="text-green-400">{sweep.data.deleted}</span> deleted
          <span className="opacity-50 mx-1">({hr(sweep.data.deletedBytes)}),</span>
          <span className="text-blue-400">{sweep.data.imported}</span> import-queued,
          <span className="text-amber-400 ml-1">{sweep.data.kept}</span> kept for review
          <span className="opacity-50 mx-1">({hr(sweep.data.keptBytes)}),</span>
          {sweep.data.matched} matched (active),
          {sweep.data.emptyDirsRemoved} empty dirs removed
          {sweep.data.errors > 0 && (
            <span className="text-red-400 ml-2">· {sweep.data.errors} errors</span>
          )}
        </div>
      )}

      {/* Kept journal — orphans the reaper couldn't safely classify.
          Multi-select table with per-row rename / probe / import /
          delete plus a bulk Delete-selected. */}
      <div className="pt-2 border-t border-surface-3">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-medium">
            Needs review ({kept.length}{kept.length > 0 && ` · ${hr(kept.reduce((a, r) => a + (r.details?.size_bytes ?? 0), 0))}`})
          </h3>
          <div className="text-xs opacity-60">
            {lastImport && <>last import: {lastImport.ts.slice(0, 19)} · </>}
            {lastDelete && <>last delete: {lastDelete.ts.slice(0, 19)}</>}
          </div>
        </div>
        {kept.length === 0 ? (
          <p className="text-xs opacity-60">Nothing flagged — every orphan was either deleted or imported.</p>
        ) : (
          <KeptOrphanTable rows={kept} />
        )}
      </div>
    </div>
  )
}

/**
 * Table view for "kept" orphans. Each row is selectable; bulk delete
 * acts on the selection. Per-row actions:
 *   - Rename: prompt for a new filename, server moves file in place
 *     and runs a fresh Sonarr probe; outcome shown inline.
 *   - Re-probe: re-ask Sonarr if it can match (no rename needed).
 *   - Import: queue Sonarr ManualImport (only enabled when probe says
 *     canImport=true).
 *   - Delete: filesystem-delete this single orphan.
 */
function KeptOrphanTable({ rows }: { rows: OrphanAuditRow[] }) {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const bulkDelete = useDeleteOrphans()
  const allPaths = rows.map((r) => r.details?.path).filter(Boolean) as string[]

  const toggle = (p: string) => {
    const next = new Set(selected)
    if (next.has(p)) next.delete(p); else next.add(p)
    setSelected(next)
  }
  const toggleAll = () => {
    if (selected.size === allPaths.length) setSelected(new Set())
    else setSelected(new Set(allPaths))
  }

  return (
    <div>
      <div className="flex items-center gap-3 mb-2 text-xs">
        <span className="opacity-70">{selected.size} selected</span>
        <button
          type="button"
          disabled={selected.size === 0 || bulkDelete.isPending}
          onClick={() => {
            if (!confirm(`Delete ${selected.size} orphan file(s)? This cannot be undone.`)) return
            bulkDelete.mutate(Array.from(selected), {
              onSuccess: () => setSelected(new Set()),
            })
          }}
          className="px-2 py-1 rounded bg-red-900/60 hover:bg-red-700 disabled:opacity-40"
        >
          {bulkDelete.isPending ? 'Deleting…' : 'Delete selected'}
        </button>
        {bulkDelete.data && (
          <span className="opacity-70">
            last bulk: {bulkDelete.data.succeeded}/{bulkDelete.data.total} ok
          </span>
        )}
      </div>
      <div className="bg-surface-0 border border-surface-3 rounded overflow-x-auto">
        <table className="w-full text-xs min-w-max">
          <thead className="bg-surface-2 text-left">
            <tr>
              <th className="px-2 py-1 w-8">
                <input
                  type="checkbox"
                  checked={selected.size > 0 && selected.size === allPaths.length}
                  ref={(el) => { if (el) el.indeterminate = selected.size > 0 && selected.size < allPaths.length }}
                  onChange={toggleAll}
                />
              </th>
              <th className="px-2 py-1">File</th>
              <th className="px-2 py-1">Folder</th>
              <th className="px-2 py-1 text-right">Size</th>
              <th className="px-2 py-1">Downloaded</th>
              <th className="px-2 py-1">Sonarr says</th>
              <th className="px-2 py-1 w-48">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <KeptOrphanRow
                key={r.id}
                row={r}
                checked={selected.has(r.details?.path ?? '')}
                onToggle={() => r.details?.path && toggle(r.details.path)}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function KeptOrphanRow({
  row, checked, onToggle,
}: { row: OrphanAuditRow; checked: boolean; onToggle: () => void }) {
  const path = row.details?.path ?? ''
  const reason = row.details?.reason ?? '?'
  const size = row.details?.size_bytes ?? 0
  const folder = row.details?.folder ?? ''
  const mtime = row.details?.mtime ?? null

  const rename = useRenameOrphan()
  const probe = useProbeOrphan()
  const importIt = useImportOrphan()
  const deleteOne = useDeleteOrphans()
  const [latestProbe, setLatestProbe] = useState<OrphanProbeResult | null>(null)
  const [livePath, setLivePath] = useState(path)

  const display = livePath !== path ? livePath : path
  const displayName = display.split(/[\\/]/).pop() ?? display
  const canImport = latestProbe?.canImport ?? false

  return (
    <>
      <tr className="border-t border-surface-3 hover:bg-surface-2 align-top">
        <td className="px-2 py-1.5">
          <input type="checkbox" checked={checked} onChange={onToggle} />
        </td>
        <td className="px-2 py-1.5 font-mono break-all" title={display}>{displayName}</td>
        <td className="px-2 py-1.5 opacity-80 font-mono">{folder || '—'}</td>
        <td className="px-2 py-1.5 text-right opacity-80">{hr(size)}</td>
        <td className="px-2 py-1.5 opacity-70 font-mono">{mtime?.slice(0, 19) ?? '—'}</td>
        <td className="px-2 py-1.5 text-amber-400/90">
          {latestProbe ? (
            <span className={latestProbe.canImport ? 'text-green-400' : ''}>
              {latestProbe.canImport
                ? `OK · ${latestProbe.seriesTitle ?? '?'} (${(latestProbe.episodes ?? []).join(', ') || '?'})`
                : (latestProbe.rejections ?? []).join('; ') || 'unknown'}
            </span>
          ) : (
            reason
          )}
        </td>
        <td className="px-2 py-1.5">
          <div className="flex flex-wrap gap-1">
            <button
              type="button"
              onClick={async () => {
                const next = window.prompt('New filename (same folder):', displayName)
                if (!next || next === displayName) return
                const res = await rename.mutateAsync({ path: display, newName: next })
                if (res.ok && res.newPath) {
                  setLivePath(res.newPath)
                  // After rename, re-probe automatically.
                  const newProbe = await probe.mutateAsync(res.newPath)
                  setLatestProbe(newProbe)
                } else {
                  alert(`Rename failed: ${res.message ?? 'unknown error'}`)
                }
              }}
              disabled={rename.isPending}
              className="px-1.5 py-0.5 rounded bg-surface-3 hover:bg-accent disabled:opacity-50"
              title="Rename in place — will re-probe Sonarr after"
            >Rename</button>
            <button
              type="button"
              onClick={async () => setLatestProbe(await probe.mutateAsync(display))}
              disabled={probe.isPending}
              className="px-1.5 py-0.5 rounded bg-surface-3 hover:bg-accent disabled:opacity-50"
              title="Ask Sonarr again — useful if you fixed something upstream"
            >Re-probe</button>
            <button
              type="button"
              onClick={async () => {
                const res = await importIt.mutateAsync(display)
                if (res.ok) alert('Sonarr ManualImport queued')
                else alert(`Import failed: ${res.message ?? 'unknown'}`)
              }}
              disabled={!canImport || importIt.isPending}
              className="px-1.5 py-0.5 rounded bg-surface-3 hover:bg-green-700 disabled:opacity-30"
              title={canImport ? 'Queue Sonarr ManualImport' : 'Re-probe first; only enabled when Sonarr can match'}
            >Import</button>
            <button
              type="button"
              onClick={async () => {
                if (!confirm(`Delete ${displayName}?`)) return
                await deleteOne.mutateAsync([display])
              }}
              disabled={deleteOne.isPending}
              className="px-1.5 py-0.5 rounded bg-red-900/60 hover:bg-red-700 disabled:opacity-50"
            >Delete</button>
          </div>
        </td>
      </tr>
    </>
  )
}

/**
 * Bandwidth-aware enforcement panel. Surfaces the live effective cap
 * + observed speed alongside the configuration form. Quiet-mode
 * toggle is split out as a one-click button — it's the setting a
 * user reaches for most often ("I'm starting a movie", "on a call").
 */
function BandwidthPanel() {
  const live = useBandwidth()
  const save = useSaveBandwidth()
  const reset = useResetBandwidth()
  const [draft, setDraft] = useState<BandwidthSettings | null>(null)

  useEffect(() => {
    if (live.data) setDraft(live.data.settings)
  }, [live.data])

  if (live.isLoading || !draft || !live.data) {
    return (
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4">
        <h2 className="font-semibold">Bandwidth</h2>
        <p className="text-xs opacity-70 mt-1">Loading…</p>
      </div>
    )
  }

  const disabled = draft.maxMbps <= 0
  const mbps = (bps: number) => (bps / 125_000).toFixed(1)
  const dirty = JSON.stringify(draft) !== JSON.stringify(live.data.settings)

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Bandwidth-aware enforcement</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Instead of pausing P4/P5 on sight, prioritarr only pauses
            when (a) the queue is near your line's capacity AND (b)
            the higher band would actually benefit AND (c) the
            candidate isn't close to finishing. Set max = 0 to
            disable and fall back to the naive rule.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
          <button
            type="button"
            onClick={async () => {
              const next: BandwidthSettings = { ...draft, quietModeEnabled: !draft.quietModeEnabled }
              setDraft(next)
              await save.mutateAsync(next)
            }}
            disabled={save.isPending}
            className={`px-3 py-1 rounded text-sm whitespace-nowrap ${
              draft.quietModeEnabled ? 'bg-amber-700 hover:bg-amber-600' : 'bg-surface-3 hover:bg-surface-2'
            }`}
            title="Toggle quiet mode — caps the queue while you're on a call or streaming"
          >
            {draft.quietModeEnabled ? '🤫 Quiet mode ON' : 'Quiet mode'}
          </button>
          <button
            type="button"
            onClick={async () => {
              if (!confirm('Reset bandwidth settings to env/YAML baseline?')) return
              await reset.mutateAsync()
            }}
            disabled={reset.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 disabled:opacity-50"
          >
            {reset.isPending ? 'Resetting…' : 'Reset'}
          </button>
          <button
            type="button"
            disabled={!dirty || save.isPending}
            onClick={() => save.mutate(draft)}
            className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-40"
          >
            {save.isPending ? 'Saving…' : dirty ? 'Save' : 'Saved'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs bg-surface-0 border border-surface-3 rounded p-3">
        <div>
          <div className="opacity-60">Effective cap</div>
          <div className="font-mono text-sm">
            {disabled ? '—' : live.data.effectiveCapBps > 0 ? `${mbps(live.data.effectiveCapBps)} Mbps` : 'n/a'}
          </div>
        </div>
        <div>
          <div className="opacity-60">Current total</div>
          <div className="font-mono text-sm">{mbps(live.data.currentTotalBps)} Mbps</div>
        </div>
        <div>
          <div className="opacity-60">24h peak observed</div>
          <div className="font-mono text-sm">{mbps(live.data.observedPeakBps)} Mbps</div>
        </div>
        <div>
          <div className="opacity-60">State</div>
          <div className="font-mono text-sm">
            {disabled ? 'disabled' : draft.quietModeEnabled ? 'quiet' : live.data.isPeakWindow ? 'peak hours' : 'normal'}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <NumberField label="Line capacity (Mbps)" hint="0 = disabled. Set to your advertised/real line speed."
          value={draft.maxMbps} min={0} step={1}
          onChange={(v) => setDraft({ ...draft, maxMbps: v })} />
        <NumberField label="Utilisation threshold"
          hint="Fraction of the cap at which pausing lower bands becomes worthwhile."
          value={draft.utilisationThresholdPct} min={0.1} max={1} step={0.05}
          onChange={(v) => setDraft({ ...draft, utilisationThresholdPct: v })} />
        <NumberField label="ETA buffer (minutes)"
          hint="If a lower-band torrent would finish within this window, don't interrupt."
          value={draft.etaBufferMinutes} min={0} step={1}
          onChange={(v) => setDraft({ ...draft, etaBufferMinutes: v })} />
        <NumberField label="P1 min speed (kbps)"
          hint="Below this, P1 is peer-limited; pausing P4/P5 won't help → skip."
          value={draft.p1MinSpeedKbps} min={0} step={10}
          onChange={(v) => setDraft({ ...draft, p1MinSpeedKbps: v })} />
        <NumberField label="Quiet-mode cap (Mbps)"
          hint="Applied when Quiet mode is on."
          value={draft.quietModeMaxMbps} min={1} step={5}
          onChange={(v) => setDraft({ ...draft, quietModeMaxMbps: v })} />
        <TextField label="Peak hours start (HH:MM)"
          hint="Start of the evening / streaming window; leave blank to disable."
          value={draft.peakHoursStart ?? ''}
          onChange={(v) => setDraft({ ...draft, peakHoursStart: v || null })} />
        <TextField label="Peak hours end (HH:MM)"
          hint="Wraps over midnight fine (e.g. 22:00 → 02:00)."
          value={draft.peakHoursEnd ?? ''}
          onChange={(v) => setDraft({ ...draft, peakHoursEnd: v || null })} />
        <NumberField label="Peak-hours cap (Mbps)"
          hint="Cap that applies inside the window. Blank / 0 → fall back to line capacity."
          value={draft.peakHoursMaxMbps ?? 0} min={0} step={5}
          onChange={(v) => setDraft({ ...draft, peakHoursMaxMbps: v || null })} />
      </div>
    </div>
  )
}

function NumberField({ label, hint, value, min, max, step, onChange }: {
  label: string; hint: string; value: number; min?: number; max?: number; step?: number;
  onChange: (v: number) => void
}) {
  return (
    <label className="flex flex-col text-xs space-y-1">
      <span className="opacity-80">{label}</span>
      <input type="number" value={value} min={min} max={max} step={step}
        onChange={(e) => onChange(e.target.valueAsNumber)}
        className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm" />
      <span className="opacity-50">{hint}</span>
    </label>
  )
}

function TextField({ label, hint, value, onChange }: {
  label: string; hint: string; value: string; onChange: (v: string) => void
}) {
  return (
    <label className="flex flex-col text-xs space-y-1">
      <span className="opacity-80">{label}</span>
      <input type="text" value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="HH:MM"
        className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm" />
      <span className="opacity-50">{hint}</span>
    </label>
  )
}

/**
 * Watched-archive panel. The actual enable flag + N-episode keep
 * window are YAML-driven in this iteration (settings.archive.*);
 * this panel exposes the two sweep actions — Preview (dry-run) and
 * Run (destructive). Preview is fast and non-destructive; Run
 * unmonitors each episode BEFORE deleting the file so Sonarr can't
 * re-queue a grab in between.
 */
function ArchivePanel() {
  const sweep = useArchiveSweep()
  const [showDetails, setShowDetails] = useState(false)

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Archive watched episodes</h2>
          <p className="text-xs opacity-70 mt-0.5">
            Deletes <strong>watched</strong> episodes that fall outside
            the keep window (latest season, or last N episodes when
            that season has more than the cap). Unmonitors in Sonarr
            first, then deletes — prevents the backfill sweep
            re-grabbing what you just cleaned up. Unwatched episodes
            are never touched. Configured via the <code>archive</code>
            block in the YAML overlay (<code>watched_enabled</code>,
            <code>latest_season_max_episodes</code>, <code>interval_hours</code>).
          </p>
        </div>
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
          <button
            type="button"
            onClick={() => sweep.mutate({ dryRun: true })}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50 whitespace-nowrap"
            title="Show what would be deleted without writing"
          >
            {sweep.isPending && sweep.variables?.dryRun !== false ? 'Previewing…' : 'Preview'}
          </button>
          <button
            type="button"
            onClick={() => {
              if (!confirm('Delete every watched episode outside the keep window? This cannot be undone.')) return
              sweep.mutate({ dryRun: false })
            }}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-red-900/60 hover:bg-red-700 disabled:opacity-50 whitespace-nowrap"
          >
            {sweep.isPending && sweep.variables?.dryRun === false ? 'Running…' : 'Run sweep'}
          </button>
        </div>
      </div>

      {sweep.error && (
        <div className="text-xs text-red-400 font-mono">
          {String((sweep.error as Error).message ?? sweep.error)}
        </div>
      )}
      {sweep.data && (
        <div className="text-sm space-y-2">
          <div>
            {sweep.data.seriesVisited} series visited ·{' '}
            <span className="text-amber-400">{sweep.data.candidates}</span> candidates ·{' '}
            <span className="text-green-400">{sweep.data.deleted}</span>{' '}
            {sweep.variables?.dryRun === false ? 'deleted' : 'would be deleted'}
            {sweep.data.errors > 0 && <span className="text-red-400 ml-2">· {sweep.data.errors} errors</span>}
          </div>
          {sweep.data.entries.length > 0 && (
            <>
              <button
                type="button"
                onClick={() => setShowDetails(!showDetails)}
                className="text-xs underline opacity-70 hover:opacity-100"
              >
                {showDetails ? 'Hide' : 'Show'} per-series detail ({sweep.data.entries.length})
              </button>
              {showDetails && (
                <div className="space-y-1 max-h-96 overflow-auto text-xs font-mono border-t border-surface-3 pt-2">
                  {sweep.data.entries.map((e) => (
                    <div key={e.seriesId} className="flex gap-2 flex-wrap py-0.5">
                      <span className="font-medium">{e.title}</span>
                      <span className="opacity-60 text-[10px]">#{e.seriesId}</span>
                      <span className="opacity-80">{e.episodes.join(', ')}</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

