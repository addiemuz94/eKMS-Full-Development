/**
 * Live SSE notification channel (checkout-deadline warnings/overdue) for Super Admin and
 * Regional Admin portal sessions. Two pieces, both purely in-process — no external pub/sub
 * needed for a single-process deploy:
 *
 * 1. A short-lived, single-use "stream ticket" workaround for the fact that the browser's
 *    native EventSource cannot set an Authorization header — a real API limitation, not
 *    something to work around by putting the actual JWT in a query string (which would land
 *    in server/proxy access logs). A ticket is minted by a normal `requireAuth`-guarded POST
 *    right before the client opens the stream, is resolved to exactly one connection identity
 *    (userId + role + optional siteIds), and is burned (deleted) the instant it's looked up,
 *    whether or not it turns out to be expired — it is never valid for a second attempt.
 * 2. An in-process registry of currently-open portal SSE connections, and
 *    `broadcastCheckoutDeadline`, called by deadlineMonitor.js whenever a WARNING_15MIN/
 *    OVERDUE event fires. Super Admins receive every event; Regional Admins only receive
 *    events whose site is in their own `user_site_assignments` — site-based directly, as of the
 *    "regional confusion" Tier 1 rework (previously routed through the site's region and
 *    `user_region_assignments`). A user can have more than one open tab/window, hence a Set per
 *    user, not a single Response.
 */
import { randomUUID } from 'crypto';
import { nowMs } from './util.js';

const STREAM_TICKET_TTL_MS = 30_000;

/** ticket -> { userId, role, siteIds: string[] | null, expiresAtEpochMs } */
const pendingStreamTickets = new Map();

/**
 * @param {string} userId
 * @param {{ role: string, siteIds?: string[] | null }} meta
 *   Super Admin: siteIds omitted/null (receives all). Regional Admin: assigned site ids.
 */
export function mintStreamTicket(userId, { role, siteIds = null } = {}) {
  const ticket = randomUUID();
  pendingStreamTickets.set(ticket, {
    userId,
    role,
    siteIds: role === 'REGIONAL_ADMIN' ? [...(siteIds ?? [])] : null,
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
    siteIds: role === 'REGIONAL_ADMIN' ? new Set(siteIds ?? []) : null,
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
 * Fan-out a checkout-deadline event to open portal SSE sessions.
 * Super Admins always receive it. Regional Admins receive it only when `siteId` is non-null and
 * listed in their own assigned sites — site-based directly, as of the "regional confusion"
 * Tier 1 rework (a site with no Regional Admin assigned to it via user_site_assignments → no RA
 * SSE, matching FCM's notifyRegionalAdminsForSite).
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
        entry.role === 'REGIONAL_ADMIN' &&
        siteId &&
        entry.siteIds &&
        entry.siteIds.has(siteId)
      ) {
        writeEvent(entry.res, eventType, data);
      }
    }
  }
}

/** @deprecated Prefer broadcastCheckoutDeadline. Still fans out to SUPER_ADMIN connections only. */
export function broadcastToSuperAdmins(eventType, data) {
  broadcastCheckoutDeadline(eventType, data, { siteId: null });
}
