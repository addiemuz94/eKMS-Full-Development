/**
 * Checkout-deadline notification monitor. In-process setInterval (no external
 * scheduler/queue — a single Node process on the VPS with no existing worker infra, see
 * CLAUDE_BACKEND.md) started once at server boot from index.js. Each tick checks for two
 * events, each firing exactly once per checkout via key_checkout_notifications' UNIQUE KEY:
 * a 15-minute-before-due warning, and the moment a checkout becomes overdue.
 *
 * Recipients per event: the technician/vendor who took the key (checkout.user_id, real FCM
 * push via sendPushToUser); every Regional Admin covering the checkout's site
 * (notifyRegionalAdminsForSite, reused as-is from keyAccessRequests.js — a site with no
 * region_id yet routes to nobody, a deliberate, already-established decision, not a gap: see
 * that function's own doc and migration 009's comment); Super Admins + covering Regional
 * Admins via the portal SSE broadcast (not push for SA; RA also get FCM above) — channels are
 * complementary, not an either/or.
 */
import pool from './db.js';
import { notifyRegionalAdminsForSite } from './routes/keyAccessRequests.js';
import { sendPushToUser } from './fcm.js';
import { broadcastCheckoutDeadline } from './notifications.js';
import { newId, nowMs, regionIdForSite } from './util.js';

const TICK_INTERVAL_MILLIS = 60_000;
const WARNING_WINDOW_MILLIS = 15 * 60_000;

async function recordNotification(checkoutId, notificationType) {
  // Rely on the UNIQUE KEY (checkout_id, notification_type) as the actual safety net, not a
  // separate pre-check right before this insert — the SELECT above already filters out
  // already-notified checkouts; this only guards the rare case of two ticks somehow both
  // reaching the same checkout (e.g. a slow tick overlapping the next one), by no-op'ing
  // instead of throwing.
  await pool.execute(
    `INSERT INTO key_checkout_notifications (id, checkout_id, notification_type, sent_at_epoch_ms)
     VALUES (:id, :checkoutId, :notificationType, :now)
     ON DUPLICATE KEY UPDATE id = id`,
    { id: newId(), checkoutId, notificationType, now: nowMs() },
  );
}

async function notifyCheckout(row, { title, body, eventType }) {
  const data = {
    checkoutId: row.id,
    keyId: row.key_id,
    siteId: row.site_id,
    dueAtEpochMillis: row.due_at_epoch_ms,
    keyDisplayName: row.key_display_name,
    terminalName: row.terminal_name,
  };
  await sendPushToUser(row.user_id, title, body, data);
  await notifyRegionalAdminsForSite(row.site_id, title, body, data);
  const regionId = await regionIdForSite(row.site_id);
  broadcastCheckoutDeadline(eventType, data, { regionId });
}

async function checkWarnings() {
  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT kc.id, kc.key_id, kc.user_id, kc.due_at_epoch_ms, t.site_id,
            mk.display_name AS key_display_name, t.name AS terminal_name
     FROM key_checkouts kc
     JOIN terminals t ON t.id = kc.terminal_id
     JOIN managed_keys mk ON mk.id = kc.key_id
     LEFT JOIN key_checkout_notifications n
       ON n.checkout_id = kc.id AND n.notification_type = 'WARNING_15MIN'
     WHERE kc.status = 'OPEN'
       AND kc.due_at_epoch_ms > :now
       AND kc.due_at_epoch_ms <= :warningThreshold
       AND n.id IS NULL`,
    { now, warningThreshold: now + WARNING_WINDOW_MILLIS },
  );
  for (const row of rows) {
    await notifyCheckout(row, {
      title: 'Key return due soon',
      body: `${row.key_display_name} at ${row.terminal_name} is due back in 15 minutes.`,
      eventType: 'CHECKOUT_WARNING_15MIN',
    });
    await recordNotification(row.id, 'WARNING_15MIN');
  }
}

async function checkOverdue() {
  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT kc.id, kc.key_id, kc.user_id, kc.due_at_epoch_ms, t.site_id,
            mk.display_name AS key_display_name, t.name AS terminal_name
     FROM key_checkouts kc
     JOIN terminals t ON t.id = kc.terminal_id
     JOIN managed_keys mk ON mk.id = kc.key_id
     LEFT JOIN key_checkout_notifications n
       ON n.checkout_id = kc.id AND n.notification_type = 'OVERDUE'
     WHERE kc.status = 'OPEN'
       AND kc.due_at_epoch_ms <= :now
       AND n.id IS NULL`,
    { now },
  );
  for (const row of rows) {
    await notifyCheckout(row, {
      title: 'Key return overdue',
      body: `${row.key_display_name} at ${row.terminal_name} is now overdue.`,
      eventType: 'CHECKOUT_OVERDUE',
    });
    await recordNotification(row.id, 'OVERDUE');
  }
}

let tickInProgress = false;

async function tick() {
  if (tickInProgress) return;
  tickInProgress = true;
  try {
    await checkWarnings();
    await checkOverdue();
  } catch (err) {
    console.error('[deadlineMonitor] tick failed', err);
  } finally {
    tickInProgress = false;
  }
}

export function startDeadlineMonitor() {
  setInterval(() => {
    tick();
  }, TICK_INTERVAL_MILLIS);
}
