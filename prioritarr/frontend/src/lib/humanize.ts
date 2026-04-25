/**
 * Convert technical identifiers (snake_case, kebab-case, camelCase)
 * into a single user-friendly sentence-case label.
 *
 *   "trakt_unmonitored"     → "Trakt unmonitored"
 *   "priority-recomputed"   → "Priority recomputed"
 *   "downloadActionFailed"  → "Download action failed"
 *
 * Idempotent on already-human strings.
 */
export function humanize(raw: string | null | undefined): string {
  if (raw == null || raw === '') return ''
  const spaced = String(raw)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}
