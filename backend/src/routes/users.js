import bcrypt from 'bcryptjs';
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

async function assignedSites(userId) {
  const [rows] = await pool.execute(
    `SELECT site_id FROM user_site_assignments WHERE user_id = :userId`,
    { userId },
  );
  return rows.map((r) => r.site_id);
}

function mapUser(row, siteIds) {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    assignedSiteIds: siteIds,
    accountStatus: row.account_status,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

async function replaceAssignments(conn, userId, siteIds) {
  await conn.execute(`DELETE FROM user_site_assignments WHERE user_id = :userId`, { userId });
  for (const siteId of siteIds) {
    await conn.execute(
      `INSERT INTO user_site_assignments (user_id, site_id) VALUES (:userId, :siteId)`,
      { userId, siteId },
    );
  }
}

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  let sql = `SELECT * FROM users WHERE lifecycle_state = :state`;
  const params = { state };
  if (siteId) {
    sql += ` AND id IN (SELECT user_id FROM user_site_assignments WHERE site_id = :siteId)`;
    params.siteId = siteId;
  }
  sql += ` ORDER BY display_name ASC`;
  const [rows] = await pool.execute(sql, params);
  const items = [];
  for (const row of rows) {
    items.push(mapUser(row, await assignedSites(row.id)));
  }
  res.json({ items });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'User not found');
  return res.json(mapUser(rows[0], await assignedSites(rows[0].id)));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    displayName: z.string().min(1),
    email: z.string().email(),
    role: z.enum(['SUPER_ADMIN', 'TECHNICIAN', 'VENDOR']),
    assignedSiteIds: z.array(z.string().uuid()).default([]),
    password: z.string().min(8).optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid user payload');

  if (parsed.data.role !== 'SUPER_ADMIN' && parsed.data.assignedSiteIds.length === 0) {
    return badRequest(res, 'TECHNICIAN and VENDOR require at least one assigned site');
  }

  const id = newId();
  const now = nowMs();
  const passwordHash = parsed.data.password
    ? await bcrypt.hash(parsed.data.password, 12)
    : null;
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    await conn.execute(
      `INSERT INTO users
        (id, display_name, email, password_hash, role, account_status, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :displayName, :email, :passwordHash, :role, 'ACTIVE', 1, 'ACTIVE', :now, :now)`,
      {
        id,
        displayName: parsed.data.displayName,
        email: parsed.data.email,
        passwordHash,
        role: parsed.data.role,
        now,
      },
    );
    await replaceAssignments(conn, id, parsed.data.assignedSiteIds);
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    if (err.code === 'ER_DUP_ENTRY') {
      return badRequest(res, 'Email already exists');
    }
    throw err;
  } finally {
    conn.release();
  }

  await writeAudit({
    eventType: 'USER_ACCOUNT_STATUS_CHANGED',
    actorUserId: req.auth.sub,
    entityType: 'USER',
    entityId: id,
    detail: 'USER_CREATED',
  });
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id });
  return res.status(201).json(mapUser(rows[0], await assignedSites(id)));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    displayName: z.string().min(1),
    email: z.string().email(),
    role: z.enum(['SUPER_ADMIN', 'TECHNICIAN', 'VENDOR']),
    assignedSiteIds: z.array(z.string().uuid()).default([]),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid user update');
  if (parsed.data.role !== 'SUPER_ADMIN' && parsed.data.assignedSiteIds.length === 0) {
    return badRequest(res, 'TECHNICIAN and VENDOR require at least one assigned site');
  }

  const [existing] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'User not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE users
       SET display_name = :displayName, email = :email, role = :role,
           revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
      {
        id: req.params.id,
        displayName: parsed.data.displayName,
        email: parsed.data.email,
        role: parsed.data.role,
        expectedRevision: parsed.data.expectedRevision,
        now,
      },
    );
    if (result.affectedRows === 0) {
      await conn.rollback();
      return conflict(res);
    }
    await replaceAssignments(conn, req.params.id, parsed.data.assignedSiteIds);
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id: req.params.id });
  return res.json(mapUser(rows[0], await assignedSites(req.params.id)));
});

router.post('/:id/account-status', async (req, res) => {
  const schema = z.object({
    accountStatus: z.enum(['ACTIVE', 'DISABLED']),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid account status payload');

  const [existing] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'User not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE users
     SET account_status = :accountStatus, revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      accountStatus: parsed.data.accountStatus,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  if (parsed.data.accountStatus === 'DISABLED') {
    await pool.execute(`UPDATE refresh_tokens SET revoked = 1 WHERE user_id = :id`, {
      id: req.params.id,
    });
  }

  await writeAudit({
    eventType: 'USER_ACCOUNT_STATUS_CHANGED',
    actorUserId: req.auth.sub,
    entityType: 'USER',
    entityId: req.params.id,
    detail: parsed.data.accountStatus,
  });
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id: req.params.id });
  return res.json(mapUser(rows[0], await assignedSites(req.params.id)));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'User not found');
  if (existing[0].id === req.auth.sub) {
    return badRequest(res, 'Cannot soft-delete the signed-in Super Admin');
  }

  const now = nowMs();
  await pool.execute(
    `UPDATE users
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now, account_status = 'DISABLED'
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  await pool.execute(`UPDATE refresh_tokens SET revoked = 1 WHERE user_id = :id`, {
    id: req.params.id,
  });
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId: req.auth.sub,
    entityType: 'USER',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id: req.params.id });
  return res.json(mapUser(rows[0], await assignedSites(req.params.id)));
});

export default router;
