/**
 * Series browser — full library at a glance with sort + filter on every
 * column. Row click opens a detail drawer that loads the full series
 * detail (including the priority reason string) via /api/v2/series/{id}.
 */

import { type ColumnDef } from '@tanstack/react-table'
import { useState } from 'react'

import { DataTable } from '../components/DataTable'
import { KebabMenu } from '../components/KebabMenu'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import { useRecomputeSeries, useSeries, useSeriesList } from '../hooks/queries'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'

interface SeriesRow {
  id: number
  title: string
  tvdbId?: number | null
  priority?: number | null
  label?: string | null
  computedAt?: string | null
  managedDownloadCount: number
}

export function SeriesPage() {
  const { data, isLoading, error } = useSeriesList({ limit: 500 })
  const recompute = useRecomputeSeries()
  const [openRow, setOpenRow] = useState<SeriesRow | null>(null)

  if (isLoading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-4">Series</h1>
        <TableSkeleton rows={12} cols={6} />
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
        return (
          <span
            className={`px-2 py-0.5 rounded text-xs border ${PRIORITY_CLASS[p]}`}
            title={PRIORITY_LABELS[p]}
          >
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
      accessorKey: 'tvdbId',
      header: 'TVDB',
      cell: ({ row }) => (
        <span className="opacity-70 font-mono">{row.original.tvdbId ?? '—'}</span>
      ),
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
            {
              label: 'View detail',
              onSelect: () => setOpenRow(row.original),
            },
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
          Click a column header to sort · type in a header field to filter · click a row for details
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

function SeriesDetailDrawer({
  row, onClose, onRecompute, isRecomputing,
}: {
  row: SeriesRow
  onClose: () => void
  onRecompute: () => void
  isRecomputing: boolean
}) {
  const detail = useSeries(row.id)
  const d = detail.data as any // schema regen will tighten this

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
            onClick={onClose}
            className="px-3 py-1.5 rounded bg-surface-3 text-sm"
          >
            Close
          </button>
        </>
      }
    >
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
        {detail.isLoading ? (
          <span className="opacity-60">Loading…</span>
        ) : d?.priority?.reason ? (
          <span className="font-mono text-xs leading-relaxed whitespace-pre-wrap">
            {d.priority.reason}
          </span>
        ) : (
          <span className="opacity-60">No reason available — priority hasn't been computed.</span>
        )}
      </DetailField>

      <DetailField label="Cache expires">
        <span className="opacity-80 text-xs">
          {d?.cacheExpiresAt?.slice(0, 19) ?? '—'}
        </span>
      </DetailField>

      <DetailField label="Active downloads">
        <span>{row.managedDownloadCount}</span>
      </DetailField>

      {d?.recentAudit?.length > 0 && (
        <DetailField label="Recent activity">
          <ul className="text-xs space-y-1 opacity-80">
            {d.recentAudit.slice(0, 5).map((a: any) => (
              <li key={a.id} className="font-mono">
                {a.ts.slice(0, 19)} · {a.action}
              </li>
            ))}
          </ul>
        </DetailField>
      )}
    </RowDrawer>
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
