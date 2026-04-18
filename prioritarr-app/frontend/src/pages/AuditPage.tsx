import { useState } from 'react'
import { useAudit } from '../hooks/queries'

export function AuditPage() {
  const [action, setAction] = useState<string>('')
  const [limit] = useState(100)
  const { data, isLoading } = useAudit({ action: action || undefined, limit })

  if (isLoading) return <p className="p-6 opacity-70">Loading…</p>
  const rows = (data?.records as Array<any>) ?? []

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Audit log ({data?.totalRecords ?? 0})</h1>
        <input
          value={action}
          onChange={(e) => setAction(e.target.value)}
          placeholder="filter: action"
          className="px-3 py-1 rounded bg-surface-2 border border-surface-3 text-sm"
        />
      </div>
      <div className="space-y-1">
        {rows.map((r: any) => (
          <div key={r.id} className="bg-surface-1 px-4 py-2 rounded border border-surface-3 text-sm font-mono">
            <span className="opacity-60">{r.ts}</span>{' '}
            <span className="text-accent">{r.action}</span>
            {r.seriesId && <span className="opacity-80"> · series={r.seriesId}</span>}
            {r.client && <span className="opacity-80"> · {r.client}/{r.clientId?.slice(0, 10)}</span>}
            {r.details && (
              <div className="mt-1 pl-4 opacity-60 text-xs break-all">{JSON.stringify(r.details)}</div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
