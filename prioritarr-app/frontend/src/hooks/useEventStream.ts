import { fetchEventSource } from '@microsoft/fetch-event-source'
import { useEffect, useRef, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { getApiKey } from '../api/client'

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
      (import.meta.env.VITE_BACKEND_URL ?? '') + '/api/v2/events',
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

          switch (appEvent.type) {
            case 'priority-recomputed':
              queryClient.invalidateQueries({ queryKey: ['series'] })
              break
            case 'download-action':
            case 'download-untracked':
              queryClient.invalidateQueries({ queryKey: ['downloads'] })
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
