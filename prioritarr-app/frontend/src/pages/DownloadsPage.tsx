import { useState } from 'react'
import { useDownloadAction, useDownloads, useUntrackDownload } from '../hooks/queries'

export function DownloadsPage() {
  const [client, setClient] = useState<'qbit' | 'sab' | undefined>(undefined)
  const { data, isLoading } = useDownloads({ client, limit: 200 })
  const action = useDownloadAction()
  const untrack = useUntrackDownload()

  if (isLoading) return <p className="p-6 opacity-70">Loading…</p>
  const rows = (data?.records as Array<any>) ?? []

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Downloads ({data?.totalRecords ?? 0})</h1>
        <div className="flex gap-2 text-sm">
          {([undefined, 'qbit', 'sab'] as const).map((c) => (
            <button
              key={String(c)}
              onClick={() => setClient(c)}
              className={`px-3 py-1 rounded ${
                client === c ? 'bg-accent text-white' : 'bg-surface-2 hover:bg-surface-3'
              }`}
            >
              {c ?? 'all'}
            </button>
          ))}
        </div>
      </div>
      <div className="bg-surface-1 rounded-lg border border-surface-3 overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-surface-2 text-left">
            <tr>
              <th className="px-4 py-2">Client</th>
              <th className="px-4 py-2">Hash / NZO</th>
              <th className="px-4 py-2">Series</th>
              <th className="px-4 py-2">Priority</th>
              <th className="px-4 py-2">Paused</th>
              <th className="px-4 py-2">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r: any) => (
              <tr key={`${r.client}-${r.clientId}`} className="border-t border-surface-3 hover:bg-surface-2">
                <td className="px-4 py-2"><span className="px-2 py-0.5 text-xs bg-surface-3 rounded">{r.client}</span></td>
                <td className="px-4 py-2 font-mono text-xs opacity-80">{r.clientId.slice(0, 14)}</td>
                <td className="px-4 py-2">{r.seriesTitle ?? <span className="opacity-40">—</span>}</td>
                <td className="px-4 py-2">P{r.currentPriority}</td>
                <td className="px-4 py-2">{r.pausedByUs ? 'yes' : 'no'}</td>
                <td className="px-4 py-2 space-x-1 text-xs">
                  {(['pause', 'resume', 'boost', 'demote'] as const).map((a) => (
                    <button
                      key={a}
                      onClick={() => action.mutate({ client: r.client, clientId: r.clientId, action: a })}
                      disabled={action.isPending}
                      className="px-2 py-0.5 rounded bg-surface-3 hover:bg-accent"
                    >
                      {a}
                    </button>
                  ))}
                  <button
                    onClick={() => untrack.mutate({ client: r.client, clientId: r.clientId })}
                    disabled={untrack.isPending}
                    className="px-2 py-0.5 rounded bg-red-900/60 hover:bg-red-700"
                  >
                    untrack
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
