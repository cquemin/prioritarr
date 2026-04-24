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
import { useEffect, useMemo, useState } from 'react'

import { DataTable } from '../components/DataTable'
import { KebabMenu } from '../components/KebabMenu'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import {
  useDownloadAction,
  useRecomputeSeries,
  useSearch,
  useSeries,
  useSeriesList,
  useSeriesSync,
  useUntrackDownload,
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

  // Minimal 2-column layout: priority chip + title. Everything else
  // (clients, paused counts, download count, cached-at) lives in the
  // detail drawer you see on row-click. Keeps the table readable on
  // narrow viewports and doesn't force horizontal scroll for
  // secondary metadata.
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
      // w-[1%] + whitespace-nowrap is the standard "shrink column to
      // its content" trick with `table-layout: auto` — keeps the
      // Priority column just wide enough for the P1 chip.
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
          <div className="space-y-0.5">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-medium">{r.title}</span>
              {chips.map((c) => (
                <span key={c} className="px-1.5 py-0.5 text-[10px] bg-surface-3 rounded opacity-70">{c}</span>
              ))}
            </div>
            {matched && (
              <div className="text-xs opacity-70 truncate">
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
          <button
            type="button"
            onClick={() => sync.mutate({ id: row.id })}
            disabled={sync.isPending}
            className="px-3 py-1.5 rounded bg-surface-3 text-sm disabled:opacity-50"
            title="Sync watch state between Plex and Trakt (mirror missing episodes)"
          >
            {sync.isPending ? 'Syncing…' : 'Sync Plex ⇆ Trakt'}
          </button>
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
        {row.reason ?? d?.priority?.reason ? (
          <span className="font-mono text-xs leading-relaxed whitespace-pre-wrap">
            {row.reason ?? d?.priority?.reason}
          </span>
        ) : detail.isLoading ? (
          <span className="opacity-60">Loading…</span>
        ) : (
          <span className="opacity-60">No reason available — priority hasn't been computed.</span>
        )}
      </DetailField>

      <DetailField label="Cache expires">
        <span className="opacity-80 text-xs">{d?.cacheExpiresAt?.slice(0, 19) ?? '—'}</span>
      </DetailField>

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
  return (
    <div className="bg-surface-2 border border-surface-3 rounded-md p-3 space-y-2">
      <div className="flex items-center gap-2">
        <span className="px-1.5 py-0.5 text-xs bg-surface-3 rounded font-mono">{dl.client}</span>
        <span className="font-mono text-xs opacity-70 truncate">{dl.clientId}</span>
        <span className="ml-auto px-1.5 py-0.5 text-xs rounded bg-surface-3">P{dl.currentPriority}</span>
      </div>
      <div className="text-xs grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 opacity-90">
        <span className="text-text-muted">Paused by us</span>
        <span className={dl.pausedByUs ? 'text-amber-400' : 'opacity-60'}>
          {dl.pausedByUs
            ? 'yes — prioritarr paused it (low priority enforcement)'
            : 'no — not paused by prioritarr'}
        </span>
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
          onClick={onUntrack}
          disabled={isPending}
          className="px-2 py-1 rounded bg-red-900/60 hover:bg-red-700 disabled:opacity-50"
        >
          Untrack
        </button>
      </div>
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
