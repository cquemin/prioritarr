import { useEffect, useMemo, useState } from 'react'
import {
  Settings as SettingsIcon, Plug, Gauge, ListOrdered, RefreshCw, Link2, Webhook,
} from 'lucide-react'

import { navigate, useRoute, type SettingsSection } from '../hooks/useHashRoute'
import { JOBS, JOBS_BY_ID, JOB_DISPLAY_ORDER, TRIGGER_CLASS, TRIGGER_LABEL, buildCadencePatch, readPath, type JobMeta, type JobSetting } from '../lib/jobs'
import {
  useArchiveSweep,
  useBandwidth,
  useDeleteOrphans,
  useImportOrphan,
  useJobRunHistory,
  useJobRuns,
  useLibrarySync,
  useTestConnection,
  useTraktAuthBegin,
  useTraktAuthDisconnect,
  useTraktAuthPoll,
  useTraktAuthRefresh,
  useTraktUnmonitorSweep,
  useMappings,
  useOrphanSweep,
  useOrphans,
  usePriorityPreview,
  useResetBandwidth,
  useSaveBandwidth,
  useProbeOrphan,
  useRefreshMappings,
  useRenameOrphan,
  useResetThresholds,
  useSaveSettings,
  useSaveThresholds,
  useSeriesList,
  useSettings,
  useThresholds,
  type BandwidthSettings,
  type OrphanAuditRow,
  type OrphanProbeResult,
  type PriorityPreviewEntry,
  type PriorityThresholds,
} from '../hooks/queries'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'
import { useAuth } from '../hooks/useAuth'
import { SyncDirectionSelect } from '../components/SyncDirectionSelect'

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

/**
 * Settings sidebar entries — id matches the URL hash segment
 * (`#/settings/<id>`). Order is the visual order in the sidebar.
 */
const SETTINGS_NAV: ReadonlyArray<{
  id: SettingsSection
  label: string
  icon: React.ReactNode
}> = [
  { id: 'general', label: 'General', icon: <SettingsIcon size={16} /> },
  { id: 'connections', label: 'Connections', icon: <Plug size={16} /> },
  { id: 'bandwidth', label: 'Bandwidth', icon: <Gauge size={16} /> },
  { id: 'priority-rules', label: 'Priority rules', icon: <ListOrdered size={16} /> },
  { id: 'jobs', label: 'Background jobs', icon: <RefreshCw size={16} /> },
  { id: 'mappings', label: 'Mappings', icon: <Link2 size={16} /> },
  { id: 'webhooks', label: 'Webhooks', icon: <Webhook size={16} /> },
]

export function SettingsPage() {
  const settings = useSettings()
  const route = useRoute()
  const section = route.settingsSection ?? 'general'

  if (settings.isLoading) return <p className="p-6 opacity-70">Loading…</p>
  const s = settings.data as any

  return (
    <div className="flex h-full">
      {/* Settings sub-nav. On mobile (< sm) it collapses to icon-only
          @ 40px; on sm+ it expands to icon+label @ 224px. The
          Settings header label hides at the same breakpoint. */}
      <aside className="w-10 sm:w-56 shrink-0 bg-surface-1 border-r border-surface-3 py-2 sm:py-4 px-1 sm:px-2 overflow-y-auto">
        <h1 className="hidden sm:block text-xs uppercase tracking-wider opacity-50 px-3 mb-2">Settings</h1>
        <nav className="flex flex-col gap-0.5 items-center sm:items-stretch">
          {SETTINGS_NAV.map((n) => {
            const active = n.id === section
            return (
              <button
                key={n.id}
                type="button"
                title={n.label}
                onClick={() => navigate({ page: 'settings', settingsSection: n.id })}
                className={`flex items-center justify-center sm:justify-start gap-2 p-2 sm:px-3 sm:py-1.5 rounded text-sm text-left ${
                  active ? 'bg-surface-3 text-accent' : 'hover:bg-surface-2'
                }`}
              >
                {n.icon}
                <span className="hidden sm:inline">{n.label}</span>
              </button>
            )
          })}
        </nav>
      </aside>

      <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6 min-w-0">
        {section === 'general' && <GeneralSection />}
        {section === 'connections' && <ConnectionsSection s={s} />}
        {section === 'bandwidth' && <BandwidthSection />}
        {section === 'priority-rules' && <PriorityRulesSection />}
        {section === 'jobs' && <JobsSection />}
        {section === 'mappings' && <MappingsSection />}
        {section === 'webhooks' && <WebhooksSection />}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Section bodies — Phase 1 wiring. Each one is a thin wrapper around */
/* existing panel components; later phases break panels into per-card */
/* sub-components and add the per-job detail pages.                    */
/* ------------------------------------------------------------------ */

function GeneralSection() {
  const { logout } = useAuth()
  const settings = useSettings()
  const save = useSaveSettings()
  const s = settings.data as any
  const [draft, setDraft] = useState<Record<string, any>>({})
  useEffect(() => { setDraft({}) }, [s?.dryRun, s?.logLevel, s?.uiOrigin])
  const dirty = Object.keys(draft).length > 0
  const get = (k: string) => (draft[k] !== undefined ? draft[k] : s?.[k])

  return (
    <>
      <SectionHeader title="General" subtitle="Runtime, log level, UI access." />
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
        <h3 className="font-semibold">Runtime</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input
              type="checkbox"
              checked={Boolean(get('dryRun'))}
              onChange={(e) => setDraft({ ...draft, dryRun: e.target.checked })}
            />
            <span className="flex-1">
              Dry-run mode
              <span className="block text-xs opacity-60">Log every upstream write but don't actually fire it. Useful for verifying a new policy.</span>
            </span>
          </label>
          <label className="flex flex-col text-xs space-y-1">
            <span className="opacity-80">Log level</span>
            <select
              value={get('logLevel') ?? 'INFO'}
              onChange={(e) => setDraft({ ...draft, logLevel: e.target.value })}
              className="bg-surface-3 border border-surface-2 rounded px-2 py-1 font-mono text-sm"
            >
              {['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'].map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </label>
          <label className="flex flex-col text-xs space-y-1">
            <span className="opacity-80">UI origin (deep-link base)</span>
            <input
              type="url"
              value={get('uiOrigin') ?? ''}
              onChange={(e) => setDraft({ ...draft, uiOrigin: e.target.value })}
              placeholder="(not set)"
              className="bg-surface-3 border border-surface-2 rounded px-2 py-1 font-mono text-sm"
            />
          </label>
        </div>
        <div className="flex justify-end">
          <button
            type="button"
            disabled={!dirty || save.isPending}
            onClick={async () => { await save.mutateAsync(draft as any); setDraft({}) }}
            className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-30"
          >
            {save.isPending ? 'Saving…' : dirty ? 'Save' : 'Saved'}
          </button>
        </div>
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
    </>
  )
}

function ConnectionsSection({ s }: { s: any }) {
  return (
    <>
      <SectionHeader
        title="Connections"
        subtitle="One card per upstream service. Test before saving — green tick = reachable + auth + parseable response. Failures point at the cause: URL, API key, or unsupported version."
      />
      <ConnectionCard
        title="Sonarr"
        service="sonarr"
        current={s}
        fields={[
          { key: 'sonarrUrl', label: 'URL', type: 'url' },
          { key: 'sonarrApiKey', label: 'API key', secret: true },
        ]}
      />
      <ConnectionCard
        title="Tautulli"
        service="tautulli"
        current={s}
        fields={[
          { key: 'tautulliUrl', label: 'URL', type: 'url' },
          { key: 'tautulliApiKey', label: 'API key', secret: true },
        ]}
      />
      <ConnectionCard
        title="qBittorrent"
        service="qbit"
        current={s}
        fields={[
          { key: 'qbitUrl', label: 'URL', type: 'url' },
          { key: 'qbitUsername', label: 'Username' },
          { key: 'qbitPassword', label: 'Password', secret: true },
        ]}
      />
      <ConnectionCard
        title="SABnzbd"
        service="sab"
        current={s}
        fields={[
          { key: 'sabUrl', label: 'URL', type: 'url' },
          { key: 'sabApiKey', label: 'API key', secret: true },
        ]}
      />
      <ConnectionCard
        title="Plex"
        service="plex"
        current={s}
        fields={[
          { key: 'plexUrl', label: 'URL', type: 'url' },
          { key: 'plexToken', label: 'Token', secret: true },
        ]}
      />
      {/* Trakt has its own card with the OAuth dance + a hot-swap
          access token, so we don't duplicate the URL/key inputs here.
          The Trakt card embeds its own Test button. */}
      <TraktAuthPanel />
    </>
  )
}

interface ConnectionCardField {
  key: string
  label: string
  type?: 'text' | 'url'
  secret?: boolean
}

/**
 * One upstream-service connection card. Owns its own draft state +
 * save lifecycle so each service feels independent. The Test button
 * runs against the *draft* values (falling back to live secrets when
 * the user didn't re-type them) and renders a green/red badge with a
 * categorised reason on failure.
 */
function ConnectionCard({
  title, service, current, fields,
}: {
  title: string
  service: string
  current: any
  fields: ReadonlyArray<ConnectionCardField>
}) {
  const save = useSaveSettings()
  const test = useTestConnection()
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [revealSecrets, setRevealSecrets] = useState(false)

  // Reset the draft when the live values change (e.g. after Save).
  useEffect(() => { setDraft({}) }, [...fields.map((f) => current?.[f.key])]) // eslint-disable-line react-hooks/exhaustive-deps

  const dirty = Object.values(draft).some((v) => v.length > 0)
  const setField = (k: string, v: string) =>
    setDraft((d) => ({ ...d, [k]: v }))

  const onTest = () => {
    // Send only fields the user actually filled in; backend falls back
    // to the saved value otherwise. Skips the redacted "***" sentinel.
    const body: Record<string, string | null> = {}
    for (const f of fields) {
      const d = draft[f.key]?.trim()
      if (d && d !== '***') body[f.key] = d
    }
    test.mutate({ service, body })
  }

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h3 className="font-semibold">{title}</h3>
        <ConnectionTestBadge result={test.data} pending={test.isPending} error={test.error} />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {fields.map((f) => {
          const live = current?.[f.key]
          const draftVal = draft[f.key] ?? ''
          const placeholder = f.secret
            ? (live ? '••••• (leave blank to keep)' : '(not set)')
            : (live ?? '(not set)')
          return (
            <label key={f.key} className="flex flex-col text-xs space-y-1">
              <span className="opacity-80">
                {f.label}
                {draftVal !== '' && <span className="text-amber-400 ml-1">(modified)</span>}
              </span>
              <input
                type={f.secret && !revealSecrets ? 'password' : (f.type === 'url' ? 'url' : 'text')}
                value={draftVal}
                onChange={(e) => setField(f.key, e.target.value)}
                placeholder={placeholder}
                className="bg-surface-0 border border-surface-3 rounded px-2 py-1 font-mono text-sm"
              />
              {!f.secret && live && draftVal === '' && (
                <span className="opacity-50">current: <code>{String(live)}</code></span>
              )}
            </label>
          )
        })}
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onTest}
          disabled={test.isPending}
          className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50"
          title="GET the upstream's status endpoint with the values above and report back"
        >
          {test.isPending ? 'Testing…' : 'Test connection'}
        </button>
        <button
          type="button"
          onClick={() => setRevealSecrets(!revealSecrets)}
          className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2"
        >
          {revealSecrets ? 'Hide' : 'Show'} secrets
        </button>
        <button
          type="button"
          disabled={!dirty || save.isPending}
          onClick={async () => {
            const patch: Record<string, string | null> = {}
            for (const [k, v] of Object.entries(draft)) {
              if (v.length > 0) patch[k] = v
            }
            await save.mutateAsync(patch as any)
            setDraft({})
          }}
          className="ml-auto px-3 py-1 rounded text-sm bg-accent disabled:opacity-30"
        >
          {save.isPending ? 'Saving…' : dirty ? 'Save' : 'Saved'}
        </button>
      </div>
    </div>
  )
}

/**
 * Visual badge for the test outcome. Green tick = connected (with
 * version when known); red with category-specific guidance on failure
 * so the operator knows where to look (URL vs key vs version).
 */
function ConnectionTestBadge({
  result, pending, error,
}: {
  result?: import('../hooks/queries').ConnectionTestResult
  pending: boolean
  error: unknown
}) {
  if (pending) {
    return (
      <span className="text-xs px-2 py-0.5 rounded bg-surface-3 inline-flex items-center gap-1">
        <span className="inline-block w-3 h-3 border-2 border-text-muted border-t-accent rounded-full animate-spin" />
        Testing…
      </span>
    )
  }
  if (error && !result) {
    return <span className="text-xs px-2 py-0.5 rounded bg-red-900/40 text-red-300">Test request failed</span>
  }
  if (!result) return <span className="text-xs opacity-50">Untested</span>
  if (result.ok) {
    return (
      <span className="text-xs px-2 py-0.5 rounded bg-green-700/40 text-green-300 inline-flex items-center gap-1.5"
        title={result.detail ?? undefined}>
        <span>✓</span>
        <span>Connected{result.version ? ` · v${result.version.replace(/^v/, '')}` : ''}</span>
      </span>
    )
  }
  // Map status → operator-friendly hint
  const hint: Record<string, string> = {
    'connection-failed': 'Wrong URL or upstream unreachable',
    'auth-failed': 'Wrong API key / credentials',
    'version-failed': 'Reached upstream but response wasn\u2019t the expected shape',
  }
  return (
    <span
      className="text-xs px-2 py-0.5 rounded bg-red-900/40 text-red-300 inline-flex items-center gap-1.5"
      title={result.detail ?? undefined}
    >
      <span>✗</span>
      <span>{hint[result.status] ?? result.status}</span>
      {result.detail && <span className="opacity-70">· {result.detail.slice(0, 70)}</span>}
    </span>
  )
}

function BandwidthSection() {
  return (
    <>
      <SectionHeader
        title="Bandwidth"
        subtitle="Caps and decision knobs for the bandwidth-aware enforcement."
      />
      <BandwidthPanel />
    </>
  )
}

function PriorityRulesSection() {
  return (
    <>
      <SectionHeader
        title="Priority rules"
        subtitle="Tuning knobs for each P-class. Edits apply to the priority cache on save (next refresh)."
      />
      <ThresholdsPanel />
    </>
  )
}

function MappingsSection() {
  const mappings = useMappings()
  const refresh = useRefreshMappings()
  const m = mappings.data as any
  return (
    <>
      <SectionHeader
        title="Mappings"
        subtitle="Plex rating-key ↔ Sonarr series id resolution table. Refreshes hourly."
      />
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="font-semibold">Plex ↔ Sonarr mappings</h3>
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
    </>
  )
}

function WebhooksSection() {
  // Read-only docs for now. Each webhook URL is composed at the edge
  // (Authelia + Traefik strip the prefix before reaching the app), so
  // surfaces the format the user pastes into Sonarr/SAB/Tautulli.
  return (
    <>
      <SectionHeader
        title="Webhooks"
        subtitle="Paste these URLs into the upstream services to drive real-time UI updates."
      />
      <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3 text-sm">
        <WebhookRow
          name="Sonarr"
          path="/prioritarr/api/sonarr/on-grab"
          notes="Sonarr → Settings → Connect → Webhook. Trigger on Grab, Download, Episode File Delete, Series Delete, Manual Interaction Required, Test."
        />
        <WebhookRow
          name="SABnzbd"
          path="/prioritarr/api/sab/webhook?nzo_id=$NZO&status=$STATUS&fail_message=$FAIL_MSG"
          notes="SABnzbd → Config → Notifications → Notification scripts (or a wrapper script that curls this URL on post-processing)."
        />
        <WebhookRow
          name="Plex / Tautulli"
          path="/prioritarr/api/plex-event"
          notes="Tautulli → Settings → Notification Agents → Webhook → POST on watched-event with JSON payload."
        />
      </div>
    </>
  )
}

function WebhookRow({ name, path, notes }: { name: string; path: string; notes: string }) {
  return (
    <div>
      <div className="flex items-center gap-2">
        <span className="font-semibold">{name}</span>
        <code className="text-xs font-mono px-2 py-0.5 bg-surface-3 rounded select-all">{path}</code>
      </div>
      <div className="text-xs opacity-70 mt-1">{notes}</div>
    </div>
  )
}

function JobsSection() {
  const route = useRoute()
  if (route.jobId) {
    const job = JOBS_BY_ID[route.jobId]
    if (!job) {
      // Bad id — bounce back to the grid rather than show an empty page.
      return <JobsGrid />
    }
    return <JobDetail job={job} />
  }
  return <JobsGrid />
}

function JobsGrid() {
  const runs = useJobRuns()
  // Index by jobId for O(1) lookup as the grid renders. Empty when
  // the query is loading — cards just show "no runs yet" in that case.
  const runByJob = useMemo(() => {
    const m = new Map<string, ReturnType<typeof useJobRuns>['data'] extends (infer T)[] | undefined ? T : never>()
    for (const r of runs.data ?? []) m.set(r.jobId, r as any)
    return m
  }, [runs.data])
  return (
    <>
      <SectionHeader
        title="Background jobs"
        subtitle="Every scheduled or event-driven job that prioritarr runs. Click a card for details, settings, and (where applicable) a manual trigger."
      />
      <ColorLegend />
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {JOB_DISPLAY_ORDER.flatMap((trigger) =>
          JOBS.filter((j) => j.trigger === trigger).map((job) => (
            <JobCard key={job.id} job={job} lastRun={runByJob.get(job.id)} />
          )),
        )}
      </div>
    </>
  )
}

function ColorLegend() {
  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-3 text-xs flex flex-wrap gap-x-5 gap-y-2 items-center">
      <span className="opacity-70">Color code:</span>
      <LegendItem trigger="auto+manual" label="Scheduled · manual trigger available" />
      <LegendItem trigger="auto" label="Scheduled only — no manual button" />
      <LegendItem trigger="event" label="Event-driven (incoming webhook)" />
      <LegendItem trigger="manual" label="Manual only — not on a clock" />
    </div>
  )
}

function LegendItem({ trigger, label }: { trigger: keyof typeof TRIGGER_CLASS; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`w-2 h-2 rounded-full ${TRIGGER_CLASS[trigger].dot}`} />
      <span className="opacity-90">{label}</span>
    </span>
  )
}

function JobCard({ job, lastRun }: { job: JobMeta; lastRun?: import('../hooks/queries').JobRun }) {
  const settings = useSettings()
  const data = settings.data as any
  const cadenceValue = job.cadence ? readPath(data, job.cadence.key) : null
  return (
    <button
      type="button"
      onClick={() => navigate({ page: 'settings', settingsSection: 'jobs', jobId: job.id })}
      className={`text-left bg-surface-1 rounded-lg p-3 hover:bg-surface-2 transition-colors space-y-2 ${TRIGGER_CLASS[job.trigger].border} border-y border-r border-surface-3`}
    >
      <div className="flex items-center gap-2">
        <span className="text-text-secondary">{job.icon}</span>
        <span className="font-medium text-sm flex-1 min-w-0 truncate">{job.name}</span>
        <RunStatusDot run={lastRun} />
      </div>
      <div className="text-xs opacity-80">{job.short}</div>
      <div className="flex items-center gap-2 text-xs">
        <span className={`px-1.5 py-0.5 rounded ${TRIGGER_CLASS[job.trigger].chip}`}>
          {TRIGGER_LABEL[job.trigger]}
        </span>
        {job.cadence && cadenceValue != null && (
          <span className="opacity-70">every {cadenceValue} {job.cadence.unit === 'hours' ? 'h' : 'm'}</span>
        )}
        {!job.cadence && <span className="opacity-50 italic">on demand</span>}
        {lastRun && <span className="opacity-60">· {relativeTime(lastRun.startedAt)}</span>}
      </div>
    </button>
  )
}

/**
 * Tiny status indicator: green = ok, red = error, gray = noop, hollow
 * = no run recorded. Hover surfaces the full timestamp + summary.
 */
function RunStatusDot({ run }: { run?: import('../hooks/queries').JobRun }) {
  if (!run) {
    return (
      <span
        className="w-2 h-2 rounded-full border border-surface-3"
        title="No runs recorded yet"
      />
    )
  }
  const color =
    run.status === 'ok' ? 'bg-green-500' :
    run.status === 'error' ? 'bg-red-500' :
    'bg-zinc-500'
  const tooltip = [
    `${run.status === 'error' ? 'Failed' : run.status === 'noop' ? 'Skipped' : 'Ran'} at ${run.startedAt.replace('T', ' ').slice(0, 19)}`,
    run.summary && `· ${run.summary}`,
    run.errorMessage && `· ${run.errorMessage}`,
    `· ${formatDuration(run.durationMs)}`,
  ].filter(Boolean).join(' ')
  return <span className={`w-2 h-2 rounded-full ${color}`} title={tooltip} />
}

/**
 * Format a millisecond duration with the right unit. < 1s shows ms,
 * < 60s shows fractional seconds, < 60m shows m+s, otherwise h+m.
 * Keeps two significant figures in the seconds case so 9377ms reads
 * "9.4s" not "9.377s".
 */
function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) return `${ms}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(sec < 10 ? 1 : 0)}s`
  const totalSec = Math.round(sec)
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  if (m < 60) return s === 0 ? `${m}m` : `${m}m ${s}s`
  const h = Math.floor(m / 60)
  const remM = m % 60
  return remM === 0 ? `${h}h` : `${h}h ${remM}m`
}

/**
 * Relative-time formatter — "5m ago" / "2h ago" / "3d ago". Coarse on
 * purpose; the tooltip on RunStatusDot has the full ISO timestamp for
 * anyone who needs precision.
 */
function relativeTime(iso: string): string {
  const t = new Date(iso).getTime()
  if (!Number.isFinite(t)) return ''
  const sec = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (sec < 60) return `${sec}s ago`
  const min = Math.floor(sec / 60); if (min < 60) return `${min}m ago`
  const hr = Math.floor(min / 60); if (hr < 24) return `${hr}h ago`
  return `${Math.floor(hr / 24)}d ago`
}

function JobDetail({ job }: { job: JobMeta }) {
  const settings = useSettings()
  const save = useSaveSettings()
  const data = settings.data as any
  const cadenceValue = job.cadence ? readPath(data, job.cadence.key) : null
  const [cadenceDraft, setCadenceDraft] = useState<number | ''>(cadenceValue ?? '')
  useEffect(() => { if (cadenceValue != null) setCadenceDraft(cadenceValue) }, [cadenceValue])
  const cadenceDirty = typeof cadenceDraft === 'number' && cadenceDraft !== cadenceValue

  return (
    <>
      <div className="flex items-center gap-3 flex-wrap">
        <button
          type="button"
          onClick={() => navigate({ page: 'settings', settingsSection: 'jobs' })}
          className="text-xs opacity-70 hover:opacity-100"
        >
          ← Back to all jobs
        </button>
      </div>
      <div className={`bg-surface-1 rounded-lg p-4 space-y-3 ${TRIGGER_CLASS[job.trigger].border} border-y border-r border-surface-3`}>
        <div className="flex items-center gap-3 flex-wrap">
          <span className="text-text-secondary">{job.icon}</span>
          <h2 className="text-xl font-semibold flex-1 min-w-0">{job.name}</h2>
          <span className={`px-2 py-0.5 text-xs rounded ${TRIGGER_CLASS[job.trigger].chip}`}>
            {TRIGGER_LABEL[job.trigger]}
          </span>
        </div>
        <p className="text-sm opacity-80 leading-relaxed">{job.description}</p>
        {job.why && (
          <p className="text-sm opacity-70 leading-relaxed border-l-2 border-surface-3 pl-3 italic">{job.why}</p>
        )}

        {/* Some jobs have a richer control panel with preview / result
            breakdown — render that in place of the bare manual button.
            The bare button still drives jobs without a rich panel. */}
        {job.id === 'trakt-unmonitor' ? (
          <div className="-m-4 mt-2 pt-3 border-t border-surface-3"><TraktUnmonitorPanel /></div>
        ) : job.id === 'watched-archiver' ? (
          <div className="-m-4 mt-2 pt-3 border-t border-surface-3"><ArchivePanel /></div>
        ) : job.id === 'library-sync' ? (
          <div className="-m-4 mt-2 pt-3 border-t border-surface-3"><LibrarySyncPanel /></div>
        ) : job.id === 'orphan-reaper' ? (
          <div className="-m-4 mt-2 pt-3 border-t border-surface-3"><OrphanReaperPanel /></div>
        ) : job.manual ? (
          <ManualTriggerButton job={job} />
        ) : null}
      </div>

      {job.cadence && (
        <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
          <h3 className="font-semibold">Schedule</h3>
          <p className="text-xs opacity-70">
            Edits apply on the next tick — no restart needed.
          </p>
          <div className="flex items-center gap-2">
            <label htmlFor={`${job.id}-cadence`} className="text-sm opacity-80">Run every</label>
            <input
              id={`${job.id}-cadence`}
              type="number"
              min={job.cadence.min ?? 1}
              step={1}
              value={cadenceDraft}
              onChange={(e) => setCadenceDraft(e.target.valueAsNumber)}
              className="w-24 bg-surface-3 border border-surface-2 rounded px-2 py-1 font-mono text-sm"
            />
            <span className="text-sm opacity-80">{job.cadence.unit}</span>
            <button
              type="button"
              disabled={!cadenceDirty || save.isPending}
              onClick={() =>
                save.mutate(buildCadencePatch(job.cadence!.key, cadenceDraft as number))
              }
              className="ml-2 px-3 py-1 rounded text-sm bg-accent disabled:opacity-30"
            >
              {save.isPending ? 'Saving…' : cadenceDirty ? 'Save' : 'Saved'}
            </button>
          </div>
        </div>
      )}

      {job.settings && job.settings.length > 0 && (
        <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
          <h3 className="font-semibold">Job-specific settings</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {job.settings.map((s) => (
              <JobSettingInput key={s.key} setting={s} data={data} />
            ))}
          </div>
        </div>
      )}

      <RecentRunsCard jobId={job.id} />

      {job.relatedSettings && job.relatedSettings.length > 0 && (
        <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
          <h3 className="font-semibold">Related settings</h3>
          <p className="text-xs opacity-70">Cross-cutting knobs that influence this job. Editing happens in their own section.</p>
          <ul className="text-sm space-y-1">
            {job.relatedSettings.map((r) => (
              <li key={`${r.section}.${r.field}`}>
                <button
                  type="button"
                  className="underline hover:text-accent text-left"
                  onClick={() => navigate({ page: 'settings', settingsSection: r.section as SettingsSection })}
                >
                  {r.sectionLabel}
                  {r.field !== '*' && <span className="opacity-60"> · {r.field}</span>}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  )
}

function JobSettingInput({ setting, data }: { setting: JobSetting; data: any }) {
  const save = useSaveSettings()
  const live = data?.[setting.key]
  const [draft, setDraft] = useState<any>(live ?? '')
  useEffect(() => { if (live !== undefined) setDraft(live) }, [live])
  const dirty = draft !== live && draft !== ''

  if (setting.type === 'boolean') {
    return (
      <label className="flex items-start gap-2 text-sm sm:col-span-2">
        <input
          type="checkbox"
          checked={Boolean(live)}
          disabled={save.isPending}
          onChange={(e) => save.mutate({ [setting.key]: e.target.checked })}
          className="mt-1"
        />
        <span className="flex-1">
          <div>{setting.label}</div>
          {setting.hint && <div className="text-xs opacity-60 mt-0.5">{setting.hint}</div>}
        </span>
      </label>
    )
  }

  return (
    <label className="flex flex-col text-xs space-y-1">
      <span className="opacity-80">{setting.label}</span>
      <div className="flex gap-2">
        <input
          type={setting.type === 'number' ? 'number' : 'text'}
          value={draft}
          step={setting.type === 'number' ? setting.step : undefined}
          min={setting.type === 'number' ? setting.min : undefined}
          onChange={(e) => setDraft(setting.type === 'number' ? e.target.valueAsNumber : e.target.value)}
          className="flex-1 bg-surface-3 border border-surface-2 rounded px-2 py-1 font-mono text-sm"
        />
        <button
          type="button"
          disabled={!dirty || save.isPending}
          onClick={() => save.mutate({ [setting.key]: draft })}
          className="px-2 py-1 rounded text-xs bg-accent disabled:opacity-30 whitespace-nowrap"
        >
          {save.isPending ? '…' : 'Save'}
        </button>
      </div>
      {setting.hint && <span className="opacity-50">{setting.hint}</span>}
    </label>
  )
}

function RecentRunsCard({ jobId }: { jobId: string }) {
  const runs = useJobRunHistory(jobId, 10)
  const list = runs.data ?? []
  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-2">
      <h3 className="font-semibold">Recent runs</h3>
      {runs.isLoading && <div className="text-xs opacity-70">Loading…</div>}
      {!runs.isLoading && list.length === 0 && (
        <div className="text-xs opacity-70">
          No runs recorded yet. Scheduler tracking started when the container last
          rebooted; manual-only and event-driven jobs only appear after a real run.
        </div>
      )}
      {list.length > 0 && (
        <ul className="text-xs space-y-1 font-mono">
          {list.map((r, i) => (
            <li
              key={`${r.startedAt}-${i}`}
              className="grid grid-cols-[auto_auto_1fr_auto] gap-3 items-baseline"
              title={r.errorMessage ?? r.summary ?? ''}
            >
              <RunStatusDot run={r} />
              <span className="opacity-80">{r.startedAt.replace('T', ' ').slice(0, 19)}</span>
              <span className="opacity-70 truncate">
                {r.errorMessage ?? r.summary ?? <span className="opacity-40">—</span>}
              </span>
              <span className="opacity-50 tabular-nums">{formatDuration(r.durationMs)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function ManualTriggerButton({ job }: { job: JobMeta }) {
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<string | null>(null)
  if (!job.manual) return null
  const onClick = async () => {
    setBusy(true); setOutcome(null)
    const params = new URLSearchParams(job.manual!.query ?? {})
    const qs = params.toString()
    const url = `${job.manual!.path}${qs ? `?${qs}` : ''}`
    try {
      const key = (window as any).localStorage?.getItem?.('apiKey') ?? null
      const res = await fetch(url, {
        method: job.manual!.method,
        headers: { ...(key ? { 'X-Api-Key': key } : {}) },
      })
      setOutcome(res.ok ? `OK (${res.status})` : `Failed: ${res.status} ${res.statusText}`)
    } catch (e) {
      setOutcome(`Failed: ${(e as Error).message ?? e}`)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex items-center gap-3 flex-wrap pt-2 border-t border-surface-3">
      <button
        type="button"
        onClick={onClick}
        disabled={busy}
        className="px-3 py-1 rounded text-sm bg-accent disabled:opacity-50"
      >
        {busy ? 'Running…' : job.manual.label}
      </button>
      {outcome && <span className={`text-xs font-mono ${outcome.startsWith('OK') ? 'text-green-400' : 'text-red-400'}`}>{outcome}</span>}
      <span className="text-xs opacity-50">
        {job.manual.method} {job.manual.path}{job.manual.query ? `?${new URLSearchParams(job.manual.query).toString()}` : ''}
      </span>
    </div>
  )
}

function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div>
      <h2 className="text-2xl font-semibold">{title}</h2>
      {subtitle && <p className="text-sm opacity-70 mt-1">{subtitle}</p>}
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

      {/* Per-priority cards. Grouped by P1/P2/P3/P4/P5 prefix because
          users reason about "what makes a series P1 vs P2", not about
          "all the watch-percent knobs". The card label uses the
          existing PRIORITY_LABELS so wording stays consistent with the
          rest of the UI. Single-knob priorities (P4, P5) are still
          rendered as cards for visual rhythm even though by-domain
          grouping would have folded them. */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {([1, 2, 3, 4, 5] as const).map((p) => {
          const fields = THRESHOLD_FIELDS.filter((f) => String(f.key).startsWith(`p${p}`))
          if (fields.length === 0) return null
          return (
            <div
              key={p}
              className={`bg-surface-0 border rounded-lg p-3 space-y-3 ${PRIORITY_CLASS[p]?.replace(/text-\S+/g, '') ?? 'border-surface-3'}`}
            >
              <div className="flex items-center gap-2">
                <span className={`px-2 py-0.5 rounded text-xs border ${PRIORITY_CLASS[p]}`}>P{p}</span>
                <span className="text-sm font-medium">{PRIORITY_LABELS[p]?.replace(/^P\d\s+/, '') ?? `Priority ${p}`}</span>
                <span className="ml-auto text-xs opacity-50">{fields.length} knob{fields.length === 1 ? '' : 's'}</span>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {fields.map((f) => {
                  if (f.kind === 'checkbox') {
                    return (
                      <label key={f.key} className="flex flex-col text-xs space-y-1 justify-between sm:col-span-2">
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
                      <span className="opacity-80">{f.label.replace(/^P\d\s*·\s*/, '')}</span>
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
            </div>
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
 * Library-wide Plex ⇆ Trakt sync. Two buttons: Preview (dry-run, never
 * commits) and Sync (real). Both yield the same shape of report; the
 * "show details" toggle expands a per-series breakdown listing the
 * actual episodes that were (or would be) pushed in each direction.
 */
function LibrarySyncPanel() {
  const sync = useLibrarySync()
  const [showDetails, setShowDetails] = useState(false)
  const [direction, setDirection] = useState<import('../hooks/queries').SyncDirection>('both')

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
          <SyncDirectionSelect value={direction} onChange={setDirection} disabled={sync.isPending} />
          <button
            onClick={() => sync.mutate({ dryRun: true, limit: 20, direction })}
            disabled={sync.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50 whitespace-nowrap"
            title="Sample the first 20 series in dry-run mode — completes in seconds"
          >
            {sync.isPending && sync.variables?.dryRun ? 'Previewing…' : 'Preview (sample 20)'}
          </button>
          <button
            onClick={() => {
              if (!confirm('Sync watch state for ALL series? This may take several minutes.')) return
              sync.mutate({ direction })
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
/**
 * Trakt OAuth device-code flow panel. Three states:
 *   1. Disconnected — show "Connect Trakt" button (requires
 *      clientId + clientSecret in Settings first; we surface a hint).
 *   2. Activation in progress — show user_code prominently, a link to
 *      trakt.tv/activate, and a "Waiting…" status that auto-polls.
 *   3. Connected — show expiry + "Refresh" + "Disconnect" buttons.
 *
 * Token writes persist to the settings DB override; the running
 * TraktClient still uses the boot-time access_token, so a restart is
 * required to actually start using the new credentials. We surface
 * that in the success message.
 */
function TraktAuthPanel() {
  const settings = useSettings()
  const begin = useTraktAuthBegin()
  const poll = useTraktAuthPoll()
  const refresh = useTraktAuthRefresh()
  const disconnect = useTraktAuthDisconnect()
  const s = (settings.data ?? {}) as {
    traktClientId?: string | null
    traktClientSecret?: string | null
    traktAccessToken?: string | null
    traktTokenExpiresAt?: string | null
  }
  // Treat "***" (the redacted-secret marker) as "set"; "" or null as
  // "not set". The redactSecret helper returns "" for null/blank input.
  const hasClientId = !!s.traktClientId && s.traktClientId.length > 0
  const hasClientSecret = !!s.traktClientSecret && s.traktClientSecret.length > 0
  const hasAccessToken = !!s.traktAccessToken && s.traktAccessToken.length > 0
  const expiresAt = s.traktTokenExpiresAt && s.traktTokenExpiresAt.length > 0
    ? new Date(s.traktTokenExpiresAt)
    : null
  const daysToExpiry = expiresAt
    ? Math.round((expiresAt.getTime() - Date.now()) / (1000 * 60 * 60 * 24))
    : null
  // Trakt issues access + refresh tokens together with ~90-day windows
  // tied to the same created_at. Each successful refresh rotates the
  // pair, so tracking access_token expiry implicitly tracks the refresh
  // token's window too (always within ~24h of each other in practice).
  const issuedAt = expiresAt
    ? new Date(expiresAt.getTime() - 90 * 24 * 60 * 60 * 1000)
    : null
  const tokenAgeDays = issuedAt
    ? Math.round((Date.now() - issuedAt.getTime()) / (1000 * 60 * 60 * 24))
    : null

  // Active flow state — held entirely in this component; no global store
  // needed because the user can only run one activation at a time.
  const [activeFlow, setActiveFlow] = useState<{
    deviceCode: string
    userCode: string
    verificationUrl: string
    expiresAt: number
    intervalMs: number
  } | null>(null)
  const [pollMessage, setPollMessage] = useState<string | null>(null)

  // Auto-poll while the flow is active. Stops on success, expiry, or
  // when the user navigates away (component unmount cleans the timer).
  useEffect(() => {
    if (!activeFlow) return
    let cancelled = false
    const tick = async () => {
      if (cancelled) return
      if (Date.now() > activeFlow.expiresAt) {
        setPollMessage('Activation window expired — start over.')
        setActiveFlow(null)
        return
      }
      try {
        const r = await poll.mutateAsync(activeFlow.deviceCode)
        if (cancelled) return
        if (r.status === 'connected') {
          setPollMessage('Connected! Restart the container to start using the new tokens.')
          setActiveFlow(null)
          settings.refetch()
          return
        }
      } catch (e) {
        // Hard error — likely "denied" or "expired".
        setPollMessage(`Stopped: ${(e as Error).message ?? e}`)
        setActiveFlow(null)
        return
      }
      setTimeout(tick, activeFlow.intervalMs)
    }
    const t = setTimeout(tick, activeFlow.intervalMs)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFlow])

  const startFlow = async () => {
    setPollMessage(null)
    try {
      const r = await begin.mutateAsync()
      setActiveFlow({
        deviceCode: r.device_code,
        userCode: r.user_code,
        verificationUrl: r.verification_url,
        expiresAt: Date.now() + r.expires_in * 1000,
        intervalMs: Math.max(r.interval, 1) * 1000,
      })
    } catch (e) {
      setPollMessage(`Failed to start: ${(e as Error).message ?? e}`)
    }
  }

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div>
        <h2 className="font-semibold">Trakt connection</h2>
        <p className="text-xs opacity-70 mt-0.5">
          Connect prioritarr to your Trakt account using Trakt's
          device-code OAuth flow — no copy-pasting tokens.
          Requires <strong>traktClientId</strong> and <strong>traktClientSecret</strong>
          {' '}from your Trakt app at{' '}
          <a className="underline" href="https://trakt.tv/oauth/applications" target="_blank" rel="noreferrer">
            trakt.tv/oauth/applications
          </a>.
        </p>
      </div>

      {(!hasClientId || !hasClientSecret) && (
        <div className="text-xs text-amber-300 bg-amber-900/20 border border-amber-800/40 rounded p-2">
          Set <strong>traktClientId</strong> and <strong>traktClientSecret</strong> in the credentials section above first, then return here.
        </div>
      )}

      {!activeFlow && hasAccessToken && (
        <div className="flex items-center gap-3 flex-wrap">
          <div className="text-sm flex-1 min-w-0 space-y-1">
            <div>
              <span className="text-green-400">✓ Connected</span>
              {tokenAgeDays !== null && (
                <span className="opacity-70 ml-2">
                  — last refreshed {tokenAgeDays === 0 ? 'today' : `${tokenAgeDays} day${tokenAgeDays === 1 ? '' : 's'} ago`}
                </span>
              )}
            </div>
            {expiresAt && (
              <div className="text-xs opacity-70 grid grid-cols-[max-content_1fr] gap-x-3">
                <span>Access token:</span>
                <span>
                  expires {daysToExpiry !== null && daysToExpiry >= 0 ? `in ${daysToExpiry} day${daysToExpiry === 1 ? '' : 's'}` : 'soon'}
                  <span className="opacity-60 font-mono ml-2">{expiresAt.toISOString().slice(0, 10)}</span>
                </span>
                <span>Refresh token:</span>
                <span>
                  rotates with the access token (same ~90-day window)
                </span>
              </div>
            )}
            <div className="text-xs opacity-60 italic">
              Auto-refreshes within 7 days of expiry — no manual action needed in normal use.
              Refresh + reconnect buttons are fallbacks for the rare cases (Trakt outage, &gt;90 days offline, revoked app).
            </div>
          </div>
          <button
            type="button"
            onClick={() =>
              refresh.mutate(undefined, {
                onSuccess: (r) => {
                  if (r.status === 'reconnect_required') {
                    setPollMessage(
                      `Refresh failed: ${(r as { detail?: string }).detail ?? 'refresh_token expired or revoked'}. Click "Connect Trakt" to start over.`,
                    )
                  } else {
                    setPollMessage('Tokens refreshed.')
                  }
                },
              })
            }
            disabled={refresh.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50"
            title="Mint a fresh access_token using the stored refresh_token. If Trakt rejects it (revoked / >90 days idle), tokens clear and you reconnect."
          >
            {refresh.isPending ? 'Refreshing…' : 'Refresh tokens'}
          </button>
          <button
            type="button"
            onClick={() => {
              if (!confirm('Disconnect Trakt? Sync + unmonitor reconciler will stop working until you reconnect (and restart).')) return
              disconnect.mutate()
            }}
            disabled={disconnect.isPending}
            className="px-3 py-1 rounded text-sm bg-red-900/60 hover:bg-red-700 disabled:opacity-50"
          >
            {disconnect.isPending ? 'Disconnecting…' : 'Disconnect'}
          </button>
        </div>
      )}

      {!activeFlow && !hasAccessToken && hasClientId && hasClientSecret && (
        <button
          type="button"
          onClick={startFlow}
          disabled={begin.isPending}
          className="px-3 py-1 rounded text-sm bg-accent hover:opacity-90 disabled:opacity-50"
        >
          {begin.isPending ? 'Starting…' : 'Connect Trakt'}
        </button>
      )}

      {activeFlow && (
        <div className="space-y-3 border-t border-surface-3 pt-3">
          <div>
            <div className="text-xs opacity-70 uppercase tracking-wider mb-1">Step 1 — open</div>
            <a
              href={activeFlow.verificationUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-block px-3 py-1 rounded bg-surface-3 hover:bg-surface-2 underline text-sm"
            >
              {activeFlow.verificationUrl} ↗
            </a>
          </div>
          <div>
            <div className="text-xs opacity-70 uppercase tracking-wider mb-1">Step 2 — enter this code</div>
            <div className="flex items-center gap-2">
              <code className="text-2xl font-mono px-3 py-2 bg-surface-3 rounded tracking-widest">
                {activeFlow.userCode}
              </code>
              <button
                type="button"
                className="px-2 py-1 text-xs rounded bg-surface-3 hover:bg-surface-2"
                onClick={() => navigator.clipboard.writeText(activeFlow.userCode)}
              >
                Copy
              </button>
            </div>
          </div>
          <div className="text-xs opacity-70">
            Auto-polling every {activeFlow.intervalMs / 1000}s · waiting for you to activate…
            <button
              type="button"
              className="ml-3 underline opacity-80"
              onClick={() => { setActiveFlow(null); setPollMessage('Cancelled.') }}
            >
              cancel
            </button>
          </div>
        </div>
      )}

      {pollMessage && (
        <div className={`text-xs font-mono ${pollMessage.startsWith('Connected') ? 'text-green-400' : pollMessage.startsWith('Stopped') || pollMessage.startsWith('Failed') ? 'text-red-400' : 'opacity-70'}`}>
          {pollMessage}
        </div>
      )}
      {begin.error && !activeFlow && (
        <div className="text-xs text-red-400 font-mono">{String((begin.error as Error).message ?? begin.error)}</div>
      )}
    </div>
  )
}

/**
 * Trakt→Sonarr unmonitor reconciler panel. Mirrors the chrome of
 * LibrarySyncPanel — Preview (dry-run, sample N), Run (library-wide),
 * plus an inline enable-toggle and tag-name editor. The tag name is
 * persisted via the standard settings override path; changes take
 * effect on the next container restart for the scheduled tick (manual
 * Run button picks up live changes immediately).
 */
function TraktUnmonitorPanel() {
  const sweep = useTraktUnmonitorSweep()
  const settings = useSettings()
  const save = useSaveSettings()
  const [showDetails, setShowDetails] = useState(false)

  const s = (settings.data ?? {}) as {
    traktUnmonitorEnabled?: boolean
    traktUnmonitorProtectTag?: string
    traktUnmonitorSkipSpecials?: boolean
    traktUnmonitorIntervalHours?: number
  }
  const enabled = s.traktUnmonitorEnabled ?? false
  const tag = s.traktUnmonitorProtectTag ?? 'prioritarr-no-unmonitor'
  const [tagDraft, setTagDraft] = useState(tag)
  useEffect(() => { setTagDraft(tag) }, [tag])
  const tagDirty = tagDraft.trim() !== tag.trim() && tagDraft.trim().length > 0

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold">Trakt → Sonarr auto-unmonitor</h2>
          <p className="text-xs opacity-70 mt-0.5">
            For every episode Trakt reports as watched but Sonarr still
            monitors without a file, flips the episode's monitored flag
            off so the backfill sweep stops chasing it. Never touches
            episodes that are on disk. Series carrying the configured
            Sonarr tag are skipped entirely — add that tag via the
            drawer, the bulk bar below the Series table, or Sonarr's UI.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row gap-2 w-full sm:w-auto">
          <button
            onClick={() => sweep.mutate({ dryRun: true, limit: 30 })}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-50 whitespace-nowrap"
            title="Dry-run the first 30 series to preview what would be unmonitored"
          >
            {sweep.isPending && sweep.variables?.dryRun ? 'Previewing…' : 'Preview (sample 30)'}
          </button>
          <button
            onClick={() => {
              if (!confirm('Unmonitor every watched-but-not-downloaded episode across the entire library? This is reversible via Sonarr but affects many episodes at once.')) return
              sweep.mutate({})
            }}
            disabled={sweep.isPending}
            className="px-3 py-1 rounded text-sm bg-accent hover:opacity-90 disabled:opacity-50 whitespace-nowrap"
          >
            {sweep.isPending && !sweep.variables?.dryRun ? 'Running…' : 'Run sweep now'}
          </button>
        </div>
      </div>

      {/* enable + tag editor row */}
      <div className="flex flex-col sm:flex-row gap-3 sm:items-center border-t border-surface-3 pt-3">
        <label className="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            disabled={save.isPending || settings.isLoading}
            onChange={(e) => save.mutate({ traktUnmonitorEnabled: e.target.checked })}
          />
          Enable scheduled sweep (every 6h)
        </label>
        <label className="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={s.traktUnmonitorSkipSpecials ?? false}
            disabled={save.isPending || settings.isLoading}
            onChange={(e) => save.mutate({ traktUnmonitorSkipSpecials: e.target.checked })}
          />
          Skip specials (season 0)
        </label>
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <label htmlFor="protect-tag" className="text-sm opacity-80 whitespace-nowrap">
            Protect tag:
          </label>
          <input
            id="protect-tag"
            type="text"
            value={tagDraft}
            onChange={(e) => setTagDraft(e.target.value)}
            className="px-2 py-1 rounded text-sm bg-surface-3 border border-surface-2 min-w-0 flex-1"
          />
          <button
            type="button"
            disabled={!tagDirty || save.isPending}
            onClick={() => save.mutate({ traktUnmonitorProtectTag: tagDraft.trim() })}
            className="px-2 py-1 rounded text-sm bg-surface-3 hover:bg-surface-2 disabled:opacity-30 whitespace-nowrap"
          >
            {save.isPending ? 'Saving…' : 'Save tag'}
          </button>
        </div>
      </div>

      {sweep.error && (
        <div className="text-xs text-red-400 font-mono">
          {String((sweep.error as Error).message ?? sweep.error)}
        </div>
      )}
      {sweep.data && (
        <TraktUnmonitorReport report={sweep.data} showDetails={showDetails} setShowDetails={setShowDetails} />
      )}
    </div>
  )
}

function TraktUnmonitorReport({
  report, showDetails, setShowDetails,
}: {
  report: import('../hooks/queries').LibraryUnmonitorReport
  showDetails: boolean
  setShowDetails: (v: boolean) => void
}) {
  const verb = report.dryRun ? 'would unmonitor' : 'unmonitored'
  const contributors = report.perSeries.filter((p) => p.unmonitored.length > 0)
  const skipped = report.perSeries.filter((p) => p.skippedReason).length
  const errored = report.perSeries.filter((p) => p.errors.length > 0).length
  return (
    <div className="text-sm space-y-2 pt-2 border-t border-surface-3">
      <div>
        {report.totalSeries} series visited ·{' '}
        <span className="text-amber-400">{report.unmonitoredTotal}</span> episodes {verb}
        {skipped > 0 && <span className="opacity-70"> · {skipped} skipped</span>}
        {errored > 0 && <span className="text-red-400 ml-2"> · {errored} series with errors</span>}
        {' · '}
        <button type="button" className="underline text-xs opacity-80" onClick={() => setShowDetails(!showDetails)}>
          {showDetails ? 'hide details' : 'show details'}
        </button>
      </div>
      {showDetails && contributors.length > 0 && (
        <ul className="text-xs opacity-80 space-y-1 max-h-72 overflow-y-auto">
          {contributors.slice(0, 50).map((p) => (
            <li key={p.seriesId}>
              <strong>{p.title}</strong>: {p.unmonitored.length} ep
              {p.unmonitored.length > 1 ? 's' : ''}
              <span className="opacity-70">
                {' — '}
                {p.unmonitored.slice(0, 8).map((e) => `S${String(e.season).padStart(2, '0')}E${String(e.number).padStart(2, '0')}`).join(', ')}
                {p.unmonitored.length > 8 && ` … +${p.unmonitored.length - 8} more`}
              </span>
            </li>
          ))}
          {contributors.length > 50 && (
            <li className="opacity-50">… +{contributors.length - 50} more series</li>
          )}
        </ul>
      )}
    </div>
  )
}

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

