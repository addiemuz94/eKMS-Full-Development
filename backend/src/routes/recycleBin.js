import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, newId, notFound, nowMs, writeAudit } from '../util.js';

const router = Router();

const RETENTION_DAYS = 60;
const MILLIS_PER_DAY = 24 * 60 * 60 * 1000;

const ENTITY_TABLES = {
  SITE: { table: 'sites', labelSql: 'name', siteCol: 'id' },
  TERMINAL: { table: 'terminals', labelSql: 'name', siteCol: 'site_id' },
  USER: { table: 'users', labelSql: 'display_name', siteCol: null },
  KEY: { table: 'managed_keys', labelSql: 'display_name', siteCol: 'site_id' },
  KEY_SLOT: { table: 'key_slots', labelSql: "CONCAT('Slot ', node_address)", siteCol: null },
  ACCESS_GRANT: { table: 'access_grants', labelSql: "CONCAT('Grant ', id)", siteCol: 'site_id' },
};

function expiresAt(deletedAt) {
  return Number(deletedAt) + RETENTION_DAYS * MILLIS_PER_DAY;
}

/**
 * Purge dependent-row cleanup (Tier 2 of the Aug 2026 live-audit fix pass) — mirrors flush.js's
 * hardDeleteKeys() / hardDeleteTerminals() / hardDeleteSites() table set and statement order
 * exactly, scoped to a single record's id instead of the whole table. Permanently purging a
 * Recycle Bin record hard-deletes it; anything with an FK pointing at it must be cleared first or
 * the final DELETE throws — and FK constraints don't care whether the referencing row is itself
 * ACTIVE or already RECYCLE_BIN, only that the row still exists.
 *
 * Same guard posture as flush.js throughout: statements that clear an auxiliary/optional
 * dependent table are wrapped in `.catch(() => undefined)` (a no-op table/row is fine); the
 * statements that delete the entity row itself (managed_keys / terminals / sites / the linking
 * table cleared right before them) are left UNGUARDED on purpose, matching flush.js's own
 * hardDeleteAccessGrants/hardDeleteKeys/hardDeleteTerminals — if something unexpected still
 * blocks the real delete, that should surface as a real error, not silently no-op and leave an
 * orphaned row while the caller thinks the purge succeeded.
 *
 * One deliberate addition beyond a literal line-for-line mirror: flush.js's hardDeleteKeys()
 * doesn't touch access_grant_keys itself, because flush.js's callers always run the whole-table
 * hardDeleteAccessGrants() first for any scope that includes KEYS. A single-record purge can't
 * reuse that — wiping every access grant in the system to purge one key would be far more
 * destructive than intended — so purgeKeyDependents() clears just this key's own
 * access_grant_keys rows (same table hardDeleteAccessGrants clears, scoped down), left unguarded
 * to match hardDeleteAccessGrants's own unguarded posture.
 *
 * Known inherited gap, not fixed here: key_checkouts deletes below use the same
 * `.catch(() => undefined)` flush.js currently uses, which silently swallows the
 * key_checkout_notifications (migration 012) FK violation described in the Tier 3 flush.js fix.
 * A KEY/TERMINAL purge that hits a checkout with a notification row will inherit that same
 * failure mode until the equivalent fix lands here too — see CLAUDE_BACKEND.md's Tier 2 entry.
 */
async function purgeKeyDependents(conn, keyId) {
  await conn.execute(`UPDATE key_slots SET managed_key_id = NULL WHERE managed_key_id = :keyId`, { keyId });
  await conn.execute(`DELETE FROM access_grant_keys WHERE key_id = :keyId`, { keyId });
  await conn.execute(`DELETE FROM key_access_request_keys WHERE key_id = :keyId`, { keyId }).catch(() => undefined);
  await conn.execute(`DELETE FROM appointment_keys WHERE key_id = :keyId`, { keyId }).catch(() => undefined);
  await conn.execute(`DELETE FROM key_checkouts WHERE key_id = :keyId`, { keyId }).catch(() => undefined);
}

/** Mirrors flush.js's hardDeleteTerminals() — including its "keys bound only via slots at this
 * terminal are hard-deleted too" behavior, not just unlinked. */
async function purgeTerminalDependents(conn, terminalId) {
  const [keyRows] = await conn.execute(
    `SELECT DISTINCT managed_key_id AS id FROM key_slots
     WHERE terminal_id = :terminalId AND managed_key_id IS NOT NULL`,
    { terminalId },
  );
  for (const row of keyRows) {
    await purgeKeyDependents(conn, row.id);
    await conn.execute(`DELETE FROM managed_keys WHERE id = :id`, { id: row.id });
  }

  await conn.execute(`DELETE FROM key_checkouts WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
  await conn.execute(`DELETE FROM terminal_cabinet_settings WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
  await conn.execute(`DELETE FROM terminal_refresh_tokens WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
  await conn.execute(`DELETE FROM sync_conflicts WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
  await conn.execute(`DELETE FROM terminal_sync_state WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
  await conn.execute(`DELETE FROM key_slots WHERE terminal_id = :terminalId`, { terminalId });
  await conn.execute(
    `DELETE FROM appointment_keys WHERE appointment_id IN
       (SELECT id FROM appointments WHERE terminal_id = :terminalId)`,
    { terminalId },
  ).catch(() => undefined);
  await conn.execute(`DELETE FROM appointments WHERE terminal_id = :terminalId`, { terminalId }).catch(() => undefined);
}

/** Mirrors flush.js's SITES-scope order exactly: hardDeleteAccessGrants → hardDeleteKeys →
 * hardDeleteTerminals → hardDeleteSites, each scoped to this one site instead of the whole
 * table. A site can only reach the Recycle Bin with zero ACTIVE terminals/keys/child-sites
 * (sites.js's own DELETE dependency block, unchanged) — but RECYCLE_BIN-state terminals/keys/
 * grants at this site are still real rows an FK can point at, so they still need clearing here. */
async function purgeSiteDependents(conn, siteId) {
  const [grantRows] = await conn.execute(`SELECT id FROM access_grants WHERE site_id = :siteId`, { siteId });
  for (const grant of grantRows) {
    await conn.execute(`DELETE FROM access_grant_keys WHERE grant_id = :id`, { id: grant.id });
  }
  await conn.execute(`DELETE FROM access_grants WHERE site_id = :siteId`, { siteId });

  const [keyRows] = await conn.execute(`SELECT id FROM managed_keys WHERE site_id = :siteId`, { siteId });
  for (const key of keyRows) {
    await purgeKeyDependents(conn, key.id);
    await conn.execute(`DELETE FROM managed_keys WHERE id = :id`, { id: key.id });
  }

  const [terminalRows] = await conn.execute(`SELECT id FROM terminals WHERE site_id = :siteId`, { siteId });
  for (const terminal of terminalRows) {
    await purgeTerminalDependents(conn, terminal.id);
    await conn.execute(`DELETE FROM terminals WHERE id = :id`, { id: terminal.id });
  }

  await conn.execute(`DELETE FROM multi_authentication_rules WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM personnel_groups WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM key_groups WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM event_definitions WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM schedules WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM appointment_reasons WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM site_office_hours WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(
    `DELETE FROM key_access_request_documents WHERE request_id IN
       (SELECT id FROM key_access_requests WHERE site_id = :siteId)`,
    { siteId },
  ).catch(() => undefined);
  await conn.execute(
    `DELETE FROM key_access_request_keys WHERE request_id IN
       (SELECT id FROM key_access_requests WHERE site_id = :siteId)`,
    { siteId },
  ).catch(() => undefined);
  await conn.execute(`DELETE FROM key_access_requests WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`DELETE FROM vendor_passkey_requests WHERE site_id = :siteId`, { siteId }).catch(() => undefined);
  await conn.execute(`UPDATE sites SET parent_site_id = NULL WHERE parent_site_id = :siteId`, { siteId });
  await conn.execute(`UPDATE sites SET region_id = NULL WHERE id = :siteId`, { siteId }).catch(() => undefined);
}

async function collectBinEntries(conn) {
  const entries = [];
  for (const [recordType, meta] of Object.entries(ENTITY_TABLES)) {
    const [rows] = await conn.execute(
      `SELECT id, ${meta.labelSql} AS record_label, revision, deleted_at_epoch_ms, deleted_by_user_id
       FROM ${meta.table}
       WHERE lifecycle_state = 'RECYCLE_BIN'
       ORDER BY deleted_at_epoch_ms DESC`,
    );
    for (const row of rows) {
      const deletedAt = Number(row.deleted_at_epoch_ms || 0);
      entries.push({
        id: `${recordType}:${row.id}`,
        recordType,
        recordId: row.id,
        recordLabel: String(row.record_label ?? row.id),
        deletedByUserId: row.deleted_by_user_id || 'unknown',
        deletedAtEpochMillis: deletedAt,
        expiresAtEpochMillis: expiresAt(deletedAt),
        restorePayloadVersion: Number(row.revision),
      });
    }
  }
  return entries.sort((a, b) => b.deletedAtEpochMillis - a.deletedAtEpochMillis);
}

router.get('/', async (_req, res) => {
  const entries = await collectBinEntries(pool);
  res.json({
    entries,
    serverTimeEpochMillis: nowMs(),
  });
});

router.post('/restore', async (req, res) => {
  const schema = z.object({
    recordType: z.enum(['SITE', 'TERMINAL', 'USER', 'KEY', 'KEY_SLOT', 'ACCESS_GRANT']),
    recordId: z.string().uuid(),
    expectedRevision: z.number().int().nonnegative().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid restore payload');

  const meta = ENTITY_TABLES[parsed.data.recordType];
  const [rows] = await pool.execute(
    `SELECT * FROM ${meta.table} WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN' LIMIT 1`,
    { id: parsed.data.recordId },
  );
  if (!rows[0]) return notFound(res, 'Recycle Bin record not found');
  if (
    parsed.data.expectedRevision != null &&
    Number(rows[0].revision) !== parsed.data.expectedRevision
  ) {
    return res.status(409).json({ error: 'CONFLICT', message: 'expectedRevision does not match' });
  }

  // A recycled USER whose real email was parked in original_email (migration 014 — see users.js's
  // DELETE route) must not restore back onto an email another ACTIVE account has since taken;
  // block with a named conflict rather than silently restoring with the placeholder address or
  // silently overwriting the other account. Pre-migration-014 recycled rows have
  // original_email = NULL (their real email was never moved out of `email` to begin with) — those
  // fall straight through to the unchanged restore below, nothing to swap back.
  let restoredEmail = null;
  if (parsed.data.recordType === 'USER' && rows[0].original_email != null) {
    const [conflicting] = await pool.execute(
      `SELECT id FROM users WHERE email = :email AND lifecycle_state = 'ACTIVE' LIMIT 1`,
      { email: rows[0].original_email },
    );
    if (conflicting[0]) {
      return res.status(409).json({
        error: 'EMAIL_CONFLICT',
        message: `Cannot restore — ${rows[0].original_email} is already in use by another active account.`,
      });
    }
    restoredEmail = rows[0].original_email;
  }

  const now = nowMs();
  await pool.execute(
    `UPDATE ${meta.table}
     SET lifecycle_state = 'ACTIVE',
         deleted_at_epoch_ms = NULL,
         deleted_by_user_id = NULL,
         revision = revision + 1,
         updated_at_epoch_ms = :now
         ${parsed.data.recordType === 'USER' ? ", account_status = 'ACTIVE'" : ''}
         ${restoredEmail != null ? ', email = :restoredEmail, original_email = NULL' : ''}
     WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN'`,
    { id: parsed.data.recordId, now, ...(restoredEmail != null ? { restoredEmail } : {}) },
  );

  await writeAudit({
    eventType: 'RECORD_RESTORED',
    actorUserId: req.auth.sub,
    entityType: parsed.data.recordType,
    entityId: parsed.data.recordId,
    detail: 'RESTORED_FROM_RECYCLE_BIN',
  });

  res.json({ ok: true, recordType: parsed.data.recordType, recordId: parsed.data.recordId });
});

router.post('/purge', async (req, res) => {
  const schema = z.object({
    recordType: z.enum(['SITE', 'TERMINAL', 'USER', 'KEY', 'KEY_SLOT', 'ACCESS_GRANT']),
    recordId: z.string().uuid(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid purge payload');

  const meta = ENTITY_TABLES[parsed.data.recordType];
  const [rows] = await pool.execute(
    `SELECT id FROM ${meta.table} WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN' LIMIT 1`,
    { id: parsed.data.recordId },
  );
  if (!rows[0]) return notFound(res, 'Recycle Bin record not found');

  // Transactional as of Tier 2 (previously bare pool.execute calls, no rollback on a mid-way
  // failure) — needed now that TERMINAL/SITE purges can touch many tables; matches flush.js's
  // own transaction-wrapped runFlush().
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    if (parsed.data.recordType === 'ACCESS_GRANT') {
      await conn.execute(`DELETE FROM access_grant_keys WHERE grant_id = :id`, {
        id: parsed.data.recordId,
      });
    }
    if (parsed.data.recordType === 'USER') {
      await conn.execute(`DELETE FROM user_site_assignments WHERE user_id = :id`, {
        id: parsed.data.recordId,
      });
      await conn.execute(`DELETE FROM refresh_tokens WHERE user_id = :id`, {
        id: parsed.data.recordId,
      });
      await conn.execute(`DELETE FROM credential_statuses WHERE user_id = :id`, {
        id: parsed.data.recordId,
      }).catch(() => undefined);
    }
    if (parsed.data.recordType === 'KEY') {
      await purgeKeyDependents(conn, parsed.data.recordId);
    }
    if (parsed.data.recordType === 'TERMINAL') {
      await purgeTerminalDependents(conn, parsed.data.recordId);
    }
    if (parsed.data.recordType === 'SITE') {
      await purgeSiteDependents(conn, parsed.data.recordId);
    }

    await conn.execute(`DELETE FROM ${meta.table} WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN'`, {
      id: parsed.data.recordId,
    });

    await writeAudit({
      eventType: 'RECORD_PURGED',
      actorUserId: req.auth.sub,
      entityType: parsed.data.recordType,
      entityId: parsed.data.recordId,
      detail: 'PURGED_FROM_RECYCLE_BIN',
      conn,
    });
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  res.json({ ok: true, recordType: parsed.data.recordType, recordId: parsed.data.recordId });
});

router.post('/purge-expired', async (req, res) => {
  const now = nowMs();
  let purged = 0;
  for (const [recordType, meta] of Object.entries(ENTITY_TABLES)) {
    const [rows] = await pool.execute(
      `SELECT id FROM ${meta.table}
       WHERE lifecycle_state = 'RECYCLE_BIN'
         AND deleted_at_epoch_ms IS NOT NULL
         AND deleted_at_epoch_ms <= :cutoff`,
      { cutoff: now - RETENTION_DAYS * MILLIS_PER_DAY },
    );
    for (const row of rows) {
      // Same per-record transaction as /purge — see that route's comment.
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        if (recordType === 'ACCESS_GRANT') {
          await conn.execute(`DELETE FROM access_grant_keys WHERE grant_id = :id`, { id: row.id });
        }
        if (recordType === 'USER') {
          await conn.execute(`DELETE FROM user_site_assignments WHERE user_id = :id`, { id: row.id });
          await conn.execute(`DELETE FROM refresh_tokens WHERE user_id = :id`, { id: row.id });
        }
        if (recordType === 'KEY') {
          await purgeKeyDependents(conn, row.id);
        }
        if (recordType === 'TERMINAL') {
          await purgeTerminalDependents(conn, row.id);
        }
        if (recordType === 'SITE') {
          await purgeSiteDependents(conn, row.id);
        }
        await conn.execute(`DELETE FROM ${meta.table} WHERE id = :id`, { id: row.id });
        await writeAudit({
          eventType: 'RECORD_PURGED',
          actorUserId: req.auth.sub,
          entityType: recordType,
          entityId: row.id,
          detail: 'PURGE_EXPIRED',
          conn,
        });
        await conn.commit();
      } catch (err) {
        await conn.rollback();
        throw err;
      } finally {
        conn.release();
      }
      purged += 1;
    }
  }
  res.json({ ok: true, purgedCount: purged, serverTimeEpochMillis: now });
});

export default router;
