/**
 * Unified Series + Downloads browser. One row per series; download
 * state (client list, paused indicator) collapses in as columns. Row
 * click opens a drawer with everything: priority detail, external
 * deep-links (Sonarr / Trakt / Tautulli / qBit / SAB), and the
 * per-managed-download breakdown with inline pause/resume/boost/
 * demote/untrack — so the drawer answers "why is Dr Stone paused?"
 * in one glance.
 */

import { type ColumnDef } from '@tanstack/react-table'
import { ExternalLink, Search, X } from 'lucide-react'
import React, { useEffect, useMemo, useState } from 'react'

import { DataTable } from '../components/DataTable'
import { KebabMenu } from '../components/KebabMenu'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import {
  useDownloadAction,
  useDownloadLogs,
  useRecomputeSeries,
  useSearch,
  useSeries,
  useSeriesList,
  useSeriesSync,
  useUntrackDownload,
  useWatchStatus,
  type ProviderWatchStatus,
  type SearchHit,
} from '../hooks/queries'
import { navigate, useRoute } from '../hooks/useHashRoute'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'

interface SeriesRow {
  id: number
  title: string
  titleSlug?: string | null
  tvdbId?: number | null
  priority?: number | null
  label?: string | null
  reason?: string | null
  computedAt?: string | null
  managedDownloadCount: number
  clients: string[]
  pausedCount: number
}

export function SeriesPage() {
  const { data, isLoading, error } = useSeriesList({ limit: 500 })
  const recompute = useRecomputeSeries()
  // Drawer open/close is driven by the URL — `#/series/123` opens the
  // drawer for series 123; closing reverts to `#/series`. Browser
  // Back/Forward just works.
  const route = useRoute()
  const openSeriesId = route.page === 'series' ? route.seriesId : null

  // Global search — matches series title OR episode name. Debounced so
  // typing fast doesn't spam the /search endpoint.
  const [searchInput, setSearchInput] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setSearchQuery(searchInput.trim()), 300)
    return () => clearTimeout(t)
  }, [searchInput])
  const search = useSearch(searchQuery)
  const searchHitsBySeriesId = useMemo(() => {
    const map = new Map<number, SearchHit>()
    for (const h of search.data?.hits ?? []) map.set(h.seriesId, h)
    return map
  }, [search.data])
  const isSearching = searchQuery.length >= 2

  if (isLoading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-4">Series</h1>
        <TableSkeleton rows={12} cols={7} />
      </div>
    )
  }
  if (error) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-4">Series</h1>
        <div className="bg-red-900/20 border border-red-700/60 rounded-lg p-4 text-red-300 text-sm">
          <strong>Failed to load series.</strong>
          <div className="mt-1 font-mono text-xs opacity-80">{String(error)}</div>
        </div>
      </div>
    )
  }

  const allRows = ((data?.records as unknown) as SeriesRow[]) ?? []
  const rows = isSearching
    ? allRows.filter((r) => searchHitsBySeriesId.has(r.id))
    : allRows

  // Responsive layout:
  //  - mobile (< sm): Priority chip + Title (with inline "N dl / N
  //    paused" chips) + kebab. Extra columns hidden to keep the
  //    table readable without horizontal scroll.
  //  - sm+: full desktop set with separate Client / Paused / Cached /
  //    Downloads columns. Inline chips under the title hide so
  //    they don't duplicate the dedicated columns.
  const columns: ColumnDef<SeriesRow>[] = [
    {
      accessorKey: 'priority',
      header: 'P',
      cell: ({ row }) => {
        const p = row.original.priority
        if (!p) return <span className="opacity-40">—</span>
        const tip = row.original.reason
          ? `${PRIORITY_LABELS[p]} — ${row.original.reason}`
          : PRIORITY_LABELS[p]
        return (
          <span className={`px-2 py-0.5 rounded text-xs border ${PRIORITY_CLASS[p]}`} title={tip}>
            P{p}
          </span>
        )
      },
      sortingFn: (a, b) => (a.original.priority ?? 99) - (b.original.priority ?? 99),
      meta: { cellClassName: 'w-[1%] whitespace-nowrap' },
    },
    {
      accessorKey: 'title',
      header: 'Series',
      cell: ({ row }) => {
        const r = row.original
        const hit = searchHitsBySeriesId.get(r.id)
        const matched = hit?.matchedEpisode
        const chips: string[] = []
        if (r.managedDownloadCount > 0) chips.push(`${r.managedDownloadCount} dl`)
        if (r.pausedCount > 0) chips.push(`${r.pausedCount} paused`)
        return (
          <div className="space-y-0.5 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-medium break-words">{r.title}</span>
              {/* Inline chips only on mobile — on desktop the dedicated
                  columns below surface the same info without duplication. */}
              {chips.map((c) => (
                <span key={c} className="px-1.5 py-0.5 text-[10px] bg-surface-3 rounded opacity-70 sm:hidden">{c}</span>
              ))}
            </div>
            {matched && (
              <div className="text-xs opacity-70 break-words">
                <span className="font-mono">S{String(matched.season).padStart(2, '0')}E{String(matched.number).padStart(2, '0')}</span>
                <span className="mx-1 opacity-50">·</span>
                <span className="italic">{matched.title}</span>
              </div>
            )}
          </div>
        )
      },
      filterFn: 'includesString',
    },
    {
      id: 'clients',
      accessorFn: (r) => r.clients.join(','),
      header: 'Client',
      meta: { cellClassName: 'hidden sm:table-cell w-[1%] whitespace-nowrap' },
      cell: ({ row }) => {
        const cs = row.original.clients
        if (cs.length === 0) return <span className="opacity-40">—</span>
        return (
          <div className="flex gap-1">
            {cs.map((c) => (
              <span key={c} className="px-1.5 py-0.5 text-xs bg-surface-3 rounded font-mono">{c}</span>
            ))}
          </div>
        )
      },
      filterFn: 'includesString',
    },
    {
      id: 'paused',
      header: 'Paused',
      meta: { cellClassName: 'hidden md:table-cell w-[1%] whitespace-nowrap' },
      cell: ({ row }) => {
        const { managedDownloadCount: n, pausedCount: p } = row.original
        if (n === 0) return <span className="opacity-40">—</span>
        if (p === 0) return <span className="opacity-60">no</span>
        if (p === n) return <span className="text-amber-400">all ({p})</span>
        return <span className="text-amber-400">{p} of {n}</span>
      },
      sortingFn: (a, b) => a.original.pausedCount - b.original.pausedCount,
    },
    {
      accessorKey: 'managedDownloadCount',
      header: 'Downloads',
      meta: { cellClassName: 'hidden lg:table-cell w-[1%] whitespace-nowrap' },
      cell: ({ row }) => <span>{row.original.managedDownloadCount}</span>,
    },
    {
      accessorKey: 'computedAt',
      header: 'Cached',
      meta: { cellClassName: 'hidden lg:table-cell w-[1%] whitespace-nowrap' },
      cell: ({ row }) => (
        <span className="opacity-70 text-xs">
          {row.original.computedAt?.slice(0, 19) ?? '—'}
        </span>
      ),
    },
    {
      id: '__actions',
      header: '',
      enableSorting: false,
      enableColumnFilter: false,
      meta: { cellClassName: 'w-[1%] whitespace-nowrap' },
      cell: ({ row }) => (
        <KebabMenu
          items={[
            {
              label: 'Recompute priority',
              onSelect: () => recompute.mutate(row.original.id),
              disabled: recompute.isPending,
            },
            { label: 'View detail', onSelect: () => navigate({ page: 'series', seriesId: row.original.id }) },
          ]}
        />
      ),
    },
  ]

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">
          Series ({isSearching ? `${rows.length} of ${allRows.length}` : data?.totalRecords ?? 0})
        </h1>
        <p className="text-xs text-text-muted">
          Click a column header to sort · type in a header field to filter · click a row for details + external links
        </p>
      </div>

      <div className="mb-4 relative">
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 opacity-60 pointer-events-none" />
        <input
          type="search"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder="Search series title or episode name… (≥ 2 chars)"
          className="w-full pl-9 pr-9 py-2 bg-surface-1 border border-surface-3 rounded text-sm focus:outline-none focus:ring-1 focus:ring-accent"
        />
        {searchInput && (
          <button
            type="button"
            onClick={() => setSearchInput('')}
            className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded hover:bg-surface-3"
            aria-label="Clear search"
          >
            <X size={14} />
          </button>
        )}
        {isSearching && search.isFetching && (
          <span className="absolute right-10 top-1/2 -translate-y-1/2 text-xs opacity-60">
            searching…
          </span>
        )}
      </div>

      <DataTable
        data={rows}
        columns={columns}
        rowKey={(r) => r.id}
        onRowClick={(r) => navigate({ page: 'series', seriesId: r.id })}
        emptyMessage="No series — Sonarr library is empty or series cache hasn't refreshed yet."
      />

      {openSeriesId != null && (() => {
        // The row may not be in the currently-filtered list (e.g. user
        // navigated directly to a series by URL). Fall back to the
        // unfiltered full list so the drawer always has a row.
        const row = allRows.find((r) => r.id === openSeriesId)
        if (!row) return null
        return (
          <SeriesDetailDrawer
            row={row}
            onClose={() => navigate({ page: 'series' })}
            onRecompute={() => recompute.mutate(openSeriesId)}
            isRecomputing={recompute.isPending}
          />
        )
      })()}
    </div>
  )
}

interface ExternalLinksDto {
  sonarr?: string | null
  trakt?: string | null
  tautulli?: string | null
  plex?: string | null
  qbit?: string | null
  sab?: string | null
}

interface DownloadDto {
  client: 'qbit' | 'sab'
  clientId: string
  seriesId: number
  seriesTitle?: string | null
  episodeIds: number[]
  initialPriority: number
  currentPriority: number
  pausedByUs: boolean
  firstSeenAt: string
  lastReconciledAt: string
  liveState?: string | null
  liveErrorMessage?: string | null
  clientUrl?: string | null
}


function SeriesDetailDrawer({
  row, onClose, onRecompute, isRecomputing,
}: {
  row: SeriesRow
  onClose: () => void
  onRecompute: () => void
  isRecomputing: boolean
}) {
  const detail = useSeries(row.id)
  const d = detail.data as any
  const links: ExternalLinksDto | undefined = d?.externalLinks
  const downloads: DownloadDto[] = (d?.downloads as DownloadDto[]) ?? []
  const summary = d?.summary as (SeriesRow | undefined)
  const sonarrUrl = links?.sonarr ?? undefined

  const action = useDownloadAction()
  const untrack = useUntrackDownload()
  const sync = useSeriesSync()
  const watchStatus = useWatchStatus(row.id)

  // Sync button should only show when a delta exists between watch
  // providers. If every source reports the same episode count we're
  // already in sync; the button is replaced with a short "synchronised"
  // info text instead.
  const okStatuses = (watchStatus.data ?? []).filter((s) => s.ok)
  const syncNeeded = okStatuses.length > 1 && new Set(okStatuses.map((s) => s.watchedEpisodeCount)).size > 1

  const titleNode = sonarrUrl ? (
    <a
      href={sonarrUrl}
      target="_blank"
      rel="noreferrer"
      className="hover:text-accent inline-flex items-center gap-1"
    >
      {row.title}
      <ExternalLink size={14} className="opacity-60" />
    </a>
  ) : (
    <>{row.title}</>
  )

  return (
    <RowDrawer
      isOpen
      onClose={onClose}
      title={row.title}
      subtitle={`Series ID ${row.id}${row.tvdbId ? ` · TVDB ${row.tvdbId}` : ''}`}
      footer={
        <>
          <button
            type="button"
            onClick={onRecompute}
            disabled={isRecomputing}
            className="px-3 py-1.5 rounded bg-accent text-white text-sm disabled:opacity-50"
          >
            {isRecomputing ? 'Recomputing…' : 'Recompute priority'}
          </button>
          {syncNeeded ? (
            <button
              type="button"
              onClick={() => sync.mutate({ id: row.id })}
              disabled={sync.isPending}
              className="px-3 py-1.5 rounded bg-surface-3 text-sm disabled:opacity-50"
              title="Push missing episodes across Plex ⇆ Trakt so both sides agree"
            >
              {sync.isPending ? 'Syncing…' : 'Sync Plex ⇆ Trakt'}
            </button>
          ) : okStatuses.length > 0 ? (
            <span className="px-3 py-1.5 text-xs text-green-400 opacity-80 self-center">
              ✓ All sources synchronised
            </span>
          ) : null}
          <button
            type="button"
            onClick={onClose}
            className="px-3 py-1.5 rounded bg-surface-3 text-sm"
          >
            Close
          </button>
        </>
      }
    >
      <DetailField label="Title">
        <span className="text-base">{titleNode}</span>
      </DetailField>

      <DetailField label="Priority">
        {row.priority ? (
          <span className={`px-2 py-0.5 rounded text-xs border ${PRIORITY_CLASS[row.priority]}`}>
            {PRIORITY_LABELS[row.priority]}
          </span>
        ) : (
          <span className="opacity-60">not computed yet</span>
        )}
      </DetailField>

      <DetailField label="Why this priority">
        <PriorityReason reason={row.reason ?? d?.priority?.reason ?? null} loading={detail.isLoading} />
      </DetailField>

      <DetailField label="Watched on">
        <WatchStatusTable data={watchStatus.data ?? null} loading={watchStatus.isLoading} />
      </DetailField>

      <DetailField label="Cache expires">
        <span className="opacity-80 text-xs">{d?.cacheExpiresAt?.slice(0, 19) ?? '—'}</span>
      </DetailField>

      {/* Below this line is data that only arrives from the detail
          endpoint. Show a pulsing skeleton while it's in flight so
          the user sees there's more coming. */}
      {detail.isLoading && !d && <DrawerLoadingSkeleton />}

      {downloads.length > 0 && (
        <DetailField label={`Downloads (${downloads.length})`}>
          <div className="space-y-2">
            {downloads.map((dl) => (
              <DownloadCard
                key={`${dl.client}-${dl.clientId}`}
                dl={dl}
                isPending={action.isPending || untrack.isPending}
                onAction={(a) => action.mutate({ client: dl.client, clientId: dl.clientId, action: a })}
                onUntrack={() => {
                  if (!confirm('Untrack this download? It will be left alone in the client.')) return
                  untrack.mutate({ client: dl.client, clientId: dl.clientId })
                }}
              />
            ))}
          </div>
        </DetailField>
      )}

      {links && (links.sonarr || links.trakt || links.tautulli || links.plex || links.qbit || links.sab) && (
        <DetailField label="Open in">
          <div className="flex flex-wrap gap-2">
            <LinkBadge href={links.sonarr} label="Sonarr" />
            <LinkBadge href={links.trakt} label="Trakt" />
            <LinkBadge href={links.tautulli} label="Tautulli" />
            <LinkBadge href={links.plex} label="Plex" />
            <LinkBadge href={links.qbit} label="qBittorrent" />
            <LinkBadge href={links.sab} label="SABnzbd" />
          </div>
        </DetailField>
      )}

      {d?.recentAudit?.length > 0 && (
        <DetailField label="Recent activity">
          <ul className="text-xs space-y-1 opacity-80">
            {d.recentAudit.slice(0, 10).map((a: any) => (
              <li key={a.id} className="font-mono">
                {a.ts.slice(0, 19)} · {a.action}
                {a.client && <span className="opacity-60"> · {a.client}/{a.clientId?.slice(0, 10)}</span>}
              </li>
            ))}
          </ul>
        </DetailField>
      )}

      {(sync.data || sync.error) && (
        <DetailField label="Last sync result">
          {sync.error ? (
            <span className="text-red-400 font-mono text-xs">
              {String((sync.error as Error).message ?? sync.error)}
            </span>
          ) : sync.data ? (
            <div className="text-xs space-y-1">
              {sync.data.skippedReason ? (
                <span className="text-amber-400">Skipped — {sync.data.skippedReason}</span>
              ) : (
                <>
                  <div>
                    <span className="text-text-muted">Plex added:</span>{' '}
                    <span className={sync.data.plexAdded > 0 ? 'text-green-400' : 'opacity-60'}>
                      {sync.data.plexAdded}
                    </span>
                    <span className="mx-3 opacity-30">·</span>
                    <span className="text-text-muted">Trakt added:</span>{' '}
                    <span className={sync.data.traktAdded > 0 ? 'text-green-400' : 'opacity-60'}>
                      {sync.data.traktAdded}
                    </span>
                  </div>
                  {sync.data.errors.length > 0 && (
                    <ul className="mt-1 space-y-0.5 text-red-400/80 font-mono">
                      {sync.data.errors.map((e, i) => (
                        <li key={i}>• {e}</li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </div>
          ) : null}
        </DetailField>
      )}

      {summary && (
        <DetailField label="Identifiers">
          <div className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs font-mono opacity-80">
            <span>series_id</span><span>{row.id}</span>
            <span>tvdb_id</span><span>{row.tvdbId ?? '—'}</span>
            <span>title_slug</span><span>{summary.titleSlug ?? '—'}</span>
          </div>
        </DetailField>
      )}
    </RowDrawer>
  )
}

function DownloadCard({
  dl, isPending, onAction, onUntrack,
}: {
  dl: DownloadDto
  isPending: boolean
  onAction: (a: 'pause' | 'resume' | 'boost' | 'demote') => void
  onUntrack: () => void
}) {
  const [showLogs, setShowLogs] = useState(false)
  const logs = useDownloadLogs(dl.client, dl.clientId, showLogs)

  // Derive one canonical status from the live state + pausedByUs flag.
  // The previous "Paused by us: no — not paused by prioritarr" was
  // ambiguous about whether the download was actually running; now we
  // separate "actual state" (from the client) and "who paused it".
  const statusLabel = (() => {
    const live = dl.liveState?.toLowerCase() ?? null
    if (!live) return { text: 'unknown (client unreachable)', color: 'opacity-60' }
    if (live.includes('error') || live.includes('missing')) return { text: `error (${dl.liveState})`, color: 'text-red-400' }
    if (live === 'paused' || live.startsWith('pauseddl') || live.startsWith('pausedup')) {
      const by = dl.pausedByUs ? 'paused by prioritarr' : 'paused (by you or the client)'
      return { text: by, color: 'text-amber-400' }
    }
    if (live.includes('stall')) return { text: `stalled (${dl.liveState})`, color: 'text-amber-400' }
    if (live.includes('download')) return { text: 'downloading', color: 'text-green-400' }
    if (live.includes('upload') || live === 'forcedup' || live === 'queuedup') return { text: 'seeding / done', color: 'text-green-400' }
    return { text: dl.liveState ?? 'unknown', color: 'opacity-70' }
  })()

  return (
    <div className="bg-surface-2 border border-surface-3 rounded-md p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="px-1.5 py-0.5 text-xs bg-surface-3 rounded font-mono">{dl.client}</span>
        {/* clientId is now a link into the downloader UI. Same deep-link
            as the LinkBadge in "Open in" below, but placed on the id
            itself for quick access. */}
        {dl.clientUrl ? (
          <a
            href={dl.clientUrl}
            target="_blank"
            rel="noreferrer"
            className="font-mono text-xs opacity-70 truncate hover:text-accent underline-offset-2 hover:underline"
            title={`Open in ${dl.client === 'qbit' ? 'qBittorrent' : 'SABnzbd'}`}
          >{dl.clientId}</a>
        ) : (
          <span className="font-mono text-xs opacity-70 truncate">{dl.clientId}</span>
        )}
        <span className={`px-1.5 py-0.5 text-xs rounded font-medium ${statusLabel.color} bg-surface-3`}>
          {statusLabel.text}
        </span>
        <span className="ml-auto px-1.5 py-0.5 text-xs rounded bg-surface-3">P{dl.currentPriority}</span>
      </div>
      {dl.liveErrorMessage && (
        <div className="text-xs text-red-400 bg-red-900/20 rounded px-2 py-1 font-mono break-words">
          {dl.liveErrorMessage}
        </div>
      )}
      <div className="text-xs grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 opacity-90">
        <span className="text-text-muted">Client state</span>
        <span className="font-mono">{dl.liveState ?? 'unknown'}</span>
        <span className="text-text-muted">Episodes</span>
        <span className="font-mono">{dl.episodeIds.join(', ') || '—'}</span>
        <span className="text-text-muted">First seen</span>
        <span className="font-mono">{dl.firstSeenAt.slice(0, 19)}</span>
        <span className="text-text-muted">Last reconciled</span>
        <span className="font-mono">{dl.lastReconciledAt.slice(0, 19)}</span>
      </div>
      <div className="flex flex-wrap gap-1 text-xs">
        {(['pause', 'resume', 'boost', 'demote'] as const).map((a) => (
          <button
            key={a}
            onClick={() => onAction(a)}
            disabled={isPending}
            className="px-2 py-1 rounded bg-surface-3 hover:bg-accent capitalize disabled:opacity-50"
          >
            {a}
          </button>
        ))}
        <button
          onClick={() => setShowLogs(!showLogs)}
          className="px-2 py-1 rounded bg-surface-3 hover:bg-accent"
        >
          {showLogs ? 'Hide logs' : 'Logs'}
        </button>
        <button
          onClick={onUntrack}
          disabled={isPending}
          className="px-2 py-1 rounded bg-red-900/60 hover:bg-red-700 disabled:opacity-50"
        >
          Untrack
        </button>
      </div>
      {showLogs && (
        <div className="text-[11px] font-mono bg-surface-0 border border-surface-3 rounded max-h-64 overflow-auto">
          {logs.isLoading ? (
            <div className="p-2 opacity-60">Loading…</div>
          ) : (logs.data?.entries ?? []).length === 0 ? (
            <div className="p-2 opacity-60">No log entries found mentioning this download.</div>
          ) : (
            <ul>
              {logs.data!.entries.map((e, i) => (
                <li
                  key={i}
                  className={`px-2 py-1 border-b border-surface-3 last:border-0 ${
                    e.level.toLowerCase().includes('error') || e.level.toLowerCase().includes('warn')
                      ? 'text-red-400'
                      : 'opacity-90'
                  }`}
                >
                  <span className="opacity-60">{e.ts.slice(0, 19)} · {e.source}</span>
                  <span className="mx-1 opacity-40">·</span>
                  {e.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

function LinkBadge({ href, label }: { href?: string | null; label: string }) {
  if (!href) return null
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="px-2 py-1 text-xs rounded bg-surface-3 hover:bg-accent inline-flex items-center gap-1"
    >
      {label}
      <ExternalLink size={12} className="opacity-70" />
    </a>
  )
}

function DetailField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wider text-text-muted mb-1">{label}</div>
      <div>{children}</div>
    </div>
  )
}

/**
 * Humanised decomposition of the raw reason string the priority
 * engine emits (e.g. "watch=85%, unwatched=2, last_watch=0d,
 * release=3d, hiatus=false"). Each key gets a plain-English label
 * and a value that reads naturally — "today" instead of "0d",
 * "yes" / "no" for hiatus, etc. Rendered as a two-column list.
 */
function PriorityReason({ reason, loading }: { reason: string | null; loading: boolean }) {
  if (loading && !reason) {
    return <div className="h-4 w-48 rounded bg-surface-3 animate-pulse" />
  }
  if (!reason) return <span className="opacity-60">No reason available — priority hasn't been computed.</span>

  // Parse the comma-separated k=v pairs; unknown keys are surfaced
  // raw so we never hide data we don't yet recognise.
  const entries = reason.split(',').map((s) => s.trim()).map((s) => {
    const eq = s.indexOf('=')
    return eq < 0 ? [s, ''] : [s.slice(0, eq).trim(), s.slice(eq + 1).trim()]
  }) as Array<[string, string]>

  function dayText(v: string): string {
    if (v === 'never' || v === 'neverd' || v === '—') return 'never'
    const n = Number(v.replace(/d$/, ''))
    if (!Number.isFinite(n)) return v
    if (n === 0) return 'today'
    if (n === 1) return 'yesterday'
    if (n < 7) return `${n} days ago`
    if (n < 30) return `${n} days ago (~${Math.round(n / 7)} weeks)`
    return `${n} days ago (~${Math.round(n / 30)} months)`
  }

  function statusText(v: string): string {
    switch (v) {
      case 'fully_downloaded': return 'fully downloaded — nothing to fetch'
      case 'returning':        return 'returning from dormancy'
      default:                 return v.replace(/_/g, ' ')
    }
  }

  function humanise(k: string, v: string): [string, string] {
    switch (k) {
      case 'watch':      return ['Watch progress', v]
      case 'unwatched':  return ['Episodes still to watch', v]
      case 'watched':    return ['Episodes watched', v]
      case 'aired':      return ['Episodes aired', v]
      case 'missing':    return ['Episodes still to download', v]
      case 'seasons':    return ['Monitored seasons', v]
      case 'last_watch': return ['Last watched', dayText(v)]
      case 'release':    return ['Latest episode released', dayText(v)]
      case 'hiatus':     return ['On hiatus', v === 'true' ? 'yes' : 'no']
      case 'status':     return ['Status', statusText(v)]
      default:           return [k.replace(/_/g, ' '), v]
    }
  }

  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
      {entries.map(([k, v]) => {
        const [label, value] = humanise(k, v)
        return (
          <React.Fragment key={k}>
            <dt className="text-text-muted">{label}</dt>
            <dd>{value}</dd>
          </React.Fragment>
        )
      })}
    </dl>
  )
}

/**
 * Per-source watch state table. One row per configured provider
 * (tautulli / plex / trakt) with how many episodes it says are
 * watched + when the latest watch happened. Unreachable providers
 * render as "unavailable" with the error message visible on hover.
 */
function WatchStatusTable({ data, loading }: { data: ProviderWatchStatus[] | null; loading: boolean }) {
  if (loading && !data) {
    return (
      <div className="space-y-1">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-4 rounded bg-surface-3 animate-pulse" />
        ))}
      </div>
    )
  }
  if (!data || data.length === 0) return <span className="opacity-60 text-xs">No watch sources configured.</span>

  return (
    <div className="text-xs">
      <div className="grid grid-cols-[auto_1fr_auto] gap-x-3 gap-y-1">
        <span className="text-text-muted font-medium">Source</span>
        <span className="text-text-muted font-medium">Watched</span>
        <span className="text-text-muted font-medium">Last</span>
        {data.map((s) => (
          <React.Fragment key={s.source}>
            <span className="capitalize">{s.source}</span>
            {s.ok ? (
              <span>{s.watchedEpisodeCount} {s.watchedEpisodeCount === 1 ? 'episode' : 'episodes'}</span>
            ) : (
              <span className="text-amber-400" title={s.errorMessage ?? 'provider unavailable'}>unavailable</span>
            )}
            <span className="opacity-70 font-mono">{s.lastWatchedAt?.slice(0, 10) ?? '—'}</span>
          </React.Fragment>
        ))}
      </div>
    </div>
  )
}

/**
 * Pulsing placeholder shown while the detail endpoint is in flight.
 * Covers the downloads / links / audit section so the user sees
 * something's coming rather than an abrupt empty area.
 */
function DrawerLoadingSkeleton() {
  return (
    <div className="space-y-3">
      {[60, 40, 80].map((w, i) => (
        <div key={i} className="space-y-1.5">
          <div className="h-3 w-24 rounded bg-surface-3 animate-pulse" />
          <div className={`h-16 rounded bg-surface-2 animate-pulse`} style={{ width: `${w}%` }} />
        </div>
      ))}
    </div>
  )
}
