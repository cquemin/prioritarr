/**
 * Shared priority vocabulary. Matches the label strings Compute.kt
 * returns, so the UI can show a friendly "P1 Live-following" tooltip
 * without asking the backend for each row's label again.
 *
 * Keep this in sync with prioritarr-app/backend/.../priority/Compute.kt.
 */

export const PRIORITY_LABELS: Record<number, string> = {
  1: 'P1 Live-following',
  2: 'P2 Caught-up but lapsed',
  3: 'P3 A few unwatched',
  4: 'P4 Partial backfill',
  5: 'P5 Full backfill / dormant',
}

export const PRIORITY_CLASS: Record<number, string> = {
  1: 'bg-priority-1/20 text-priority-1 border-priority-1/50',
  2: 'bg-priority-2/20 text-priority-2 border-priority-2/50',
  3: 'bg-priority-3/20 text-priority-3 border-priority-3/50',
  4: 'bg-priority-4/20 text-priority-4 border-priority-4/50',
  5: 'bg-priority-5/20 text-priority-5 border-priority-5/50',
}
