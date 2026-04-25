/**
 * Simple pulsing placeholder. Used in place of "Loading…" text so the
 * layout doesn't jump when data arrives. Accepts Tailwind classes for
 * per-use sizing (w/h).
 */

interface SkeletonProps {
  className?: string
  /** Number of identical rows — for building list/table placeholders. */
  rows?: number
}

export function Skeleton({ className = 'h-4 w-full', rows = 1 }: SkeletonProps) {
  if (rows <= 1) {
    return <div className={`bg-surface-3 rounded animate-pulse ${className}`} aria-hidden="true" />
  }
  return (
    <div className="space-y-2" aria-hidden="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className={`bg-surface-3 rounded animate-pulse ${className}`} />
      ))}
    </div>
  )
}

/**
 * Table-shaped skeleton — header row + N body rows. Matches the visual
 * shape of DataTable so swapping between loading and loaded states
 * doesn't shift layout.
 */
export function TableSkeleton({ rows = 8, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 overflow-hidden">
      <div className="bg-surface-2 px-4 py-3 flex gap-4">
        {Array.from({ length: cols }).map((_, i) => (
          <Skeleton key={i} className="h-4 flex-1" />
        ))}
      </div>
      <div className="divide-y divide-surface-3">
        {Array.from({ length: rows }).map((_, i) => (
          <div key={i} className="px-4 py-3 flex gap-4">
            {Array.from({ length: cols }).map((_, j) => (
              <Skeleton key={j} className="h-4 flex-1" />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
