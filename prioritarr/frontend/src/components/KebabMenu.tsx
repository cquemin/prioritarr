/**
 * Three-dot overflow menu for single-row actions. Wraps Radix Dropdown
 * so callers get keyboard a11y + portal escape-hatching for free.
 *
 * Items: { label: string, onSelect: () => void, destructive?: boolean }
 */

import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { MoreHorizontal } from 'lucide-react'

export interface KebabItem {
  label: string
  onSelect: () => void
  destructive?: boolean
  disabled?: boolean
}

export function KebabMenu({ items, label = 'Row actions' }: { items: KebabItem[]; label?: string }) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        aria-label={label}
        onClick={(e) => e.stopPropagation()}
        className="p-1 rounded hover:bg-surface-3 text-text-secondary focus:outline-none focus:ring-1 focus:ring-accent"
      >
        <MoreHorizontal size={16} />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={4}
          className="z-50 min-w-[140px] bg-surface-2 border border-surface-3 rounded-md shadow-lg py-1"
          onClick={(e) => e.stopPropagation()}
        >
          {items.map((it) => (
            <DropdownMenu.Item
              key={it.label}
              disabled={it.disabled}
              onSelect={it.onSelect}
              className={`px-3 py-1.5 text-sm cursor-pointer outline-none ${
                it.destructive
                  ? 'text-red-400 focus:bg-red-900/40'
                  : 'focus:bg-accent/30'
              } data-[disabled]:opacity-40 data-[disabled]:pointer-events-none`}
            >
              {it.label}
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}
