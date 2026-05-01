import { useEffect, useState } from 'react'
import { X, KeyRound, WifiOff } from 'lucide-react'
import { useHealthProviders } from '../hooks/queries'
import { navigate } from '../hooks/useHashRoute'

/**
 * Provider health banner. Mounted in the app shell, visible on every
 * page. One row per non-healthy upstream; each row deep-links to the
 * matching settings card and is independently dismissable.
 *
 * Dismissals are kept in component state — *not* persisted — so a full
 * page reload re-shows everything. Matches the spec: dismiss is a
 * "noted, hide for this session" gesture, not a permanent silence.
 *
 * Auto-undismiss when a provider transitions ok→non-ok again, so a
 * re-broken integration always re-pings the user even if they had
 * previously dismissed an unrelated row from this set.
 */
export function HealthBanner() {
  const { data } = useHealthProviders()
  const [dismissed, setDismissed] = useState<Set<string>>(new Set())
  // Track providers that were healthy on the previous render so we can
  // auto-undismiss on a fresh transition healthy → unhealthy.
  const [previouslyOk, setPreviouslyOk] = useState<Set<string>>(new Set())

  const providers = data?.providers ?? []

  // Undismiss any provider that just flipped from ok back to non-ok.
  useEffect(() => {
    if (providers.length === 0) return
    const newlyBroken = providers
      .filter((p) => p.status !== 'ok' && p.status !== 'unknown' && previouslyOk.has(p.name))
      .map((p) => p.name)
    if (newlyBroken.length > 0) {
      setDismissed((prev) => {
        const next = new Set(prev)
        for (const n of newlyBroken) next.delete(n)
        return next
      })
    }
    // Refresh the "previously ok" snapshot for next render.
    const okNow = new Set(providers.filter((p) => p.status === 'ok').map((p) => p.name))
    setPreviouslyOk(okNow)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  // Build the row list: only non-ok and non-unknown, minus dismissed.
  const rows = providers.filter(
    (p) => p.status !== 'ok' && p.status !== 'unknown' && !dismissed.has(p.name),
  )

  if (rows.length === 0) return null

  return (
    <div className="border-b border-amber-700">
      {rows.map((p) => (
        <div
          key={p.name}
          className="bg-amber-900/40 px-4 py-2 text-sm text-amber-100 flex items-center gap-3 border-t border-amber-800/50 first:border-t-0"
        >
          <div className="shrink-0">
            {p.status === 'unauth' ? (
              <KeyRound size={16} className="text-amber-300" />
            ) : (
              <WifiOff size={16} className="text-amber-300" />
            )}
          </div>
          <button
            type="button"
            onClick={() => goToSettings(p.name)}
            className="flex-1 text-left hover:underline focus:outline-none focus:underline"
          >
            <span className="font-semibold">{p.label}</span>{' '}
            <span className="opacity-80">
              {statusLabel(p.status)}
              {p.detail ? ` — ${p.detail}` : ''}
            </span>
            {p.lastOkAt && (
              <span className="opacity-50 ml-2 text-xs">
                last healthy {formatRelative(p.lastOkAt)}
              </span>
            )}
          </button>
          <button
            type="button"
            onClick={() => dismissOne(setDismissed, p.name)}
            title="Dismiss for this session"
            className="shrink-0 opacity-60 hover:opacity-100 focus:outline-none"
          >
            <X size={16} />
          </button>
        </div>
      ))}
    </div>
  )
}

function statusLabel(status: string): string {
  switch (status) {
    case 'unauth':
      return 'authentication failed — click to re-auth'
    case 'unreachable':
      return 'unreachable — service may be down'
    default:
      return status
  }
}

function dismissOne(
  setter: React.Dispatch<React.SetStateAction<Set<string>>>,
  name: string,
) {
  setter((prev) => {
    const next = new Set(prev)
    next.add(name)
    return next
  })
}

/**
 * Take the user to the connections settings section, then scroll to the
 * matching provider card on the next paint. Card ids set in
 * SettingsPage.tsx via `id="settings-{name}-card"`.
 */
function goToSettings(name: string) {
  navigate({ page: 'settings', settingsSection: 'connections' })
  requestAnimationFrame(() => {
    const el = document.getElementById(`settings-${name}-card`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      // Give a short visual confirmation. The card resets the class on
      // the next interaction; we use a CSS animation that auto-fades.
      el.classList.add('ring-2', 'ring-amber-400')
      window.setTimeout(() => {
        el.classList.remove('ring-2', 'ring-amber-400')
      }, 2000)
    }
  })
}

/** Coarse relative-time helper for the "last healthy" hint. */
function formatRelative(iso: string): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const ageMin = Math.max(0, Math.round((Date.now() - t) / 60_000))
  if (ageMin < 1) return 'just now'
  if (ageMin < 60) return `${ageMin}m ago`
  const ageHr = Math.round(ageMin / 60)
  if (ageHr < 24) return `${ageHr}h ago`
  const ageDay = Math.round(ageHr / 24)
  return `${ageDay}d ago`
}

