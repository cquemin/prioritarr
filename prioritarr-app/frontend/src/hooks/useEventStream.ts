import { fetchEventSource } from '@microsoft/fetch-event-source'
import { useEffect, useRef, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { API_BASE, getApiKey } from '../api/client'

type Status = 'connecting' | 'open' | 'closed'

const LAST_EVENT_ID_KEY = 'prioritarr_last_event_id'

export interface AppEvent {
  id: number | null
  type: string
  payload: unknown
}

export function useEventStream(queryClient: QueryClient) {
  const [status, setStatus] = useState<Status>('connecting')
  const [recent, setRecent] = useState<AppEvent[]>([])
  const ctrlRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const apiKey = getApiKey()
    if (!apiKey) return
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    setStatus('connecting')

    const lastId = localStorage.getItem(LAST_EVENT_ID_KEY)
    const headers: Record<string, string> = { 'X-Api-Key': apiKey }
    if (lastId) headers['Last-Event-ID'] = lastId

    fetchEventSource(
      API_BASE + '/api/v2/events',
      {
        headers,
        signal: ctrl.signal,
        openWhenHidden: true,
        onopen: async (resp) => {
          if (!resp.ok) throw new Error(`SSE open failed: ${resp.status}`)
          setStatus('open')
        },
        onmessage: (ev) => {
          let payload: unknown = null
          try { payload = JSON.parse(ev.data) } catch { payload = ev.data }
          const appEvent: AppEvent = {
            id: ev.id ? Number(ev.id) : null,
            type: ev.event || 'message',
            payload,
          }
          if (appEvent.id != null) localStorage.setItem(LAST_EVENT_ID_KEY, String(appEvent.id))
          setRecent((prev) => [appEvent, ...prev].slice(0, 20))

          // Extract seriesId from the payload when present — webhook
          // events that affect a specific series include it so we
          // can narrow the cache invalidation to the matching
          // useSeries(id) query instead of blowing the whole list.
          const seriesId =
            typeof appEvent.payload === 'object' &&
            appEvent.payload !== null &&
            'series_id' in (appEvent.payload as any)
              ? (appEvent.payload as any).series_id
              : null

          switch (appEvent.type) {
            case 'priority-recomputed':
              queryClient.invalidateQueries({ queryKey: ['series'] })
              break
            case 'download-action':
            case 'download-untracked':
              queryClient.invalidateQueries({ queryKey: ['downloads'] })
              if (seriesId != null) queryClient.invalidateQueries({ queryKey: ['series', seriesId] })
              break
            // Sonarr webhook: episode imported. The priority engine's
            // "has file" tally changed for this series — recompute
            // and re-fetch the drawer.
            case 'episode-imported':
            case 'episode-deleted':
              queryClient.invalidateQueries({ queryKey: ['series'] })
              if (seriesId != null) {
                queryClient.invalidateQueries({ queryKey: ['series', seriesId] })
                queryClient.invalidateQueries({ queryKey: ['watch-status', seriesId] })
              }
              break
            case 'series-removed':
              // Series gone from Sonarr — flush the cache entries that
              // referenced it so the UI drops it immediately instead
              // of polling for a 404.
              queryClient.invalidateQueries({ queryKey: ['series'] })
              if (seriesId != null) queryClient.invalidateQueries({ queryKey: ['series', seriesId] })
              break
            // SAB post-processing webhook: a job transitioned into
            // Completed / Failed. Downloads list needs a refresh so
            // the pill updates.
            case 'download-completed':
            case 'download-failed':
              queryClient.invalidateQueries({ queryKey: ['downloads'] })
              queryClient.invalidateQueries({ queryKey: ['series'] })
              break
            case 'import-failed':
              // Surface via the event ticker only; no query to
              // invalidate because the state hasn't changed.
              break
            case 'mapping-refreshed':
              queryClient.invalidateQueries({ queryKey: ['mappings'] })
              break
            case 'audit-appended':
              queryClient.invalidateQueries({ queryKey: ['audit'] })
              break
            case 'heartbeat':
              // no-op — liveness only
              break
          }
        },
        onclose: () => setStatus('closed'),
        onerror: (err) => {
          setStatus('closed')
          // Let the default retry kick in; don't rethrow.
          console.warn('SSE error:', err)
        },
      },
    )

    return () => ctrl.abort()
  }, [queryClient])

  return { status, recent }
}
