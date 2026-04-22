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
import { ExternalLink } from 'lucide-react'
import { useState } from 'react'

import { DataTable } from '../components/DataTable'
import { KebabMenu } from '../components/KebabMenu'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import {
  useDownloadAction,
  useRecomputeSeries,
  useSeries,
  useSeriesList,
  useSeriesSync,
  useUntrackDownload,
} from '../hooks/queries'
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
  const [openRow, setOpenRow] = useState<SeriesRow | null>(null)

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

  const rows = ((data?.records as unknown) as SeriesRow[]) ?? []

  const columns: ColumnDef<SeriesRow>[] = [
    {
      accessorKey: 'priority',
      header: 'Priority',
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
    },
    {
      accessorKey: 'title',
      header: 'Title',
      cell: ({ row }) => <span className="font-medium">{row.original.title}</span>,
      filterFn: 'includesString',
    },
    {
      id: 'clients',
      accessorFn: (r) => r.clients.join(','),
      header: 'Client',
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
      accessorKey: 'computedAt',
      header: 'Cached',
      cell: ({ row }) => (
        <span className="opacity-70 text-xs">
          {row.original.computedAt?.slice(0, 19) ?? '—'}
        </span>
      ),
    },
    {
      accessorKey: 'managedDownloadCount',
      header: 'Downloads',
      cell: ({ row }) => <span>{row.original.managedDownloadCount}</span>,
    },
    {
      id: '__actions',
      header: '',
      enableSorting: false,
      enableColumnFilter: false,
      cell: ({ row }) => (
        <KebabMenu
          items={[
            {
              label: 'Recompute priority',
              onSelect: () => recompute.mutate(row.original.id),
              disabled: recompute.isPending,
            },
            { label: 'View detail', onSelect: () => setOpenRow(row.original) },
          ]}
        />
      ),
    },
  ]

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Series ({data?.totalRecords ?? 0})</h1>
        <p className="text-xs text-text-muted">
          Click a column header to sort · type in a header field to filter · click a row for details + external links
        </p>
      </div>

      <DataTable
        data={rows}
        columns={columns}
        rowKey={(r) => r.id}
        onRowClick={(r) => setOpenRow(r)}
        emptyMessage="No series — Sonarr library is empty or series cache hasn't refreshed yet."
      />

      {openRow && (
        <SeriesDetailDrawer
          row={openRow}
          onClose={() => setOpenRow(null)}
          onRecompute={() => recompute.mutate(openRow.id)}
          isRecomputing={recompute.isPending}
        />
      )}
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
            onClick={() => sync.mutate(row.id)}
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
