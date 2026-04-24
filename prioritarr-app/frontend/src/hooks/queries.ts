import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, API_BASE, getApiKey } from '../api/client'

interface PaginationInput {
  offset?: number
  limit?: number
  sort?: string
  sort_dir?: 'asc' | 'desc'
}

export function useSeriesList(p: PaginationInput = {}) {
  return useQuery({
    queryKey: ['series', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/series', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export function useSeries(id: number | null) {
  return useQuery({
    queryKey: ['series', id],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/series/{id}', {
        params: { path: { id: id! } },
      })
      if (error) throw error
      return data
    },
    enabled: id != null,
  })
}

export function useRecomputeSeries() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => {
      const { data, error } = await apiClient.POST('/api/v2/series/{id}/recompute', {
        params: { path: { id } },
      })
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['series'] }),
  })
}

export function useDownloads(p: PaginationInput & { client?: 'qbit' | 'sab' } = {}) {
  return useQuery({
    queryKey: ['downloads', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/downloads', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export interface DownloadLogEntry { ts: string; source: string; level: string; message: string }
export function useDownloadLogs(client: string, clientId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['download-logs', client, clientId],
    queryFn: () =>
      rawFetch<{ client: string; clientId: string; entries: DownloadLogEntry[] }>(
        `/api/v2/downloads/${client}/${encodeURIComponent(clientId)}/logs`,
      ),
    enabled,
  })
}

export function useDownloadAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { client: string; clientId: string; action: 'pause' | 'resume' | 'boost' | 'demote' }) => {
      const { data, error } = await apiClient.POST(
        '/api/v2/downloads/{client}/{clientId}/actions/{action}',
        { params: { path: v } },
      )
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
  })
}

export function useUntrackDownload() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { client: string; clientId: string }) => {
      const { data, error } = await apiClient.DELETE(
        '/api/v2/downloads/{client}/{clientId}',
        { params: { path: v } },
      )
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
  })
}

// Backend-polish PR (cquemin/prioritarr#21) adds POST /api/v2/downloads/bulk.
// openapi.json regen is separate, so we post via raw fetch for now.
export interface BulkDownloadItem { client: string; clientId: string }
export type BulkDownloadActionName = 'pause' | 'resume' | 'boost' | 'demote' | 'untrack'
export interface BulkItemResult { client: string; clientId: string; ok: boolean; message?: string }
export interface BulkDownloadActionResult {
  ok: boolean
  dryRun: boolean
  total: number
  succeeded: number
  failed: number
  results: BulkItemResult[]
}

export function useBulkDownloadAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { action: BulkDownloadActionName; items: BulkDownloadItem[] }): Promise<BulkDownloadActionResult> => {
      const key = getApiKey()
      const res = await fetch(`${API_BASE}/api/v2/downloads/bulk`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(key ? { 'X-Api-Key': key } : {}),
        },
        body: JSON.stringify(v),
      })
      if (!res.ok) {
        throw new Error(`bulk action failed: ${res.status} ${res.statusText}`)
      }
      return (await res.json()) as BulkDownloadActionResult
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
  })
}

export function useAudit(p: PaginationInput & { series_id?: number; action?: string; since?: string } = {}) {
  return useQuery({
    queryKey: ['audit', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/audit', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export function useSettings() {
  return useQuery({
    queryKey: ['settings'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/settings')
      if (error) throw error
      return data
    },
  })
}

// Operator-editable settings (URLs, API keys, dryRun, logLevel,
// uiOrigin). Persisted as a DB override on top of the env baseline;
// changes only take effect after a container restart because clients
// are constructed once at boot. Backend exposes:
//   GET    /api/v2/settings          -> redacted view
//   POST   /api/v2/settings          -> partial patch (this hook)
//   DELETE /api/v2/settings          -> clear override (Reset)
export interface EditableSettings {
  sonarrUrl?: string | null
  sonarrApiKey?: string | null
  tautulliUrl?: string | null
  tautulliApiKey?: string | null
  qbitUrl?: string | null
  qbitUsername?: string | null
  qbitPassword?: string | null
  sabUrl?: string | null
  sabApiKey?: string | null
  plexUrl?: string | null
  plexToken?: string | null
  traktClientId?: string | null
  traktAccessToken?: string | null
  dryRun?: boolean | null
  logLevel?: string | null
  uiOrigin?: string | null
}

export function useSaveSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: EditableSettings) =>
      rawFetch('/api/v2/settings', { method: 'POST', body: JSON.stringify(patch) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  })
}

// OrphanReaper — sweeps download folders for files no client tracks.
// One sweep returns a count + journal; the recurring backend job uses
// the same logic. UI uses these to surface kept-orphans needing
// operator review.
export interface OrphanReportEntry {
  action: string
  path: string
  sizeBytes: number
  reason: string
}
export interface OrphanReport {
  matched: number
  deleted: number
  imported: number
  importPending: number
  kept: number
  emptyDirsRemoved: number
  errors: number
  deletedBytes: number
  keptBytes: number
  entries: OrphanReportEntry[]
}
export interface OrphanAuditRow {
  id: number
  ts: string
  action: string
  details: {
    path?: string
    size_bytes?: number
    reason?: string
    folder?: string
    mtime?: string | null
  } | null
}

export function useOrphanSweep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (opts: { dryRun?: boolean } = {}) =>
      rawFetch<OrphanReport>(`/api/v2/orphans/sweep${opts.dryRun ? '?dryRun=true' : ''}`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orphans'] }),
  })
}

export interface OrphanBulkOutcome { path: string; ok: boolean; message?: string }
export interface OrphanBulkResult { total: number; succeeded: number; outcomes: OrphanBulkOutcome[] }
export interface OrphanProbeResult {
  ok: boolean
  canImport: boolean
  rejections: string[]
  seriesTitle?: string | null
  episodes?: string[]
  message?: string | null
}

export function useDeleteOrphans() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (paths: string[]) =>
      rawFetch<OrphanBulkResult>('/api/v2/orphans/delete', {
        method: 'POST', body: JSON.stringify({ paths }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orphans'] }),
  })
}

export function useRenameOrphan() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { path: string; newName: string }) =>
      rawFetch<{ ok: boolean; newPath?: string; message?: string }>(
        '/api/v2/orphans/rename', { method: 'POST', body: JSON.stringify(v) },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orphans'] }),
  })
}

export function useProbeOrphan() {
  return useMutation({
    mutationFn: (path: string) =>
      rawFetch<OrphanProbeResult>('/api/v2/orphans/probe', {
        method: 'POST', body: JSON.stringify({ path, newName: '' }),
      }),
  })
}

export function useImportOrphan() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (path: string) =>
      rawFetch<OrphanBulkOutcome>('/api/v2/orphans/import', {
        method: 'POST', body: JSON.stringify({ path, newName: '' }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orphans'] }),
  })
}

export function useOrphans(limit = 500) {
  return useQuery({
    queryKey: ['orphans', limit],
    queryFn: () => rawFetch<OrphanAuditRow[]>(`/api/v2/orphans?limit=${limit}`),
  })
}

export function useResetSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => rawFetch('/api/v2/settings', { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  })
}

export function useMappings() {
  return useQuery({
    queryKey: ['mappings'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/mappings')
      if (error) throw error
      return data
    },
  })
}

// Cross-source watch sync (Plex ⇆ Trakt). Posted via raw fetch — same
// reason as bulk above (openapi.json regen is its own PR).
export interface EpisodeRef { season: number; number: number }
export interface SeriesSyncReport {
  seriesId: number
  title: string
  plexAdded: number
  traktAdded: number
  pushedToPlex: EpisodeRef[]
  pushedToTrakt: EpisodeRef[]
  errors: string[]
  skippedReason: string | null
}
export interface LibrarySyncReport {
  ok: boolean
  dryRun: boolean
  totalSeries: number
  plexAddedTotal: number
  traktAddedTotal: number
  perSeries: SeriesSyncReport[]
}

async function postJson<T>(path: string): Promise<T> {
  const key = getApiKey()
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { ...(key ? { 'X-Api-Key': key } : {}) },
  })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

// Per-provider watch status for a single series. Drives the drawer's
// "watched on" breakdown and the "sync available / all in sync" gate.
export interface ProviderWatchStatus {
  source: string
  ok: boolean
  watchedEpisodeCount: number
  lastWatchedAt: string | null
  errorMessage?: string | null
}

export function useWatchStatus(seriesId: number | null) {
  return useQuery({
    queryKey: ['watch-status', seriesId],
    queryFn: () =>
      rawFetch<ProviderWatchStatus[]>(`/api/v2/series/${seriesId}/watch-status`),
    enabled: seriesId != null,
  })
}

export function useSeriesSync() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: number; dryRun?: boolean }) =>
      postJson<SeriesSyncReport>(`/api/v2/series/${v.id}/sync${v.dryRun ? '?dryRun=true' : ''}`),
    onSuccess: (_d, v) => {
      // Skip cache invalidation on dry-run — nothing actually changed.
      if (v.dryRun) return
      qc.invalidateQueries({ queryKey: ['series', v.id] })
      qc.invalidateQueries({ queryKey: ['series'] })
    },
  })
}

export function useLibrarySync() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (opts: { dryRun?: boolean; limit?: number } = {}) => {
      const params = new URLSearchParams()
      if (opts.dryRun) params.set('dryRun', 'true')
      if (opts.limit) params.set('limit', String(opts.limit))
      const qs = params.toString()
      return postJson<LibrarySyncReport>(`/api/v2/sync${qs ? `?${qs}` : ''}`)
    },
    onSuccess: (_d, opts) => {
      if (opts.dryRun) return
      qc.invalidateQueries({ queryKey: ['series'] })
    },
  })
}

// Priority thresholds — live editable via the Settings UI. POSTed via
// raw fetch for the same reason as bulk (openapi.json regen is separate).
export interface PriorityThresholds {
  p1WatchPctMin: number
  p1DaysSinceWatchMax: number
  p1DaysSinceReleaseMax: number
  p1HiatusGapDays: number
  p1HiatusReleaseWindowDays: number
  p2WatchPctMin: number
  p2DaysSinceWatchMax: number
  p3WatchPctMin: number
  p3UnwatchedMax: number
  p3DaysSinceWatchMax: number
  p4MinWatched: number
  p5WhenNothingToDownload: boolean
  p3DormantReleaseWindowDays: number
}

async function rawFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const key = getApiKey()
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(key ? { 'X-Api-Key': key } : {}),
      ...(init.headers ?? {}),
    },
  })
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} failed: ${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

export function useThresholds() {
  return useQuery({
    queryKey: ['thresholds'],
    queryFn: () => rawFetch<PriorityThresholds>('/api/v2/settings/thresholds'),
  })
}

export function useSaveThresholds() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (next: PriorityThresholds) =>
      rawFetch<PriorityThresholds>('/api/v2/settings/thresholds', {
        method: 'POST',
        body: JSON.stringify(next),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['thresholds'] })
      qc.invalidateQueries({ queryKey: ['series'] })
    },
  })
}

export function useResetThresholds() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () =>
      rawFetch<PriorityThresholds>('/api/v2/settings/thresholds', {
        method: 'DELETE',
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['thresholds'] })
      qc.invalidateQueries({ queryKey: ['series'] })
    },
  })
}

export interface PriorityResultWire {
  priority: number
  label: string
  reason: string
}

export interface ManagedDownloadPreview {
  client: string
  clientId: string
  currentPriority: number
  currentlyPausedByUs: boolean
  wouldBePaused: boolean
}

export interface PriorityPreviewEntry {
  seriesId: number
  title: string
  monitoredSeasons: number
  monitoredEpisodesAired: number
  monitoredEpisodesWatched: number
  unwatched: number
  watchPct: number
  daysSinceWatch: number | null
  daysSinceRelease: number | null
  previous: PriorityResultWire | null
  preview: PriorityResultWire
  downloads: ManagedDownloadPreview[]
}

export interface PriorityPreviewResponse {
  thresholds: PriorityThresholds
  entries: PriorityPreviewEntry[]
}

export function usePriorityPreview() {
  // Not a useQuery because it's a POST with a body — the body's a
  // user-tweakable patch, so caching by queryKey would be a mess.
  // Callers call mutate({seriesIds, thresholds}) whenever inputs change.
  return useMutation({
    mutationFn: (v: { seriesIds: number[]; thresholds: Partial<PriorityThresholds> }) =>
      rawFetch<PriorityPreviewResponse>('/api/v2/priority/preview', {
        method: 'POST',
        body: JSON.stringify(v),
      }),
  })
}

// Global search across series titles + monitored episode titles.
export interface MatchedEpisode {
  season: number
  number: number
  title: string
}
export interface SearchHit {
  seriesId: number
  title: string
  matchedBy: 'title' | 'episode'
  matchedEpisode: MatchedEpisode | null
}
export interface SearchResponse {
  query: string
  hits: SearchHit[]
}

export function useSearch(query: string) {
  return useQuery({
    queryKey: ['search', query],
    queryFn: () =>
      rawFetch<SearchResponse>(`/api/v2/search?q=${encodeURIComponent(query)}`),
    // Below 2 chars the backend returns empty; skip the roundtrip.
    enabled: query.trim().length >= 2,
  })
}

export function useRefreshMappings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const { data, error } = await apiClient.POST('/api/v2/mappings/refresh')
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mappings'] }),
  })
}
