import type { SyncDirection } from '../hooks/queries'

export const SYNC_DIRECTIONS: Array<{
  value: SyncDirection
  label: string
  hint: string
}> = [
  {
    value: 'both',
    label: 'Plex ⇆ Trakt (symmetric)',
    hint: 'Push Plex-watched to Trakt AND Trakt-watched to Plex. Default.',
  },
  {
    value: 'plex-to-trakt',
    label: 'Plex → Trakt only',
    hint: 'Only push Plex-watched to Trakt. Use when Trakt is your source of truth to avoid loop-backs from Plex scrobbles.',
  },
  {
    value: 'trakt-to-plex',
    label: 'Trakt → Plex only',
    hint: 'Only scrobble Trakt-watched back to Plex. Use when seeding a fresh Plex library from an existing Trakt history.',
  },
]

type Props = {
  value: SyncDirection
  onChange: (v: SyncDirection) => void
  disabled?: boolean
  className?: string
  id?: string
}

export function SyncDirectionSelect({ value, onChange, disabled, className, id }: Props) {
  const active = SYNC_DIRECTIONS.find((d) => d.value === value) ?? SYNC_DIRECTIONS[0]
  return (
    <select
      id={id}
      value={value}
      onChange={(e) => onChange(e.target.value as SyncDirection)}
      disabled={disabled}
      title={active.hint}
      className={
        className ??
        'px-2 py-1 rounded text-sm bg-surface-3 border border-surface-2 disabled:opacity-50'
      }
    >
      {SYNC_DIRECTIONS.map((d) => (
        <option key={d.value} value={d.value} title={d.hint}>
          {d.label}
        </option>
      ))}
    </select>
  )
}
