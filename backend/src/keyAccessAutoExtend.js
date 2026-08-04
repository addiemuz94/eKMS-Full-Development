/**
 * PIN-expiry recurring auto-extend for key_access_requests (Only B Technician + Vendor
 * two-stage requests). In-process setInterval, same shape as deadlineMonitor.js (no external
 * scheduler/queue — a single Node process on the VPS with no existing worker infra) — a
 * dedicated file/interval rather than folded into deadlineMonitor.js's own tick, so this
 * feature ships independently of that one's separate deploy status and a slow/broken query in
 * either job can never block the other (see CLAUDE_MOBILE.md/CLAUDE_BACKEND.md for the
 * discovery/reasoning).
 *
 * An APPROVED request whose passkey_expires_at_epoch_ms lapses without ever being used
 * (first_used_at_epoch_ms still NULL) is extended by 1 hour — both passkey_expires_at_epoch_ms
 * and return_at_epoch_ms together (a confirmed product decision: the requester's displayed
 * pickup/return window is meant to reflect the current, possibly-extended commitment, not just
 * the original ask — see KeyAccessRequestScreen.kt's windowLabel()). This repeats every tick,
 * indefinitely, until first_used_at_epoch_ms is stamped (passkeyLogin(), on first successful
 * terminal login) or the request reaches a terminal status (CANCELLED/REVOKED/EXPIRED — all
 * excluded by this query's own `status = 'APPROVED'` filter).
 *
 * Requires expireOverdueRequests()'s passkey_expires_at_epoch_ms/APPROVED lazy-expire branch to
 * be narrowed away (see keyAccessRequests.js) — otherwise a live API call landing between two
 * ticks would flip the row to EXPIRED before this job ever gets to extend it.
 */
import pool from './db.js';
import { sendPushToUser } from './fcm.js';
import { nowMs, writeAudit } from './util.js';

const TICK_INTERVAL_MILLIS = 60_000;
const EXTEND_BY_MILLIS = 60 * 60_000; // 1 hour — the extension amount, not the tick cadence.

async function extendLapsedRequests() {
  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT id, requester_user_id, site_id, passkey_expires_at_epoch_ms, return_at_epoch_ms
     FROM key_access_requests
     WHERE status = 'APPROVED'
       AND passkey_expires_at_epoch_ms IS NOT NULL
       AND passkey_expires_at_epoch_ms < :now
       AND first_used_at_epoch_ms IS NULL`,
    { now },
  );

  for (const row of rows) {
    const oldPasskeyExpiry = Number(row.passkey_expires_at_epoch_ms);
    const newPasskeyExpiry = oldPasskeyExpiry + EXTEND_BY_MILLIS;
    const newReturnAt = row.return_at_epoch_ms == null
      ? null
      : Number(row.return_at_epoch_ms) + EXTEND_BY_MILLIS;

    // Double-checked guard against a concurrent status change or first-use landing between the
    // SELECT above and this UPDATE (no expectedRevision column exists on this table — see
    // migration 009's design note; every other write to this table uses the same
    // status-in-the-WHERE-clause guard instead). Re-checking the exact old expiry value we read
    // additionally guards against two overlapping ticks (belt-and-suspenders alongside
    // tickInProgress below) double-extending the same row.
    const [result] = await pool.execute(
      `UPDATE key_access_requests SET
         passkey_expires_at_epoch_ms = :newPasskeyExpiry,
         return_at_epoch_ms = :newReturnAt
       WHERE id = :id
         AND status = 'APPROVED'
         AND first_used_at_epoch_ms IS NULL
         AND passkey_expires_at_epoch_ms = :oldPasskeyExpiry`,
      { id: row.id, newPasskeyExpiry, newReturnAt, oldPasskeyExpiry },
    );
    if (result.affectedRows === 0) continue; // raced with something else this tick — next tick re-evaluates

    await writeAudit({
      eventType: 'KEY_ACCESS_REQUEST_AUTO_EXTENDED',
      // No actorUserId — system-triggered, not a real user action.
      siteId: row.site_id,
      entityType: 'KEY_ACCESS_REQUEST',
      entityId: row.id,
      detail: `Auto-extended by 1 hour (unused); new expiry ${new Date(newPasskeyExpiry).toISOString()}`,
    });

    await sendPushToUser(
      row.requester_user_id,
      'Key access extended',
      'Your approved PIN was not used in time, so its window was extended by 1 hour.',
      { requestId: row.id, stage: 'AUTO_EXTENDED', newExpiresAtEpochMillis: newPasskeyExpiry },
    );
  }
}

let tickInProgress = false;

async function tick() {
  if (tickInProgress) return;
  tickInProgress = true;
  try {
    await extendLapsedRequests();
  } catch (err) {
    console.error('[keyAccessAutoExtend] tick failed', err);
  } finally {
    tickInProgress = false;
  }
}

export function startKeyAccessAutoExtend() {
  setInterval(() => {
    tick();
  }, TICK_INTERVAL_MILLIS);
}
