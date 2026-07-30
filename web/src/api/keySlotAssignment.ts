import { api, ApiError } from './client'
import type { KeySlotDto, TerminalDto } from './types'

/**
 * Shared by the Registration wizard's Keys step and the standalone Keys page — both need the
 * exact same "auto-assign the next available node" behavior, so it lives here once rather than
 * being duplicated per call site.
 *
 * Lowest-unused-integer starting at 1, bounded by terminal.configuredSlotCount. A node counts as
 * available if no KeySlot row exists there at all, OR an ACTIVE KeySlot row exists but is
 * currently unassigned (managedKeyId null) — the latter case is what makes a deleted key's node
 * reusable: keys.js's DELETE route unlinks (not deletes) the KeySlot, and key-slots.js's own
 * duplicate-node-per-terminal check only rejects a second ACTIVE row at the same node, so an
 * unassigned row must be re-linked via PATCH rather than a fresh POST.
 */
export type AssignKeyNodeResult =
  | { ok: true; slot: KeySlotDto }
  | { ok: false; reason: 'CAPACITY_FULL' }
  | { ok: false; reason: 'ERROR'; message: string }

export async function assignKeyToNextAvailableNode(
  terminal: TerminalDto,
  keyId: string,
): Promise<AssignKeyNodeResult> {
  try {
    const slots = await api.listKeySlots(terminal.id)
    const byNode = new Map<number, KeySlotDto>()
    for (const slot of slots) byNode.set(slot.nodeAddress, slot)

    for (let node = 1; node <= terminal.configuredSlotCount; node += 1) {
      const existing = byNode.get(node)
      if (!existing) {
        const created = await api.createKeySlot({
          terminalId: terminal.id,
          nodeAddress: node,
          managedKeyId: keyId,
        })
        return { ok: true, slot: created }
      }
      if (!existing.managedKeyId) {
        const updated = await api.updateKeySlot(existing.id, {
          terminalId: existing.terminalId,
          nodeAddress: existing.nodeAddress,
          managedKeyId: keyId,
          expectedRevision: existing.revision,
        })
        return { ok: true, slot: updated }
      }
    }
    return { ok: false, reason: 'CAPACITY_FULL' }
  } catch (err) {
    return {
      ok: false,
      reason: 'ERROR',
      message: err instanceof ApiError ? err.message : 'Failed to assign a cabinet node for this key',
    }
  }
}

/** Preemptive capacity check so "Add key" can be disabled/hidden before even trying, per the
 * task's "don't let the UI 400 silently" instruction — the actual create call above is still the
 * source of truth (a race between two admins is still handled there, just less commonly hit). */
export async function countAvailableNodes(terminal: TerminalDto): Promise<number> {
  const slots = await api.listKeySlots(terminal.id)
  const occupiedNodes = new Set(slots.filter((slot) => slot.managedKeyId).map((slot) => slot.nodeAddress))
  return Math.max(0, terminal.configuredSlotCount - occupiedNodes.size)
}
