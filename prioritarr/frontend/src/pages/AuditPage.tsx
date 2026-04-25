/**
 * Audit log browser. Same DataTable as Series/Downloads so sort + filter
 * behave consistently; the `details` blob lands in a drawer on row
 * click because it's arbitrary JSON that doesn't fit a column cleanly.
 */

import { type ColumnDef } from '@tanstack/react-table'
import { useState } from 'react'

import { DataTable } from '../components/DataTable'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import { useAudit } from '../hooks/queries'
import { humanize } from '../lib/humanize'

interface AuditRow {
  id: number
  ts: string
  action: string
  seriesId?: number | null
  client?: string | null
  clientId?: string | null
  details?: unknown
}

export function AuditPage() {
  const { data, isLoading, error } = useAudit({ limit: 500 })
  const [openRow, setOpenRow] = useState<AuditRow | null>(null)

  if (isLoading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-4">Audit log</h1>
        <TableSkeleton rows={10} cols={5} />
      </div>
    )
  }
  if (error) {
    return <ErrorState error={error} />
  }

  const rows = ((data?.records as unknown) as AuditRow[]) ?? []

  const columns: ColumnDef<AuditRow>[] = [
    {
      accessorKey: 'ts',
      header: 'Timestamp',
      cell: ({ row }) => (
        <span className="font-mono text-xs opacity-80">
          {row.original.ts.slice(0, 19)}
        </span>
      ),
      meta: { filter: { type: 'date-range' } },
    },
    {
      accessorKey: 'action',
      header: 'Action',
      // Display the humanized action so the operator doesn't have to
      // mentally translate `trakt_unmonitored` etc. Filter still works
      // on the raw value (TanStack reads accessorKey verbatim).
      cell: ({ row }) => <span className="text-accent">{humanize(row.original.action)}</span>,
      meta: { filter: { type: 'select' } },
    },
    {
      accessorKey: 'seriesId',
      header: 'Series',
      cell: ({ row }) =>
        row.original.seriesId ? (
          <span className="font-mono text-xs">#{row.original.seriesId}</span>
        ) : (
          <span className="opacity-30">—</span>
        ),
    },
    {
      accessorKey: 'client',
      header: 'Client',
      cell: ({ row }) =>
        row.original.client ? (
          <span className="text-xs">
            <span className="px-1.5 py-0.5 bg-surface-3 rounded">{humanize(row.original.client)}</span>
            {row.original.clientId && (
              <span className="opacity-60 font-mono ml-1">
                {row.original.clientId.slice(0, 14)}
              </span>
            )}
          </span>
        ) : (
          <span className="opacity-30">—</span>
        ),
      meta: {
        filter: {
          type: 'select',
          options: [
            { value: 'qbit', label: 'qBittorrent' },
            { value: 'sab', label: 'SABnzbd' },
          ],
        },
      },
    },
    {
      id: '__details_preview',
      header: 'Details',
      enableSorting: false,
      cell: ({ row }) => {
        if (!row.original.details) return <span className="opacity-30">—</span>
        const str = JSON.stringify(row.original.details)
        return (
          <span className="font-mono text-xs opacity-70 truncate block max-w-md">
            {str.length > 80 ? str.slice(0, 80) + '…' : str}
          </span>
        )
      },
    },
  ]

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">
          Audit log ({data?.totalRecords ?? 0})
        </h1>
        <p className="text-xs text-text-muted">
          Sort + filter per column · click a row to see the full details payload
        </p>
      </div>

      <DataTable
        data={rows}
        columns={columns}
        rowKey={(r) => r.id}
        onRowClick={(r) => setOpenRow(r)}
        emptyMessage="No audit entries — this fills in as priority recomputes, webhooks, and download actions run."
      />

      {openRow && (
        <RowDrawer
          isOpen
          onClose={() => setOpenRow(null)}
          title={humanize(openRow.action)}
          subtitle={`#${openRow.id} · ${openRow.ts.slice(0, 19)}`}
        >
          <div className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
            <span className="text-text-muted">Timestamp</span>
            <span className="font-mono text-xs">{openRow.ts}</span>
            <span className="text-text-muted">Series ID</span>
            <span>{openRow.seriesId ?? '—'}</span>
            <span className="text-text-muted">Client</span>
            <span>
              {openRow.client ? `${openRow.client} / ${openRow.clientId ?? '—'}` : '—'}
            </span>
          </div>
          {openRow.details != null && (
            <div>
              <div className="text-xs uppercase tracking-wider text-text-muted mb-1">
                Details
              </div>
              <pre className="bg-surface-2 rounded p-3 text-xs font-mono whitespace-pre-wrap break-all">
                {JSON.stringify(openRow.details, null, 2)}
              </pre>
            </div>
          )}
        </RowDrawer>
      )}
    </div>
  )
}

function ErrorState({ error }: { error: unknown }) {
  return (
    <div className="p-6">
      <h1 className="text-2xl font-semibold mb-4">Audit log</h1>
      <div className="bg-red-900/20 border border-red-700/60 rounded-lg p-4 text-red-300 text-sm">
        <strong>Failed to load audit log.</strong>
        <div className="mt-1 font-mono text-xs opacity-80">{String(error)}</div>
      </div>
    </div>
  )
}
