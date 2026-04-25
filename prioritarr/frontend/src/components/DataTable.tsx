/**
 * Generic TanStack-Table wrapper used by Series + Downloads pages.
 *
 * Wires sort-by-header-click, per-column text filters, row selection
 * (checkbox), and row-click handoff. Stays presentation-only — callers
 * own the data, the filtered/selected state is reported back via
 * callbacks so each page can render its own bulk-action bar.
 */

import {
  type Column,
  type ColumnDef,
  type ColumnFiltersState,
  type Row,
  type RowSelectionState,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import { humanize } from '../lib/humanize'

/**
 * Per-column filter UX. Stash on `meta.filter`:
 *   - `{ type: 'text' }`   default — free-text "contains" input
 *   - `{ type: 'select', options? }` — dropdown. If `options` omitted,
 *      the unique values in the data are used and humanized as labels.
 *   - `{ type: 'date-range' }` — two date pickers; matches rows whose
 *      column value (ISO string) falls inside [from, to]. Either bound
 *      may be empty.
 *
 * Each variant attaches the right `filterFn` automatically so the
 * caller doesn't have to remember to set `filterFn: 'includesString'`.
 */
export type FilterConfig =
  | { type: 'text' }
  | { type: 'select'; options?: ReadonlyArray<{ value: string; label: string }> }
  | { type: 'date-range' }

declare module '@tanstack/react-table' {
  // Augment ColumnMeta so callers get autocomplete + type-checking
  // when they set `meta: { filter: { type: 'select' } }`.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData extends unknown, TValue> {
    cellClassName?: string
    filter?: FilterConfig
    /** Override the built-in humanize for select-filter option labels. */
    formatOption?: (raw: unknown) => string
  }
}

export interface DataTableProps<T> {
  data: T[]
  columns: ColumnDef<T, any>[]
  /** Stable row key — used for selection tracking across filter/sort. */
  rowKey: (row: T) => string | number
  /** Clicked anywhere on a row except the checkbox / kebab. */
  onRowClick?: (row: T) => void
  /** Show a leading checkbox column + report selected row objects. */
  enableSelection?: boolean
  onSelectionChange?: (selected: T[]) => void
  emptyMessage?: string
}

export function DataTable<T>(props: DataTableProps<T>) {
  const {
    data, columns, rowKey, onRowClick,
    enableSelection = false, onSelectionChange, emptyMessage = 'No rows',
  } = props

  const [sorting, setSorting] = useState<SortingState>([])
  const [filters, setFilters] = useState<ColumnFiltersState>([])
  const [selection, setSelection] = useState<RowSelectionState>({})

  // Inject the right filterFn for any column whose meta declares a
  // non-text filter, so callers don't have to repeat themselves.
  const filteredColumns: ColumnDef<T, any>[] = useMemo(
    () => columns.map((c) => {
      const meta = c.meta as { filter?: FilterConfig } | undefined
      if (!meta?.filter) return c
      if (meta.filter.type === 'select' && c.filterFn == null) {
        // Exact-match select; row's column value compared as string.
        return { ...c, filterFn: ((row, columnId, filterValue) => {
          if (!filterValue) return true
          const v = row.getValue(columnId)
          return v != null && String(v) === String(filterValue)
        }) }
      }
      if (meta.filter.type === 'date-range' && c.filterFn == null) {
        return { ...c, filterFn: dateRangeFilterFn }
      }
      return c
    }),
    [columns],
  )

  // Prepend a selection column if requested. We build it inline rather
  // than export a helper so the caller's columns array stays untouched.
  const tableColumns: ColumnDef<T, any>[] = enableSelection
    ? [selectColumn<T>(), ...filteredColumns]
    : filteredColumns

  const table = useReactTable({
    data,
    columns: tableColumns,
    getRowId: (row) => String(rowKey(row)),
    state: { sorting, columnFilters: filters, rowSelection: selection },
    onSortingChange: setSorting,
    onColumnFiltersChange: setFilters,
    onRowSelectionChange: setSelection,
    enableRowSelection: enableSelection,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  })

  // Push selection out. We map ids → original rows so consumers don't
  // have to re-derive from the table instance.
  useEffect(() => {
    if (!onSelectionChange) return
    const selected = table.getSelectedRowModel().rows.map((r) => r.original)
    onSelectionChange(selected)
  }, [selection, data]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="bg-surface-1 rounded-lg border border-surface-3 overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-surface-2 text-left">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => {
                const col = h.column
                const sortDir = col.getIsSorted()
                // Optional per-column width/class, carried on the
                // column definition's `meta.cellClassName`. Use it for
                // narrow-content columns (Priority chip, kebab cell)
                // via e.g. `meta: { cellClassName: 'w-[1%] whitespace-nowrap' }`.
                const extraClass = (col.columnDef.meta as { cellClassName?: string } | undefined)?.cellClassName ?? ''
                return (
                  <th key={h.id} className={`px-4 py-2 align-top ${extraClass}`}>
                    <div className="flex flex-col gap-1">
                      <button
                        type="button"
                        onClick={col.getCanSort() ? col.getToggleSortingHandler() : undefined}
                        disabled={!col.getCanSort()}
                        className={`flex items-center gap-1 text-left font-medium ${
                          col.getCanSort() ? 'cursor-pointer hover:text-text-primary' : 'cursor-default'
                        }`}
                      >
                        {flexRender(col.columnDef.header, h.getContext())}
                        {col.getCanSort() && (
                          <SortIcon dir={sortDir === false ? null : sortDir} />
                        )}
                      </button>
                      {col.getCanFilter() && (
                        <SmartFilterInput column={col} data={data} />
                      )}
                    </div>
                  </th>
                )
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => (
            <DataTableRow key={row.id} row={row} onRowClick={onRowClick} />
          ))}
          {table.getRowModel().rows.length === 0 && (
            <tr>
              <td
                colSpan={tableColumns.length}
                className="px-4 py-8 text-center text-text-muted"
              >
                {emptyMessage}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function DataTableRow<T>({
  row, onRowClick,
}: { row: Row<T>, onRowClick?: (row: T) => void }) {
  const isSelected = row.getIsSelected()
  return (
    <tr
      className={`border-t border-surface-3 ${
        isSelected ? 'bg-accent/10' : 'hover:bg-surface-2'
      } ${onRowClick ? 'cursor-pointer' : ''}`}
      onClick={(e) => {
        // Clicks on interactive children (buttons, inputs, checkboxes,
        // the kebab menu's trigger) shouldn't open the drawer.
        const target = e.target as HTMLElement
        if (target.closest('button, input, [role="menu"], [role="menuitem"]')) return
        onRowClick?.(row.original)
      }}
    >
      {row.getVisibleCells().map((cell) => {
        const extraClass = (cell.column.columnDef.meta as { cellClassName?: string } | undefined)?.cellClassName ?? ''
        return (
          <td key={cell.id} className={`px-4 py-2 align-middle ${extraClass}`}>
            {flexRender(cell.column.columnDef.cell, cell.getContext())}
          </td>
        )
      })}
    </tr>
  )
}

/**
 * Filter input that picks its UI from the column's `meta.filter` config.
 * For `select`, options derive from the data when not explicitly given;
 * for `date-range`, two native date inputs drive a range filterFn.
 */
function SmartFilterInput<T>({ column, data }: { column: Column<T, unknown>; data: T[] }) {
  const meta = column.columnDef.meta as
    | { filter?: FilterConfig; formatOption?: (raw: unknown) => string }
    | undefined
  const filter = meta?.filter ?? { type: 'text' }

  if (filter.type === 'select') {
    return <SelectFilter column={column} data={data} explicit={filter.options} formatOption={meta?.formatOption} />
  }
  if (filter.type === 'date-range') {
    return <DateRangeFilter column={column} />
  }
  // text — default, "contains" semantics via TanStack's includesString.
  return (
    <input
      value={(column.getFilterValue() as string) ?? ''}
      onChange={(e) => column.setFilterValue(e.target.value || undefined)}
      placeholder="filter…"
      className="px-2 py-0.5 text-xs rounded bg-surface-3 placeholder:text-text-muted focus:outline-none focus:ring-1 focus:ring-accent w-full"
    />
  )
}

function SelectFilter<T>({
  column, data, explicit, formatOption,
}: {
  column: Column<T, unknown>
  data: T[]
  explicit?: ReadonlyArray<{ value: string; label: string }>
  formatOption?: (raw: unknown) => string
}) {
  const id = column.id
  // When the caller didn't hand us options, derive them from the data
  // by collecting the unique non-null values in this column. Memoised
  // on (data, id) so the dropdown doesn't reshuffle on each render.
  const options = useMemo(() => {
    if (explicit && explicit.length > 0) return explicit
    const seen = new Set<string>()
    for (const row of data) {
      const v = (row as Record<string, unknown>)[id]
      if (v == null || v === '') continue
      seen.add(String(v))
    }
    return Array.from(seen).sort().map((v) => ({
      value: v,
      label: formatOption ? formatOption(v) : humanize(v),
    }))
  }, [explicit, data, id, formatOption])

  return (
    <select
      value={(column.getFilterValue() as string) ?? ''}
      onChange={(e) => column.setFilterValue(e.target.value || undefined)}
      className="px-1 py-0.5 text-xs rounded bg-surface-3 focus:outline-none focus:ring-1 focus:ring-accent w-full"
    >
      <option value="">All</option>
      {options.map((o) => (
        <option key={o.value} value={o.value}>{o.label}</option>
      ))}
    </select>
  )
}

function DateRangeFilter<T>({ column }: { column: Column<T, unknown> }) {
  const value = (column.getFilterValue() as { from?: string; to?: string } | undefined) ?? {}
  const set = (next: { from?: string; to?: string }) => {
    const cleaned = {
      from: next.from?.trim() || undefined,
      to: next.to?.trim() || undefined,
    }
    column.setFilterValue(cleaned.from || cleaned.to ? cleaned : undefined)
  }
  return (
    <div className="flex gap-1">
      <input
        type="date"
        value={value.from ?? ''}
        onChange={(e) => set({ ...value, from: e.target.value })}
        title="From (inclusive)"
        className="px-1 py-0.5 text-xs rounded bg-surface-3 focus:outline-none focus:ring-1 focus:ring-accent flex-1 min-w-0"
      />
      <input
        type="date"
        value={value.to ?? ''}
        onChange={(e) => set({ ...value, to: e.target.value })}
        title="To (inclusive)"
        className="px-1 py-0.5 text-xs rounded bg-surface-3 focus:outline-none focus:ring-1 focus:ring-accent flex-1 min-w-0"
      />
    </div>
  )
}

/**
 * Inclusive [from, to] date filter. Compares the column value as an
 * ISO string against the YYYY-MM-DD bounds; lexicographic compare works
 * for ISO strings, so no Date parsing required.
 */
function dateRangeFilterFn<T>(row: Row<T>, columnId: string, filterValue: unknown): boolean {
  const range = filterValue as { from?: string; to?: string } | undefined
  if (!range || (!range.from && !range.to)) return true
  const raw = row.getValue(columnId)
  if (raw == null) return false
  const iso = String(raw).slice(0, 10)  // YYYY-MM-DD
  if (range.from && iso < range.from) return false
  if (range.to && iso > range.to) return false
  return true
}

function SortIcon({ dir }: { dir: 'asc' | 'desc' | null }) {
  if (dir === 'asc') return <ArrowUp size={12} className="opacity-70" />
  if (dir === 'desc') return <ArrowDown size={12} className="opacity-70" />
  return <ArrowUpDown size={12} className="opacity-30" />
}

function selectColumn<T>(): ColumnDef<T, any> {
  return {
    id: '__select',
    header: ({ table }) => (
      <input
        type="checkbox"
        aria-label="Select all filtered rows"
        checked={table.getIsAllRowsSelected()}
        ref={(el) => {
          if (el) el.indeterminate = table.getIsSomeRowsSelected() && !table.getIsAllRowsSelected()
        }}
        onChange={table.getToggleAllRowsSelectedHandler()}
      />
    ),
    cell: ({ row }) => (
      <input
        type="checkbox"
        aria-label={`Select row ${row.id}`}
        checked={row.getIsSelected()}
        onChange={row.getToggleSelectedHandler()}
        onClick={(e) => e.stopPropagation()}
      />
    ),
    enableSorting: false,
    enableColumnFilter: false,
    size: 32,
  }
}
