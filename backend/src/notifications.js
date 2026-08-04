/**
 * Super Admin live SSE notification channel (checkout-deadline warnings/overdue). Two pieces,
 * both purely in-process — no external pub/sub needed for a single-process deploy:
 *
 * 1. A short-lived, single-use "stream ticket" workaround for the fact that the browser's
 *    native EventSource cannot set an Authorization header — a real API limitation, not
 *    something to work around by putting the actual JWT in a query string (which would land
 *    in server/proxy access logs). A ticket is minted by a normal `requireAuth`-guarded POST
 *    right before the client opens the stream, is resolved to exactly one userId, and is
 *    burned (deleted) the instant it's looked up, whether or not it turns out to be expired —
 *    it is never valid for a second attempt.
 * 2. An in-process registry of currently-open Super Admin SSE connections (Map<userId,
 *    Set<Response>>), and `broadcastToSuperAdmins`, called by deadlineMonitor.js whenever a
 *    WARNING_15MIN/OVERDUE event fires. A user can have more than one open tab/window, hence a
 *    Set per user, not a single Response.
 */
import { randomUUID } from 'crypto';
import { nowMs } from './util.js';

const STREAM_TICKET_TTL_MS = 30_000;

const pendingStreamTickets = new Map(); // ticket -> { userId, expiresAtEpochMs }

export function mintStreamTicket(userId) {
  const ticket = randomUUID();
  pendingStreamTickets.set(ticket, { userId, expiresAtEpochMs: nowMs() + STREAM_TICKET_TTL_MS });
  return ticket;
}

/** Single-use: the ticket is deleted here regardless of outcome, valid or not. */
export function consumeStreamTicket(ticket) {
  const entry = pendingStreamTickets.get(ticket);
  if (!entry) return null;
  pendingStreamTickets.delete(ticket);
  if (entry.expiresAtEpochMs < nowMs()) return null;
  return entry.userId;
}

const superAdminConnections = new Map(); // userId -> Set<Response>

export function registerSuperAdminConnection(userId, res) {
  if (!superAdminConnections.has(userId)) {
    superAdminConnections.set(userId, new Set());
  }
  superAdminConnections.get(userId).add(res);
}

export function unregisterSuperAdminConnection(userId, res) {
  const set = superAdminConnections.get(userId);
  if (!set) return;
  set.delete(res);
  if (set.size === 0) superAdminConnections.delete(userId);
}

/** [eventType] e.g. 'CHECKOUT_WARNING_15MIN' / 'CHECKOUT_OVERDUE'. Silently drops if no Super
 * Admin currently has the portal open — there is no queue/replay, this is a live-popup channel
 * only, not a notification-history mechanism (that's the audit log / a future Alerts-tab view). */
export function broadcastToSuperAdmins(eventType, data) {
  const payload = `event: ${eventType}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const connections of superAdminConnections.values()) {
    for (const res of connections) {
      try {
        res.write(payload);
      } catch {
        // Connection is already gone; its own 'close' handler will unregister it.
      }
    }
  }
}
