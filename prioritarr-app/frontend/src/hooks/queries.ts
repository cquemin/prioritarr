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
    mutationFn: (opts: { dryRun?: boolean } = {}) =>
      postJson<LibrarySyncReport>(`/api/v2/sync${opts.dryRun ? '?dryRun=true' : ''}`),
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
