/** Shared cabinet layout helpers for Registration + Cabinet Management + Keys. */

export type CabinetLayoutInput = {
  rows: number
  columns: number
  totalSlots: number
}

export function parseCabinetLayout(
  rowsRaw: string,
  columnsRaw: string,
): { ok: true; value: CabinetLayoutInput } | { ok: false; message: string } {
  const rows = Number(rowsRaw)
  const columns = Number(columnsRaw)
  if (!Number.isFinite(rows) || !Number.isInteger(rows) || rows < 1 || rows > 127) {
    return { ok: false, message: 'Rows must be a whole number between 1 and 127.' }
  }
  if (!Number.isFinite(columns) || !Number.isInteger(columns) || columns < 1 || columns > 127) {
    return { ok: false, message: 'Columns must be a whole number between 1 and 127.' }
  }
  const totalSlots = rows * columns
  if (totalSlots < 1 || totalSlots > 127) {
    return {
      ok: false,
      message: `Rows × columns must total between 1 and 127 (currently ${totalSlots}).`,
    }
  }
  return { ok: true, value: { rows, columns, totalSlots } }
}

export function layoutSummary(terminal: {
  configuredSlotCount: number
  nodeRows?: number | null
  nodesPerRow?: number | null
}): string {
  if (terminal.nodeRows && terminal.nodesPerRow) {
    return `${terminal.nodeRows} rows × ${terminal.nodesPerRow} columns (${terminal.configuredSlotCount} slots)`
  }
  return `${terminal.configuredSlotCount} key slots`
}

/** Infer rows/columns strings for form fields from a terminal. */
export function layoutFieldsFromTerminal(terminal: {
  configuredSlotCount: number
  nodeRows?: number | null
  nodesPerRow?: number | null
}): { rows: string; columns: string } {
  if (terminal.nodeRows && terminal.nodeRows > 0 && terminal.nodesPerRow && terminal.nodesPerRow > 0) {
    return { rows: String(terminal.nodeRows), columns: String(terminal.nodesPerRow) }
  }
  const total = Math.max(1, Math.min(127, terminal.configuredSlotCount || 24))
  const columns = total <= 6 ? total : total <= 12 ? 6 : total <= 24 ? 8 : 10
  const rows = Math.max(1, Math.ceil(total / columns))
  return { rows: String(rows), columns: String(columns) }
}
