import { useEffect, useState } from 'react'
import { API_BASE } from './client'

export type VersionInfo = { version: string; gitSha: string; buildTime: string }

export function useVersion(): VersionInfo | null {
  const [v, setV] = useState<VersionInfo | null>(null)
  useEffect(() => {
    let alive = true
    fetch(`${API_BASE}/version`, { headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (alive && data) setV(data as VersionInfo)
      })
      .catch(() => {
        /* non-fatal: footer just stays hidden */
      })
    return () => {
      alive = false
    }
  }, [])
  return v
}
