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

  const now = nowMs();
  await pool.execute(
    `UPDATE ${meta.table}
     SET lifecycle_state = 'ACTIVE',
         deleted_at_epoch_ms = NULL,
         deleted_by_user_id = NULL,
         revision = revision + 1,
         updated_at_epoch_ms = :now
         ${parsed.data.recordType === 'USER' ? ", account_status = 'ACTIVE'" : ''}
     WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN'`,
    { id: parsed.data.recordId, now },
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

  if (parsed.data.recordType === 'ACCESS_GRANT') {
    await pool.execute(`DELETE FROM access_grant_keys WHERE access_grant_id = :id`, {
      id: parsed.data.recordId,
    });
  }
  if (parsed.data.recordType === 'USER') {
    await pool.execute(`DELETE FROM user_site_assignments WHERE user_id = :id`, {
      id: parsed.data.recordId,
    });
    await pool.execute(`DELETE FROM refresh_tokens WHERE user_id = :id`, {
      id: parsed.data.recordId,
    });
    await pool.execute(`DELETE FROM credential_statuses WHERE user_id = :id`, {
      id: parsed.data.recordId,
    }).catch(() => undefined);
  }

  await pool.execute(`DELETE FROM ${meta.table} WHERE id = :id AND lifecycle_state = 'RECYCLE_BIN'`, {
    id: parsed.data.recordId,
  });

  await writeAudit({
    eventType: 'RECORD_PURGED',
    actorUserId: req.auth.sub,
    entityType: parsed.data.recordType,
    entityId: parsed.data.recordId,
    detail: 'PURGED_FROM_RECYCLE_BIN',
  });

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
      if (recordType === 'ACCESS_GRANT') {
        await pool.execute(`DELETE FROM access_grant_keys WHERE access_grant_id = :id`, { id: row.id });
      }
      if (recordType === 'USER') {
        await pool.execute(`DELETE FROM user_site_assignments WHERE user_id = :id`, { id: row.id });
        await pool.execute(`DELETE FROM refresh_tokens WHERE user_id = :id`, { id: row.id });
      }
      await pool.execute(`DELETE FROM ${meta.table} WHERE id = :id`, { id: row.id });
      await writeAudit({
        eventType: 'RECORD_PURGED',
        actorUserId: req.auth.sub,
        entityType: recordType,
        entityId: row.id,
        detail: 'PURGE_EXPIRED',
      });
      purged += 1;
    }
  }
  res.json({ ok: true, purgedCount: purged, serverTimeEpochMillis: now });
});

export default router;
