/**
 * Downloads browser — all tracked qBit + SAB items with multi-select and
 * bulk actions. Row click opens a drawer for single-item detail; the
 * kebab in each row still offers the one-click path for common actions.
 *
 * Bulk actions fan out as sequential single-item mutations today —
 * good enough for the typical selection size. A proper bulk endpoint
 * can come later if that becomes a bottleneck.
 */

import { type ColumnDef } from '@tanstack/react-table'
import { useState } from 'react'

import { BulkActionBar } from '../components/BulkActionBar'
import { DataTable } from '../components/DataTable'
import { KebabMenu } from '../components/KebabMenu'
import { RowDrawer } from '../components/RowDrawer'
import { TableSkeleton } from '../components/Skeleton'
import {
  useBulkDownloadAction,
  useDownloadAction,
  useDownloads,
  useUntrackDownload,
} from '../hooks/queries'
import { PRIORITY_CLASS, PRIORITY_LABELS } from '../lib/priority'

type DlAction = 'pause' | 'resume' | 'boost' | 'demote'

interface DownloadRow {
  client: 'qbit' | 'sab'
  clientId: string
  seriesId: number
  seriesTitle?: string | null
  currentPriority: number
  pausedByUs: boolean
  firstSeenAt: string
  lastReconciledAt: string
}

export function DownloadsPage() {
  const [clientFilter, setClientFilter] = useState<'qbit' | 'sab' | undefined>(undefined)
  const [selected, setSelected] = useState<DownloadRow[]>([])
  const [openRow, setOpenRow] = useState<DownloadRow | null>(null)

  const { data, isLoading } = useDownloads({ client: clientFilter, limit: 500 })
  const action = useDownloadAction()
  const untrack = useUntrackDownload()
  const bulk = useBulkDownloadAction()

  if (isLoading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-4">Downloads</h1>
        <TableSkeleton rows={10} cols={6} />
      </div>
    )
  }
  const rows = ((data?.records as unknown) as DownloadRow[]) ?? []

  // Bulk actions go through /api/v2/downloads/bulk — one round trip with
  // per-item verdicts — instead of fanning out N single-item mutations.
  // The drawer + kebab paths still use the single-item endpoints.
  function doBulk(a: DlAction) {
    bulk.mutate(
      { action: a, items: selected.map((r) => ({ client: r.client, clientId: r.clientId })) },
      {
        onSuccess: (res) => {
          if (res.failed > 0) {
            const firstFail = res.results.find((r) => !r.ok)
            console.warn(`bulk ${a}: ${res.failed}/${res.total} failed (e.g. ${firstFail?.client}/${firstFail?.clientId}: ${firstFail?.message})`)
          }
          setSelected([])
        },
      },
    )
  }
  function doBulkUntrack() {
    if (!confirm(`Untrack ${selected.length} downloads? They'll be left alone in the client.`)) return
    bulk.mutate(
      { action: 'untrack', items: selected.map((r) => ({ client: r.client, clientId: r.clientId })) },
      { onSuccess: () => setSelected([]) },
    )
  }

  const columns: ColumnDef<DownloadRow>[] = [
    {
      accessorKey: 'client',
      header: 'Client',
      cell: ({ row }) => (
        <span className="px-2 py-0.5 text-xs bg-surface-3 rounded">
          {row.original.client}
        </span>
      ),
      filterFn: 'equals',
    },
    {
      accessorKey: 'seriesTitle',
      header: 'Series',
      cell: ({ row }) =>
        row.original.seriesTitle ?? <span className="opacity-40">—</span>,
      filterFn: 'includesString',
    },
    {
      accessorKey: 'currentPriority',
      header: 'Priority',
      cell: ({ row }) => {
        const p = row.original.currentPriority
        return (
          <span
            className={`px-2 py-0.5 rounded text-xs border ${PRIORITY_CLASS[p] ?? ''}`}
            title={PRIORITY_LABELS[p] ?? `P${p}`}
          >
            P{p}
          </span>
        )
      },
    },
    {
      accessorKey: 'pausedByUs',
      header: 'Paused',
      cell: ({ row }) => (row.original.pausedByUs
        ? <span className="text-amber-400">yes</span>
        : <span className="opacity-60">no</span>),
      filterFn: (row, _id, value) => {
        if (!value) return true
        return (value === 'yes') === row.original.pausedByUs
      },
    },
    {
      accessorKey: 'clientId',
      header: 'ID',
      cell: ({ row }) => (
        <span className="font-mono text-xs opacity-70">
          {row.original.clientId.slice(0, 14)}
        </span>
      ),
    },
    {
      id: '__actions',
      header: '',
      enableSorting: false,
      enableColumnFilter: false,
      cell: ({ row }) => {
        const r = row.original
        return (
          <KebabMenu
            items={[
              { label: 'Pause', onSelect: () => action.mutate({ client: r.client, clientId: r.clientId, action: 'pause' }) },
              { label: 'Resume', onSelect: () => action.mutate({ client: r.client, clientId: r.clientId, action: 'resume' }) },
              { label: 'Boost', onSelect: () => action.mutate({ client: r.client, clientId: r.clientId, action: 'boost' }) },
              { label: 'Demote', onSelect: () => action.mutate({ client: r.client, clientId: r.clientId, action: 'demote' }) },
              { label: 'Untrack', destructive: true, onSelect: () => untrack.mutate({ client: r.client, clientId: r.clientId }) },
            ]}
          />
        )
      },
    },
  ]

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">
          Downloads ({data?.totalRecords ?? 0})
        </h1>
        <div className="flex gap-2 text-sm">
          {([undefined, 'qbit', 'sab'] as const).map((c) => (
            <button
              key={String(c)}
              onClick={() => setClientFilter(c)}
              className={`px-3 py-1 rounded ${
                clientFilter === c ? 'bg-accent text-white' : 'bg-surface-2 hover:bg-surface-3'
              }`}
            >
              {c ?? 'all'}
            </button>
          ))}
        </div>
      </div>

      <DataTable
        data={rows}
        columns={columns}
        rowKey={(r) => `${r.client}-${r.clientId}`}
        onRowClick={(r) => setOpenRow(r)}
        enableSelection
        onSelectionChange={setSelected}
        emptyMessage="No tracked downloads — run qBit + SAB for a minute, the reconcile loop will pick them up."
      />

      <BulkActionBar count={selected.length} onClear={() => setSelected([])}>
        {(['pause', 'resume', 'boost', 'demote'] as DlAction[]).map((a) => (
          <button
            key={a}
            onClick={() => doBulk(a)}
            disabled={bulk.isPending}
            className="px-3 py-1.5 text-sm rounded bg-surface-3 hover:bg-accent capitalize disabled:opacity-50"
          >
            {a}
          </button>
        ))}
        <button
          onClick={doBulkUntrack}
          disabled={bulk.isPending}
          className="px-3 py-1.5 text-sm rounded bg-red-900/60 hover:bg-red-700 disabled:opacity-50"
        >
          Untrack
        </button>
      </BulkActionBar>

      {openRow && (
        <RowDrawer
          isOpen
          onClose={() => setOpenRow(null)}
          title={openRow.seriesTitle ?? '(unknown series)'}
          subtitle={`${openRow.client} · ${openRow.clientId}`}
          footer={
            <>
              {(['pause', 'resume', 'boost', 'demote'] as DlAction[]).map((a) => (
                <button
                  key={a}
                  onClick={() => action.mutate({ client: openRow.client, clientId: openRow.clientId, action: a })}
                  disabled={action.isPending}
                  className="px-3 py-1.5 rounded bg-surface-3 hover:bg-accent text-sm capitalize"
                >
                  {a}
                </button>
              ))}
              <button
                onClick={() => {
                  if (!confirm('Untrack this download? It will be left alone in the client.')) return
                  untrack.mutate({ client: openRow.client, clientId: openRow.clientId })
                  setOpenRow(null)
                }}
                disabled={untrack.isPending}
                className="px-3 py-1.5 rounded bg-red-900/60 hover:bg-red-700 text-sm"
              >
                Untrack
              </button>
            </>
          }
        >
          <div className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
            <span className="text-text-muted">Client</span>
            <span>{openRow.client}</span>
            <span className="text-text-muted">Client ID</span>
            <span className="font-mono text-xs break-all">{openRow.clientId}</span>
            <span className="text-text-muted">Series</span>
            <span>{openRow.seriesTitle ?? '—'} (id {openRow.seriesId})</span>
            <span className="text-text-muted">Priority</span>
            <span>P{openRow.currentPriority}</span>
            <span className="text-text-muted">Paused by us</span>
            <span>{openRow.pausedByUs ? 'yes' : 'no'}</span>
            <span className="text-text-muted">First seen</span>
            <span className="font-mono text-xs">{openRow.firstSeenAt.slice(0, 19)}</span>
            <span className="text-text-muted">Last reconciled</span>
            <span className="font-mono text-xs">{openRow.lastReconciledAt.slice(0, 19)}</span>
          </div>
        </RowDrawer>
      )}
    </div>
  )
}
