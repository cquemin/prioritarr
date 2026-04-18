import { useState } from 'react'
import { useRecomputeSeries, useSeriesList } from '../hooks/queries'

const PRIO_CLASS: Record<number, string> = {
  1: 'bg-priority-1/20 text-priority-1 border-priority-1/50',
  2: 'bg-priority-2/20 text-priority-2 border-priority-2/50',
  3: 'bg-priority-3/20 text-priority-3 border-priority-3/50',
  4: 'bg-priority-4/20 text-priority-4 border-priority-4/50',
  5: 'bg-priority-5/20 text-priority-5 border-priority-5/50',
}

export function SeriesPage() {
  const [sort, setSort] = useState<'priority' | 'title' | 'id'>('priority')
  const { data, isLoading, error } = useSeriesList({ sort, limit: 200 })
  const recompute = useRecomputeSeries()

  if (isLoading) return <p className="p-6 opacity-70">Loading…</p>
  if (error) return <p className="p-6 text-red-400">Error: {String(error)}</p>

  const rows = (data?.records as Array<any>) ?? []

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Series ({data?.totalRecords ?? 0})</h1>
        <div className="flex gap-2 text-sm">
          {(['priority', 'title', 'id'] as const).map((s) => (
            <button
              key={s}
              onClick={() => setSort(s)}
              className={`px-3 py-1 rounded ${
                sort === s ? 'bg-accent text-white' : 'bg-surface-2 hover:bg-surface-3'
              }`}
            >
              Sort: {s}
            </button>
          ))}
        </div>
      </div>
      <div className="bg-surface-1 rounded-lg border border-surface-3 overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-surface-2 text-left">
            <tr>
              <th className="px-4 py-2">Priority</th>
              <th className="px-4 py-2">Title</th>
              <th className="px-4 py-2">TVDB</th>
              <th className="px-4 py-2">Cached</th>
              <th className="px-4 py-2">Downloads</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r: any) => (
              <tr key={r.id} className="border-t border-surface-3 hover:bg-surface-2">
                <td className="px-4 py-2">
                  {r.priority ? (
                    <span
                      className={`px-2 py-0.5 rounded text-xs border ${PRIO_CLASS[r.priority]}`}
                    >
                      P{r.priority}
                    </span>
                  ) : (
                    <span className="opacity-40">—</span>
                  )}
                </td>
                <td className="px-4 py-2 font-medium">{r.title}</td>
                <td className="px-4 py-2 opacity-70">{r.tvdbId ?? '—'}</td>
                <td className="px-4 py-2 opacity-70">{r.computedAt?.slice(0, 19) ?? '—'}</td>
                <td className="px-4 py-2">{r.managedDownloadCount}</td>
                <td className="px-4 py-2">
                  <button
                    onClick={() => recompute.mutate(r.id)}
                    disabled={recompute.isPending}
                    className="px-2 py-0.5 rounded text-xs bg-surface-3 hover:bg-accent"
                  >
                    Recompute
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
