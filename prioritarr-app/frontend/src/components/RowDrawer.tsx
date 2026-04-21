/**
 * Radix-Dialog-based side drawer — slides in from the right when a
 * table row is clicked. Used for per-row detail + single-item actions
 * that are too dense to cram into row cells.
 *
 * Callers pass:
 *   - isOpen / onClose to control visibility
 *   - title + children for the content
 *   - footer slot for action buttons
 */

import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'

interface RowDrawerProps {
  isOpen: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}

export function RowDrawer({ isOpen, onClose, title, subtitle, children, footer }: RowDrawerProps) {
  return (
    <Dialog.Root open={isOpen} onOpenChange={(o) => !o && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/60 data-[state=open]:animate-in data-[state=open]:fade-in data-[state=closed]:animate-out data-[state=closed]:fade-out" />
        <Dialog.Content
          className="fixed top-0 right-0 h-full w-full max-w-md bg-surface-1 border-l border-surface-3 shadow-xl flex flex-col data-[state=open]:animate-in data-[state=open]:slide-in-from-right data-[state=closed]:animate-out data-[state=closed]:slide-out-to-right"
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <header className="flex items-start justify-between gap-4 p-5 border-b border-surface-3">
            <div className="min-w-0">
              <Dialog.Title className="text-lg font-semibold truncate">
                {title}
              </Dialog.Title>
              {subtitle && (
                <Dialog.Description className="text-xs text-text-muted mt-0.5">
                  {subtitle}
                </Dialog.Description>
              )}
            </div>
            <Dialog.Close
              aria-label="Close"
              className="p-1 rounded hover:bg-surface-2 text-text-secondary"
            >
              <X size={18} />
            </Dialog.Close>
          </header>

          <div className="flex-1 overflow-y-auto p-5 space-y-4">
            {children}
          </div>

          {footer && (
            <footer className="p-5 border-t border-surface-3 flex flex-wrap gap-2">
              {footer}
            </footer>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
