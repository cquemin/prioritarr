/**
 * Sticky bottom bar that appears when at least one row is selected.
 * The only thing it knows about the rows is their count — the actions
 * themselves are passed in as children, keeping this component
 * domain-agnostic (shared by Downloads today, anything else tomorrow).
 */

import type { ReactNode } from 'react'
import { X } from 'lucide-react'

interface BulkActionBarProps {
  count: number
  onClear: () => void
  children: ReactNode
}

export function BulkActionBar({ count, onClear, children }: BulkActionBarProps) {
  if (count === 0) return null
  return (
    <div
      role="toolbar"
      aria-label="Bulk actions"
      className="sticky bottom-0 left-0 right-0 z-10 bg-surface-2 border-t border-surface-3 px-4 py-3 flex items-center gap-3 shadow-lg"
    >
      <button
        type="button"
        onClick={onClear}
        aria-label="Clear selection"
        className="p-1 rounded hover:bg-surface-3 text-text-secondary"
      >
        <X size={16} />
      </button>
      <span className="text-sm">
        <strong>{count}</strong> selected
      </span>
      <div className="flex flex-wrap gap-2 ml-auto">{children}</div>
    </div>
  )
}
