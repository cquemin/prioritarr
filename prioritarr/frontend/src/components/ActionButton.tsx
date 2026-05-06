import React from 'react'
import { Loader2 } from 'lucide-react'

/**
 * Action button with explicit four-state visuals:
 *
 *   - "idle, can act"      → primary accent, full opacity, hover glow
 *   - "idle, can't act"    → flat surface, ~50% opacity, no-hover (visibly grey, not just dim primary)
 *   - "loading"            → primary, spinner replaces label suffix, dimmed slightly, disabled
 *   - "loading complete"   → reverts to "idle, can't act" naturally — caller decides idleLabel
 *
 * Use as:
 *   <ActionButton loading={save.isPending} disabled={!dirty} onClick={...}>Save</ActionButton>
 *   <ActionButton loading={reset.isPending} variant="secondary" onClick={...}>Reset</ActionButton>
 *
 * The visual `disabled` is the OR of the explicit `disabled` prop AND `loading`.
 * `disabled` is what the caller passes for "can't act" semantics; `loading` is
 * during the API call. Both produce a visually-disabled button, but loading
 * additionally shows a spinner.
 *
 * `variant`:
 *   - 'primary' (default)    → bg-accent, used for Save/Run-equivalent
 *   - 'secondary'            → bg-surface-3, used for Reset/Cancel-equivalent
 *   - 'destructive'          → bg-red-700, used for Delete-equivalent
 *
 * `size`:
 *   - 'md' (default)         → px-3 py-1 text-sm
 *   - 'sm'                   → px-2 py-0.5 text-xs (used in compact contexts like the inline P5 ratchet block)
 */
export type ActionButtonVariant = 'primary' | 'secondary' | 'destructive'
export type ActionButtonSize = 'md' | 'sm'

interface ActionButtonProps {
  children: React.ReactNode
  onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void
  loading?: boolean
  disabled?: boolean
  variant?: ActionButtonVariant
  size?: ActionButtonSize
  type?: 'button' | 'submit'
  title?: string
  loadingLabel?: string  // e.g., "Saving…" — shown next to spinner. Defaults to "Working…"
  className?: string     // appended to the base classes; for callers that need extra width / margin tweaks
}

const VARIANT_BASE: Record<ActionButtonVariant, string> = {
  primary: 'bg-accent text-white hover:brightness-110',
  secondary: 'bg-surface-3 text-white hover:bg-surface-2',
  destructive: 'bg-red-700 text-white hover:bg-red-600',
}

const SIZE_BASE: Record<ActionButtonSize, string> = {
  md: 'px-3 py-1 text-sm',
  sm: 'px-2 py-0.5 text-xs',
}

export function ActionButton({
  children,
  onClick,
  loading = false,
  disabled = false,
  variant = 'primary',
  size = 'md',
  type = 'button',
  title,
  loadingLabel,
  className = '',
}: ActionButtonProps) {
  const inactive = disabled || loading
  const base = 'inline-flex items-center justify-center gap-2 rounded font-medium transition-colors'
  const variantClass = inactive
    ? 'bg-surface-3/60 text-white/50 cursor-not-allowed'   // visibly grey, not faded primary
    : VARIANT_BASE[variant]
  const sizeClass = SIZE_BASE[size]
  return (
    <button
      type={type}
      title={title}
      disabled={inactive}
      onClick={onClick}
      className={`${base} ${variantClass} ${sizeClass} ${className}`.trim()}
    >
      {loading && <Loader2 className="animate-spin" size={size === 'sm' ? 12 : 14} aria-hidden />}
      {loading && loadingLabel ? <span>{loadingLabel}</span> : children}
    </button>
  )
}
