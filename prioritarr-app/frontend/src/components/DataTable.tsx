/**
 * Generic TanStack-Table wrapper used by Series + Downloads pages.
 *
 * Wires sort-by-header-click, per-column text filters, row selection
 * (checkbox), and row-click handoff. Stays presentation-only — callers
 * own the data, the filtered/selected state is reported back via
 * callbacks so each page can render its own bulk-action bar.
 */

import {
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
import { useEffect, useState } from 'react'

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

  // Prepend a selection column if requested. We build it inline rather
  // than export a helper so the caller's columns array stays untouched.
  const tableColumns: ColumnDef<T, any>[] = enableSelection
    ? [selectColumn<T>(), ...columns]
    : columns

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
    <div className="bg-surface-1 rounded-lg border border-surface-3 overflow-hidden">
      <table className="w-full text-sm">
        <thead className="bg-surface-2 text-left">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => {
                const col = h.column
                const sortDir = col.getIsSorted()
                return (
                  <th key={h.id} className="px-4 py-2 align-top">
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
                        <input
                          value={(col.getFilterValue() as string) ?? ''}
                          onChange={(e) => col.setFilterValue(e.target.value || undefined)}
                          placeholder="filter…"
                          className="px-2 py-0.5 text-xs rounded bg-surface-3 placeholder:text-text-muted focus:outline-none focus:ring-1 focus:ring-accent w-full"
                        />
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
      {row.getVisibleCells().map((cell) => (
        <td key={cell.id} className="px-4 py-2 align-middle">
          {flexRender(cell.column.columnDef.cell, cell.getContext())}
        </td>
      ))}
    </tr>
  )
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
