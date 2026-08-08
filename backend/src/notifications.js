/**
 * Live SSE notification channel for portal sessions (checkout-deadline + key-access).
 * Two pieces, both purely in-process — no external pub/sub needed for a single-process deploy:
 *
 * 1. A short-lived, single-use "stream ticket" workaround for the fact that the browser's
 *    native EventSource cannot set an Authorization header — a real API limitation, not
 *    something to work around by putting the actual JWT in a query string (which would land
 *    in server/proxy access logs). A ticket is minted by a normal `requireAuth`-guarded POST
 *    right before the client opens the stream, is resolved to exactly one connection identity
 *    (userId + role + optional siteIds), and is burned (deleted) the instant it's looked up,
 *    whether or not it turns out to be expired — it is never valid for a second attempt.
 * 2. An in-process registry of currently-open portal SSE connections, plus fan-out helpers
 *    (`broadcastCheckoutDeadline`, `broadcastKeyAccess`, `broadcastToUser`) used by
 *    deadlineMonitor.js and keyAccessRequests.js.
 *
 * Recipients:
 * - Super Admin: every checkout + key-access event
 * - Regional Admin / Technician: site-scoped checkout events; key-access when site matches
 *   or when they are an explicit target (e.g. PIC on a Vendor PENDING_PIC request)
 * - Vendor: only direct `broadcastToUser` events (own request status)
 */
import { randomUUID } from 'crypto';
import { nowMs } from './util.js';

const STREAM_TICKET_TTL_MS = 30_000;

/** Roles that receive site-filtered checkout / key-access broadcasts. */
const SITE_SCOPED_ROLES = new Set(['REGIONAL_ADMIN', 'TECHNICIAN']);

/** ticket -> { userId, role, siteIds: string[] | null, expiresAtEpochMs } */
const pendingStreamTickets = new Map();

/**
 * @param {string} userId
 * @param {{ role: string, siteIds?: string[] | null }} meta
 *   Super Admin / Vendor: siteIds omitted/null. Regional Admin / Technician: assigned site ids.
 */
export function mintStreamTicket(userId, { role, siteIds = null } = {}) {
  const ticket = randomUUID();
  pendingStreamTickets.set(ticket, {
    userId,
    role,
    siteIds: SITE_SCOPED_ROLES.has(role) ? [...(siteIds ?? [])] : null,
    expiresAtEpochMs: nowMs() + STREAM_TICKET_TTL_MS,
  });
  return ticket;
}

/** Single-use: the ticket is deleted here regardless of outcome, valid or not.
 *  @returns {{ userId: string, role: string, siteIds: string[] | null } | null} */
export function consumeStreamTicket(ticket) {
  const entry = pendingStreamTickets.get(ticket);
  if (!entry) return null;
  pendingStreamTickets.delete(ticket);
  if (entry.expiresAtEpochMs < nowMs()) return null;
  return {
    userId: entry.userId,
    role: entry.role,
    siteIds: entry.siteIds,
  };
}

/** userId -> Set<{ res, role, siteIds: Set<string> | null }> */
const adminConnections = new Map();

export function registerAdminConnection(userId, res, { role, siteIds = null } = {}) {
  if (!adminConnections.has(userId)) {
    adminConnections.set(userId, new Set());
  }
  adminConnections.get(userId).add({
    res,
    role,
    siteIds: SITE_SCOPED_ROLES.has(role) ? new Set(siteIds ?? []) : null,
  });
}

export function unregisterAdminConnection(userId, res) {
  const set = adminConnections.get(userId);
  if (!set) return;
  for (const entry of set) {
    if (entry.res === res) {
      set.delete(entry);
      break;
    }
  }
  if (set.size === 0) adminConnections.delete(userId);
}

/** @deprecated Prefer registerAdminConnection — kept as alias for any leftover callers. */
export function registerSuperAdminConnection(userId, res) {
  registerAdminConnection(userId, res, { role: 'SUPER_ADMIN', siteIds: null });
}

/** @deprecated Prefer unregisterAdminConnection. */
export function unregisterSuperAdminConnection(userId, res) {
  unregisterAdminConnection(userId, res);
}

function writeEvent(res, eventType, data) {
  const payload = `event: ${eventType}\ndata: ${JSON.stringify(data)}\n\n`;
  try {
    res.write(payload);
  } catch {
    // Connection is already gone; its own 'close' handler will unregister it.
  }
}

/**
 * Fan-out to every open SSE tab for one user (PIC / requester / etc.).
 * @param {string} userId
 * @param {string} eventType
 * @param {object} data
 */
export function broadcastToUser(userId, eventType, data) {
  if (!userId) return;
  const connections = adminConnections.get(userId);
  if (!connections) return;
  for (const entry of connections) {
    writeEvent(entry.res, eventType, data);
  }
}

/**
 * Fan-out a checkout-deadline event to open portal SSE sessions.
 * Super Admins always. Regional Admin / Technician when `siteId` is in their assignments.
 *
 * @param {string} eventType e.g. 'CHECKOUT_WARNING_15MIN' / 'CHECKOUT_OVERDUE'
 * @param {object} data
 * @param {{ siteId?: string | null }} [opts]
 */
export function broadcastCheckoutDeadline(eventType, data, { siteId = null } = {}) {
  for (const connections of adminConnections.values()) {
    for (const entry of connections) {
      if (entry.role === 'SUPER_ADMIN') {
        writeEvent(entry.res, eventType, data);
        continue;
      }
      if (
        SITE_SCOPED_ROLES.has(entry.role) &&
        siteId &&
        entry.siteIds &&
        entry.siteIds.has(siteId)
      ) {
        writeEvent(entry.res, eventType, data);
      }
    }
  }
}

/**
 * Fan-out a key-access lifecycle event.
 * Super Admins always. Regional Admins when site matches. Plus any explicit target user ids
 * (e.g. the assigned PIC, the requester). Technicians are not site-broadcast for key-access —
 * they only receive events when listed in targetUserIds (PIC inbox / own request).
 *
 * @param {string} eventType e.g. 'KEY_ACCESS_PENDING_PIC' / 'KEY_ACCESS_PENDING_RA' / …
 * @param {object} data
 * @param {{ siteId?: string | null, targetUserIds?: string[] }} [opts]
 */
export function broadcastKeyAccess(eventType, data, { siteId = null, targetUserIds = [] } = {}) {
  for (const connections of adminConnections.values()) {
    for (const entry of connections) {
      if (entry.role === 'SUPER_ADMIN') {
        writeEvent(entry.res, eventType, data);
        continue;
      }
      if (
        entry.role === 'REGIONAL_ADMIN' &&
        siteId &&
        entry.siteIds &&
        entry.siteIds.has(siteId)
      ) {
        writeEvent(entry.res, eventType, data);
      }
    }
  }
  const seen = new Set();
  for (const uid of targetUserIds) {
    if (!uid || seen.has(uid)) continue;
    seen.add(uid);
    broadcastToUser(uid, eventType, data);
  }
}

/** @deprecated Prefer broadcastCheckoutDeadline. Still fans out to SUPER_ADMIN connections only. */
export function broadcastToSuperAdmins(eventType, data) {
  broadcastCheckoutDeadline(eventType, data, { siteId: null });
}
