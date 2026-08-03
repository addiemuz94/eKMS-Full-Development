import type { FormEvent } from 'react'
import { parseCabinetLayout } from '../api/cabinetLayout'

type Props = {
  rows: string
  columns: string
  onRowsChange: (value: string) => void
  onColumnsChange: (value: string) => void
  /** Optional compact hint under the fields. */
  hint?: string
}

/**
 * Rows × Columns capacity editor — used on Registration, Cabinet settings, and Keys layout.
 */
export function CabinetLayoutFields({
  rows,
  columns,
  onRowsChange,
  onColumnsChange,
  hint = 'Total key slots = rows × columns (maximum 127). This sets the cabinet layout grid.',
}: Props) {
  const layout = parseCabinetLayout(rows, columns)
  const total = layout.ok ? String(layout.value.totalSlots) : '—'

  return (
    <div className="cabinet-layout-fields">
      <div className="split">
        <div className="field">
          <label>Rows</label>
          <input
            type="number"
            min={1}
            max={127}
            value={rows}
            onChange={(e) => onRowsChange(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label>Columns</label>
          <input
            type="number"
            min={1}
            max={127}
            value={columns}
            onChange={(e) => onColumnsChange(e.target.value)}
            required
          />
        </div>
      </div>
      <div className="field">
        <label>Total key slots</label>
        <input value={total} readOnly disabled />
      </div>
      {hint && (
        <p className="muted" style={{ marginTop: -4, fontSize: 13 }}>
          {hint}
        </p>
      )}
    </div>
  )
}

/** Prevent form submit bubbling when nested; parent form owns submit. */
export function stopNestedSubmit(e: FormEvent) {
  e.preventDefault()
}
