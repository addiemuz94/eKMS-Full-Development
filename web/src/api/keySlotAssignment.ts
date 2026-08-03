import { api, ApiError } from './client'
import type { KeySlotDto, TerminalDto } from './types'

/**
 * Shared by Registration Keys + Cabinet Management Keys — assign / expand / shrink
 * physical cabinet nodes (1..configuredSlotCount).
 */

export type AssignKeyNodeResult =
  | { ok: true; slot: KeySlotDto }
  | { ok: false; reason: 'CAPACITY_FULL' | 'NODE_TAKEN' | 'INVALID_NODE' }
  | { ok: false; reason: 'ERROR'; message: string }

function occupiedNodes(slots: KeySlotDto[], terminalId: string): Set<number> {
  return new Set(
    slots
      .filter((slot) => slot.terminalId === terminalId && slot.managedKeyId)
      .map((slot) => slot.nodeAddress),
  )
}

export function listFreeNodeAddresses(
  terminal: TerminalDto,
  slots: KeySlotDto[],
): number[] {
  const taken = occupiedNodes(slots, terminal.id)
  const free: number[] = []
  for (let node = 1; node <= terminal.configuredSlotCount; node += 1) {
    if (!taken.has(node)) free.push(node)
  }
  return free
}

async function linkOrCreateSlot(
  terminal: TerminalDto,
  keyId: string,
  node: number,
  slots: KeySlotDto[],
): Promise<KeySlotDto> {
  const existing = slots.find((s) => s.terminalId === terminal.id && s.nodeAddress === node)
  if (!existing) {
    return api.createKeySlot({
      terminalId: terminal.id,
      nodeAddress: node,
      managedKeyId: keyId,
    })
  }
  if (existing.managedKeyId && existing.managedKeyId !== keyId) {
    throw new ApiError(400, 'Node address already assigned on this terminal')
  }
  if (existing.managedKeyId === keyId) return existing
  return api.updateKeySlot(existing.id, {
    terminalId: existing.terminalId,
    nodeAddress: existing.nodeAddress,
    managedKeyId: keyId,
    expectedRevision: existing.revision,
  })
}

async function unlinkKeyFromSlots(keyId: string, slots: KeySlotDto[]): Promise<void> {
  for (const slot of slots.filter((s) => s.managedKeyId === keyId)) {
    await api.updateKeySlot(slot.id, {
      terminalId: slot.terminalId,
      nodeAddress: slot.nodeAddress,
      managedKeyId: null,
      expectedRevision: slot.revision,
    })
  }
}

export async function assignKeyToNextAvailableNode(
  terminal: TerminalDto,
  keyId: string,
): Promise<AssignKeyNodeResult> {
  try {
    const slots = await api.listKeySlots(terminal.id)
    const free = listFreeNodeAddresses(terminal, slots)
    if (!free.length) return { ok: false, reason: 'CAPACITY_FULL' }
    const slot = await linkOrCreateSlot(terminal, keyId, free[0], slots)
    return { ok: true, slot }
  } catch (err) {
    return {
      ok: false,
      reason: 'ERROR',
      message: err instanceof ApiError ? err.message : 'Failed to assign a cabinet slot for this key',
    }
  }
}

export async function assignKeyToNode(
  terminal: TerminalDto,
  keyId: string,
  nodeAddress: number,
): Promise<AssignKeyNodeResult> {
  try {
    if (!Number.isInteger(nodeAddress) || nodeAddress < 1) {
      return { ok: false, reason: 'INVALID_NODE' }
    }
    if (nodeAddress > terminal.configuredSlotCount) {
      return { ok: false, reason: 'INVALID_NODE' }
    }
    const slots = await api.listKeySlots(terminal.id)
    const occupant = slots.find(
      (s) =>
        s.terminalId === terminal.id &&
        s.nodeAddress === nodeAddress &&
        s.managedKeyId &&
        s.managedKeyId !== keyId,
    )
    if (occupant) return { ok: false, reason: 'NODE_TAKEN' }
    await unlinkKeyFromSlots(keyId, slots)
    const refreshed = await api.listKeySlots(terminal.id)
    const slot = await linkOrCreateSlot(terminal, keyId, nodeAddress, refreshed)
    return { ok: true, slot }
  } catch (err) {
    return {
      ok: false,
      reason: 'ERROR',
      message: err instanceof ApiError ? err.message : 'Failed to assign this cabinet slot',
    }
  }
}

export async function countAvailableNodes(terminal: TerminalDto): Promise<number> {
  const slots = await api.listKeySlots(terminal.id)
  return listFreeNodeAddresses(terminal, slots).length
}

/** Append one free key slot by raising configuredSlotCount (max 127). */
export async function addCabinetKeySlot(terminal: TerminalDto): Promise<TerminalDto> {
  if (terminal.configuredSlotCount >= 127) {
    throw new ApiError(400, 'Cabinet already has the maximum of 127 key slots.')
  }
  const nextCount = terminal.configuredSlotCount + 1
  let nodeRows = terminal.nodeRows ?? null
  let nodesPerRow = terminal.nodesPerRow ?? null
  if (nodeRows && nodesPerRow && nextCount > nodeRows * nodesPerRow) {
    // Keep slots-per-row; add another row when the grid would overflow.
    nodeRows = Math.ceil(nextCount / nodesPerRow)
  }
  return api.updateTerminal(terminal.id, {
    siteId: terminal.siteId,
    name: terminal.name,
    boxAddress: terminal.boxAddress,
    serialNumber: terminal.serialNumber ?? null,
    configuredSlotCount: nextCount,
    vendorDeviceId: terminal.vendorDeviceId ?? null,
    nodeRows,
    nodesPerRow,
    latitude: terminal.latitude ?? null,
    longitude: terminal.longitude ?? null,
    expectedRevision: terminal.revision,
  })
}

/**
 * Remove the last key slot when it is free. Soft-deletes any empty KeySlot row at that node.
 */
export async function removeTrailingFreeKeySlot(
  terminal: TerminalDto,
): Promise<TerminalDto> {
  if (terminal.configuredSlotCount <= 1) {
    throw new ApiError(400, 'Cabinet must keep at least one key slot.')
  }
  const last = terminal.configuredSlotCount
  const slots = await api.listKeySlots(terminal.id)
  const lastSlot = slots.find((s) => s.terminalId === terminal.id && s.nodeAddress === last)
  if (lastSlot?.managedKeyId) {
    throw new ApiError(400, `Slot ${last} still has a key assigned. Delete or move the key first.`)
  }
  if (lastSlot) {
    await api.deleteKeySlot(lastSlot.id)
  }
  const nextCount = terminal.configuredSlotCount - 1
  let nodeRows = terminal.nodeRows ?? null
  const nodesPerRow = terminal.nodesPerRow ?? null
  if (nodeRows && nodesPerRow) {
    nodeRows = Math.max(1, Math.ceil(nextCount / nodesPerRow))
  }
  return api.updateTerminal(terminal.id, {
    siteId: terminal.siteId,
    name: terminal.name,
    boxAddress: terminal.boxAddress,
    serialNumber: terminal.serialNumber ?? null,
    configuredSlotCount: nextCount,
    vendorDeviceId: terminal.vendorDeviceId ?? null,
    nodeRows,
    nodesPerRow,
    latitude: terminal.latitude ?? null,
    longitude: terminal.longitude ?? null,
    expectedRevision: terminal.revision,
  })
}
