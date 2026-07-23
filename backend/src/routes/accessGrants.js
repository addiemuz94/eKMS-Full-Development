import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  badRequest,
  conflict,
  lifecycleFromRow,
  newId,
  notFound,
  nowMs,
  writeAudit,
} from '../util.js';

const router = Router();

async function keyIdsForGrant(grantId) {
  const [rows] = await pool.execute(
    `SELECT key_id FROM access_grant_keys WHERE grant_id = :grantId`,
    { grantId },
  );
  return rows.map((r) => r.key_id);
}

function mapGrant(row, keyIds) {
  return {
    id: row.id,
    userId: row.user_id,
    siteId: row.site_id,
    keyIds,
    validFromEpochMillis: row.valid_from_epoch_ms == null ? null : Number(row.valid_from_epoch_ms),
    validUntilEpochMillis:
      row.valid_until_epoch_ms == null ? null : Number(row.valid_until_epoch_ms),
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

async function replaceKeys(conn, grantId, keyIds) {
  await conn.execute(`DELETE FROM access_grant_keys WHERE grant_id = :grantId`, { grantId });
  for (const keyId of keyIds) {
    await conn.execute(
      `INSERT INTO access_grant_keys (grant_id, key_id) VALUES (:grantId, :keyId)`,
      { grantId, keyId },
    );
  }
}

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  const userId = req.query.userId;
  let sql = `SELECT * FROM access_grants WHERE lifecycle_state = :state`;
  const params = { state };
  if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }
  if (userId) {
    sql += ` AND user_id = :userId`;
    params.userId = userId;
  }
  sql += ` ORDER BY created_at_epoch_ms DESC`;
  const [rows] = await pool.execute(sql, params);
  const items = [];
  for (const row of rows) {
    items.push(mapGrant(row, await keyIdsForGrant(row.id)));
  }
  res.json({ items });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM access_grants WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Access grant not found');
  return res.json(mapGrant(rows[0], await keyIdsForGrant(rows[0].id)));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    userId: z.string().uuid(),
    siteId: z.string().uuid(),
    keyIds: z.array(z.string().uuid()).min(1),
    validFromEpochMillis: z.number().int().nullable().optional(),
    validUntilEpochMillis: z.number().int().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid access grant payload');

  const id = newId();
  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    await conn.execute(
      `INSERT INTO access_grants
        (id, user_id, site_id, valid_from_epoch_ms, valid_until_epoch_ms, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :userId, :siteId, :validFrom, :validUntil, 1, 'ACTIVE', :now, :now)`,
      {
        id,
        userId: parsed.data.userId,
        siteId: parsed.data.siteId,
        validFrom: parsed.data.validFromEpochMillis ?? null,
        validUntil: parsed.data.validUntilEpochMillis ?? null,
        now,
      },
    );
    await replaceKeys(conn, id, parsed.data.keyIds);
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  await writeAudit({
    eventType: 'ACCESS_GRANT_CREATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'ACCESS_GRANT',
    entityId: id,
  });
  const [rows] = await pool.execute(`SELECT * FROM access_grants WHERE id = :id`, { id });
  return res.status(201).json(mapGrant(rows[0], await keyIdsForGrant(id)));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    userId: z.string().uuid(),
    siteId: z.string().uuid(),
    keyIds: z.array(z.string().uuid()).min(1),
    validFromEpochMillis: z.number().int().nullable().optional(),
    validUntilEpochMillis: z.number().int().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid access grant update');

  const [existing] = await pool.execute(
    `SELECT * FROM access_grants WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Access grant not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE access_grants
       SET user_id = :userId, site_id = :siteId, valid_from_epoch_ms = :validFrom,
           valid_until_epoch_ms = :validUntil, revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
      {
        id: req.params.id,
        userId: parsed.data.userId,
        siteId: parsed.data.siteId,
        validFrom: parsed.data.validFromEpochMillis ?? null,
        validUntil: parsed.data.validUntilEpochMillis ?? null,
        expectedRevision: parsed.data.expectedRevision,
        now,
      },
    );
    if (result.affectedRows === 0) {
      await conn.rollback();
      return conflict(res);
    }
    await replaceKeys(conn, req.params.id, parsed.data.keyIds);
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  await writeAudit({
    eventType: 'ACCESS_GRANT_UPDATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'ACCESS_GRANT',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM access_grants WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(mapGrant(rows[0], await keyIdsForGrant(req.params.id)));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM access_grants WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Access grant not found');

  const now = nowMs();
  await pool.execute(
    `UPDATE access_grants
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId: req.auth.sub,
    siteId: existing[0].site_id,
    entityType: 'ACCESS_GRANT',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM access_grants WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(mapGrant(rows[0], await keyIdsForGrant(req.params.id)));
});

export default router;
