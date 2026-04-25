/**
 * Background-jobs catalog. Single source of truth for the
 * Settings → Background jobs page. Each entry describes one logical
 * job: how it's triggered, where its cadence lives in EditableSettings,
 * and which specific knobs the job's detail page should expose.
 *
 * Jobs are grouped by trigger pattern, which drives the card colour:
 *   - auto+manual: scheduled AND has a manual run button
 *   - auto-only:   scheduled, no manual entry point
 *   - event:       fired by upstream webhook, not on a clock
 *   - manual-only: tool, no schedule (e.g. cross-source sync)
 *
 * Cadence keys point at fields on EditableSettings; the corresponding
 * baseline value comes from the SettingsRedacted GET response (under
 * `intervals.*` for the nested ones, or top-level for the rest).
 */

import {
  RefreshCw, Database, Search, Activity, Trash2, ListChecks, KeyRound, Cog,
  Webhook, ArrowLeftRight, Box, FileSearch,
} from 'lucide-react'
import type { ReactNode } from 'react'

export type JobTrigger = 'auto+manual' | 'auto' | 'event' | 'manual'

/**
 * Default name for the Sonarr tag that exempts a series from the
 * Trakt→Sonarr unmonitor reconciler. Mirrors the backend
 * `DEFAULT_PROTECT_TAG` constant — keep in sync.
 */
export const DEFAULT_PROTECT_TAG = 'prioritarr-no-unmonitor'

export interface JobMeta {
  /** URL slug — `#/settings/jobs/<id>`. */
  id: string
  name: string
  icon: ReactNode
  trigger: JobTrigger
  /** Tag-line for the card. */
  short: string
  /** What the job does (paragraph). */
  description: string
  /** Why it exists + a short worked example. */
  why?: string
  /** Cadence: where the field lives, what the unit is. Omitted for event/manual jobs. */
  cadence?: {
    /** Path into EditableSettings — `'intervals.reconcileMinutes'` or `'orphanReaperIntervalMinutes'`. */
    key: string
    unit: 'minutes' | 'hours'
    /** Lowest sane value for the input. Most jobs go off the rails below ~1m. */
    min?: number
  }
  /** Optional manual trigger. The frontend renders a button + invokes this endpoint. */
  manual?: {
    /** Method always POST in v1. */
    method: 'POST'
    /** Absolute path (no /api/v2 prefix — added by the hook). */
    path: string
    /** Query params merged onto the URL (e.g. dryRun=true). */
    query?: Record<string, string>
    /** Button label. */
    label: string
  }
  /** Job-specific scalar settings (cadence excluded — that one's always present). */
  settings?: Array<JobSetting>
  /** Cross-cutting settings that affect this job; rendered as links. */
  relatedSettings?: Array<{ section: string; sectionLabel: string; field: string }>
}

export type JobSetting =
  | { key: string; label: string; type: 'number'; min?: number; step?: number; hint?: string }
  | { key: string; label: string; type: 'boolean'; hint?: string }
  | { key: string; label: string; type: 'string'; hint?: string }

export const JOBS: ReadonlyArray<JobMeta> = [
  {
    id: 'reconcile',
    name: 'Reconcile (qBit + SAB)',
    icon: <Activity size={18} />,
    trigger: 'auto',
    short: 'Pause lower-priority torrents when higher ones need bandwidth.',
    description:
      'The core enforcement loop. Walks every active download in qBit and SAB, computes whether each should currently be running, and pauses or resumes accordingly. Bandwidth-aware: respects the line cap, peak-hour profile, and quiet-mode cap.',
    why:
      'Without this loop, Sonarr happily downloads everything in parallel and saturates the line. With it, P1 episodes always grab the bandwidth ahead of P5 backfill — and the policy adapts automatically to whether the line is actually busy.',
    cadence: { key: 'intervals.reconcileMinutes', unit: 'minutes', min: 1 },
    relatedSettings: [
      { section: 'general', sectionLabel: 'General', field: 'dryRun' },
      { section: 'bandwidth', sectionLabel: 'Bandwidth', field: '*' },
    ],
  },
  {
    id: 'refresh-priorities',
    name: 'Refresh priorities',
    icon: <RefreshCw size={18} />,
    trigger: 'auto',
    short: 'Recompute priority for every monitored series.',
    description:
      'Walks every monitored Sonarr series and re-runs the priority computation. The priority cache TTL keeps the upstream traffic bounded; this job exists so the UI has a current priority chip even for series with no recent webhook activity.',
    cadence: { key: 'intervals.refreshPrioritiesMinutes', unit: 'minutes', min: 1 },
  },
  {
    id: 'refresh-series-cache',
    name: 'Refresh series cache',
    icon: <Database size={18} />,
    trigger: 'auto',
    short: 'Local cache of Sonarr\u2019s series list.',
    description:
      'Pulls the full Sonarr series list and stores it locally so the Series page renders without an upstream call (a 7s round-trip on a ~450-series library). New shows added in Sonarr appear in prioritarr within one tick.',
    cadence: { key: 'intervals.refreshSeriesCacheMinutes', unit: 'minutes', min: 1 },
  },
  {
    id: 'refresh-episode-cache',
    name: 'Refresh episode cache',
    icon: <Search size={18} />,
    trigger: 'auto',
    short: 'Episode-title cache for global search.',
    description:
      'Pulls every episode title for every series so the global search box can match against episode names without hitting Sonarr per keystroke. Lower cadence than series cache because titles rarely churn.',
    cadence: { key: 'intervals.refreshEpisodeCacheMinutes', unit: 'minutes', min: 5 },
  },
  {
    id: 'refresh-mappings',
    name: 'Refresh mappings',
    icon: <ArrowLeftRight size={18} />,
    trigger: 'auto+manual',
    short: 'Plex \u2194 Sonarr id resolution table.',
    description:
      'Maps Plex rating-keys to Sonarr series ids by walking the Plex library and joining on TVDB id (with title fallback for shows missing TVDB metadata). Watch-history webhooks rely on this mapping to find the right Sonarr series.',
    cadence: { key: 'intervals.refreshMappingsMinutes', unit: 'minutes', min: 5 },
    manual: { method: 'POST', path: '/api/v2/mappings/refresh', label: 'Refresh now' },
  },
  {
    id: 'queue-janitor',
    name: 'Queue janitor',
    icon: <ListChecks size={18} />,
    trigger: 'auto',
    short: 'Clean stuck downloads + blocklist bad releases.',
    description:
      'Detects torrents with no activity for >48h and SAB jobs stuck in failed/post-processing limbo. Removes them from the client, blocklists the release in Sonarr, and triggers a re-search in priority order.',
    cadence: { key: 'intervals.queueJanitorMinutes', unit: 'minutes', min: 1 },
  },
  {
    id: 'unmonitored-reaper',
    name: 'Unmonitored-queue reaper',
    icon: <Trash2 size={18} />,
    trigger: 'auto',
    short: 'Cancel queue entries for unmonitored episodes.',
    description:
      'When you flip an episode\u2019s monitored=false in Sonarr, any in-flight download for it should stop. This job sweeps the Sonarr queue for entries whose series/season/episode is no longer monitored and cancels + blocklists them.',
    cadence: { key: 'intervals.unmonitoredReaperMinutes', unit: 'minutes', min: 1 },
  },
  {
    id: 'trakt-unmonitor',
    name: 'Trakt \u2192 Sonarr unmonitor',
    icon: <Cog size={18} />,
    trigger: 'auto+manual',
    short: 'Unmonitor episodes you\u2019ve already watched on Trakt.',
    description:
      'For each Sonarr series, fetches Trakt watched-episode set; for any episode marked watched on Trakt that is still monitored in Sonarr without a file, flips monitored=false. Never touches downloaded episodes (that\u2019s the watched archiver\u2019s job).',
    why:
      'Imagine you watched seasons 1\u20133 of a show on someone else\u2019s account on Trakt. Without this job, Sonarr keeps grinding to download every missing episode \u2014 wasting bandwidth on stuff you\u2019ll never watch on this server. With it, Sonarr stops chasing those episodes.',
    cadence: { key: 'traktUnmonitorIntervalHours', unit: 'hours', min: 1 },
    manual: {
      method: 'POST', path: '/api/v2/reconcile/trakt-unmonitor', label: 'Run sweep now',
    },
    settings: [
      { key: 'traktUnmonitorEnabled', label: 'Enabled', type: 'boolean', hint: 'Off by default. The manual trigger ignores this flag.' },
      { key: 'traktUnmonitorSkipSpecials', label: 'Skip specials (S00)', type: 'boolean', hint: 'Skip season 0 entirely. Default off.' },
      { key: 'traktUnmonitorProtectTag', label: 'Protect tag', type: 'string', hint: `Sonarr tag whose presence excludes a series from this job. Defaults to ${DEFAULT_PROTECT_TAG}.` },
    ],
  },
  {
    id: 'trakt-token-refresh',
    name: 'Trakt token refresh',
    icon: <KeyRound size={18} />,
    trigger: 'auto',
    short: 'Proactively rotate Trakt tokens before expiry.',
    description:
      'Trakt issues 90-day tokens. This job checks daily and refreshes when expiry is within 7 days. If the refresh fails because the refresh_token is also stale, tokens are wiped so the UI flips back to "Connect Trakt".',
    cadence: { key: 'intervals.traktTokenRefreshHours', unit: 'hours', min: 1 },
    relatedSettings: [
      { section: 'connections', sectionLabel: 'Connections', field: 'Trakt' },
    ],
  },
  {
    id: 'watched-archiver',
    name: 'Watched archiver',
    icon: <Trash2 size={18} />,
    trigger: 'auto+manual',
    short: 'Delete watched episodes outside the keep window.',
    description:
      'Walks the library, finds watched episodes that fall outside the keep window (latest season \u2014 or last N episodes when that season is large), unmonitors them in Sonarr, then deletes the file. Unmonitor-before-delete prevents the backfill sweep from immediately re-grabbing the same episode.',
    cadence: { key: 'archiveIntervalHours', unit: 'hours', min: 1 },
    manual: { method: 'POST', path: '/api/v2/archive/sweep', label: 'Run sweep now', query: { dryRun: 'true' } },
  },
  {
    id: 'orphan-reaper',
    name: 'Orphan reaper',
    icon: <Box size={18} />,
    trigger: 'auto+manual',
    short: 'Sweep download folders for files Sonarr/SAB no longer track.',
    description:
      'Walks the configured download paths, collates files Sonarr and SAB no longer reference, auto-imports the importable, deletes the hardlink-twins + "not an upgrade" cases, and queues anything ambiguous for the operator to action via Settings \u2192 Orphans.',
    cadence: { key: 'orphanReaperIntervalMinutes', unit: 'minutes', min: 5 },
    manual: { method: 'POST', path: '/api/v2/orphans/sweep', label: 'Run sweep now' },
  },
  {
    id: 'backfill-sweep',
    name: 'Backfill sweep',
    icon: <FileSearch size={18} />,
    trigger: 'auto',
    short: 'Search for high-priority missing episodes.',
    description:
      'Picks the top N missing-monitored-aired episodes (sorted by priority then air date) and asks Sonarr to search each. Throttled by the per-search delay so we never run a Sonarr indexer test against a hundred releases at once.',
    cadence: { key: 'intervals.backfillSweepHours', unit: 'hours', min: 1 },
    settings: [
      { key: 'backfillMaxSearchesPerSweep', label: 'Max searches per sweep', type: 'number', min: 1, step: 1 },
      { key: 'backfillDelayBetweenSearchesSeconds', label: 'Delay between searches (sec)', type: 'number', min: 0, step: 1 },
    ],
  },
  {
    id: 'cutoff-sweep',
    name: 'Cutoff sweep',
    icon: <FileSearch size={18} />,
    trigger: 'auto',
    short: 'Search for upgrade candidates.',
    description:
      'Asks Sonarr for the cutoff-unmet list (episodes whose current quality is below the profile target) and triggers searches for the top N. Same throttling shape as backfill sweep.',
    cadence: { key: 'intervals.cutoffSweepHours', unit: 'hours', min: 1 },
    settings: [
      { key: 'cutoffMaxSearchesPerSweep', label: 'Max searches per sweep', type: 'number', min: 1, step: 1 },
    ],
  },
  {
    id: 'library-sync',
    name: 'Plex \u21c4 Trakt sync',
    icon: <ArrowLeftRight size={18} />,
    trigger: 'manual',
    short: 'Mirror watch state between Plex and Trakt (manual-only).',
    description:
      'Cross-source watch sync: anything Plex has watched but Trakt doesn\u2019t gets POSTed to Trakt /sync/history; anything Trakt has watched but Plex doesn\u2019t gets scrobbled to Plex. No clock \u2014 you trigger it explicitly. Direction selector lets you pick one-way mode.',
    manual: { method: 'POST', path: '/api/v2/sync', label: 'Run library sync' },
  },
  {
    id: 'sonarr-webhook',
    name: 'Sonarr webhook',
    icon: <Webhook size={18} />,
    trigger: 'event',
    short: 'Inbound: grab / download / file delete / series delete events.',
    description:
      'Receives Sonarr\u2019s outbound webhook on every grab, import, episode-file delete, series delete, and manual-interaction-required event. Routes by eventType: grabs are recorded with priority; deletes invalidate caches and push SSE so any open drawer refreshes.',
    relatedSettings: [
      { section: 'webhooks', sectionLabel: 'Webhooks', field: 'sonarr-on-grab' },
    ],
  },
  {
    id: 'sab-webhook',
    name: 'SABnzbd webhook',
    icon: <Webhook size={18} />,
    trigger: 'event',
    short: 'Inbound: SAB post-processing notifications.',
    description:
      'Fired by SABnzbd\u2019s post-processing script with `nzo_id`, `status` (Completed / Failed), and an optional `fail_message`. Pushes a typed SSE event so the UI can flip download state immediately rather than waiting for the next reconcile tick.',
    relatedSettings: [
      { section: 'webhooks', sectionLabel: 'Webhooks', field: 'sab-webhook' },
    ],
  },
  {
    id: 'plex-webhook',
    name: 'Plex / Tautulli webhook',
    icon: <Webhook size={18} />,
    trigger: 'event',
    short: 'Inbound: episode-watched events from Tautulli.',
    description:
      'Tautulli posts a watched event when you finish an episode. Looks up the Sonarr series via the Plex \u2194 Sonarr mapping table, persists the watch event, and triggers a priority recompute for that series.',
    relatedSettings: [
      { section: 'webhooks', sectionLabel: 'Webhooks', field: 'plex-event' },
    ],
  },
]

/* ---- Trigger → colour helpers. Used by the grid + the detail badge. ---- */

export const TRIGGER_LABEL: Record<JobTrigger, string> = {
  'auto+manual': 'Scheduled · manual',
  'auto': 'Scheduled',
  'event': 'Event-driven',
  'manual': 'Manual',
}

/** Tailwind border + chip classes per trigger. Border drives the card colour. */
export const TRIGGER_CLASS: Record<JobTrigger, { border: string; chip: string; dot: string }> = {
  'auto+manual': { border: 'border-l-4 border-l-green-500',  chip: 'bg-green-600/20 text-green-300',  dot: 'bg-green-500' },
  'auto':         { border: 'border-l-4 border-l-blue-500',   chip: 'bg-blue-600/20 text-blue-300',    dot: 'bg-blue-500' },
  'event':        { border: 'border-l-4 border-l-amber-500',  chip: 'bg-amber-600/20 text-amber-300',  dot: 'bg-amber-500' },
  'manual':       { border: 'border-l-4 border-l-zinc-500',   chip: 'bg-zinc-600/20 text-zinc-300',    dot: 'bg-zinc-500' },
}

/**
 * Read a dotted-path key from a SettingsRedacted-shaped object.
 * Used to fetch the current cadence value for display + editing.
 */
export function readPath(obj: any, path: string): any {
  return path.split('.').reduce((acc, k) => (acc == null ? acc : acc[k]), obj)
}

/**
 * Build the EditableSettings patch for saving a cadence change.
 * Handles both the flat (`orphanReaperIntervalMinutes`) and nested
 * (`intervals.reconcileMinutes`) shapes — at the EditableSettings
 * level both are flat keys, so the nested path's last segment is
 * the actual key.
 */
export function buildCadencePatch(path: string, value: number): Record<string, number> {
  const parts = path.split('.')
  const flatKey = parts.length === 1 ? parts[0] : parts[parts.length - 1]
  return { [flatKey]: value }
}

/** Constant-time lookup by id. */
export const JOBS_BY_ID: Record<string, JobMeta> = Object.fromEntries(JOBS.map((j) => [j.id, j]))

/** Stable display order on the grid: green → blue → amber → zinc. */
export const JOB_DISPLAY_ORDER: JobTrigger[] = ['auto+manual', 'auto', 'event', 'manual']
