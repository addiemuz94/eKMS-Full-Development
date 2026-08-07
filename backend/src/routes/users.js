import bcrypt from 'bcryptjs';
import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  assignedSiteIdsForUser,
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

/** Region-assignment counterpart of [assignedSites] (migration 009) — see
 * `assignedRegionIds`'s doc on [mapUser] for why this is additive, not a replacement. */
async function assignedRegions(userId) {
  const [rows] = await pool.execute(
    `SELECT region_id FROM user_region_assignments WHERE user_id = :userId`,
    { userId },
  );
  return rows.map((r) => r.region_id);
}

function mapUser(row, siteIds, regionIds = []) {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    assignedSiteIds: siteIds,
    // Region assignment (migration 009) — independent of assignedSiteIds, only meaningful for
    // REGIONAL_ADMIN (routes key-access-request approval authority; see keyAccessRequests.js).
    // Deliberately not validated against assignedSiteIds for consistency — see the
    // "no consistency rule" note on user_region_assignments in
    // 009_regions_and_key_access_requests.sql.
    assignedRegionIds: regionIds,
    accountStatus: row.account_status,
    staffId: row.staff_id,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

/** TERMINAL_DEVICE-scoped tokens have no real user behind them — never attribute an audit
 * record's actorUserId to a terminal's own id. See requireSuperAdminOrAllowlistedRole. */
function actorUserIdFor(req) {
  return req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
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

/** Region counterpart of [replaceAssignments] — same delete-all-then-reinsert shape, own table.
 * No application-level validation that regionIds reference real regions, same as
 * replaceAssignments's siteIds: a bad id fails on the FK constraint itself and surfaces as a
 * raw 500, identical to today's behavior for a bad assignedSiteIds entry. */
async function replaceRegionAssignments(conn, userId, regionIds) {
  await conn.execute(`DELETE FROM user_region_assignments WHERE user_id = :userId`, { userId });
  for (const regionId of regionIds) {
    await conn.execute(
      `INSERT INTO user_region_assignments (user_id, region_id) VALUES (:userId, :regionId)`,
      { userId, regionId },
    );
  }
}

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  // GOD_ADMIN is developer/bootstrap-only — never appear in personnel lists.
  let sql = `SELECT * FROM users WHERE lifecycle_state = :state AND role <> 'GOD_ADMIN'`;
  const params = { state };

  // Regional Admin: only personnel assigned to at least one of the RA's sites.
  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return res.json({ items: [] });
    if (siteId && !assignedSiteIds.includes(siteId)) return res.json({ items: [] });
    const scopeIds = siteId ? [siteId] : assignedSiteIds;
    const placeholders = scopeIds.map((_, i) => `:site${i}`).join(', ');
    sql += ` AND id IN (SELECT user_id FROM user_site_assignments WHERE site_id IN (${placeholders}))`;
    scopeIds.forEach((id, i) => {
      params[`site${i}`] = id;
    });
  } else if (siteId) {
    sql += ` AND id IN (SELECT user_id FROM user_site_assignments WHERE site_id = :siteId)`;
    params.siteId = siteId;
  }
  sql += ` ORDER BY display_name ASC`;
  const [rows] = await pool.execute(sql, params);
  const items = [];
  for (const row of rows) {
    items.push(mapUser(row, await assignedSites(row.id), await assignedRegions(row.id)));
  }
  res.json({ items });
});

/** Bootstrap status for God Admin onboarding UI. */
router.get('/bootstrap-status', async (req, res) => {
  const [rows] = await pool.execute(
    `SELECT COUNT(*) AS c FROM users
     WHERE role = 'SUPER_ADMIN' AND lifecycle_state = 'ACTIVE'`,
  );
  return res.json({
    hasSuperAdmin: Number(rows[0].c) > 0,
    superAdminCount: Number(rows[0].c),
  });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'User not found');
  if (rows[0].role === 'GOD_ADMIN' && req.auth?.role !== 'GOD_ADMIN') {
    return notFound(res, 'User not found');
  }
  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return notFound(res, 'User not found');
    const userSites = await assignedSites(rows[0].id);
    if (!userSites.some((id) => assignedSiteIds.includes(id))) {
      return notFound(res, 'User not found');
    }
  }
  return res.json(mapUser(rows[0], await assignedSites(rows[0].id), await assignedRegions(rows[0].id)));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    displayName: z.string().min(1),
    email: z.string().email(),
    role: z.enum(['SUPER_ADMIN', 'REGIONAL_ADMIN', 'TECHNICIAN', 'VENDOR']),
    assignedSiteIds: z.array(z.string().uuid()).default([]),
    assignedRegionIds: z.array(z.string().uuid()).default([]),
    password: z.string().min(8).optional(),
    staffId: z.string().max(128).nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid user payload');

  // God Admin may only create the first Super Admin.
  if (req.auth?.role === 'GOD_ADMIN') {
    if (parsed.data.role !== 'SUPER_ADMIN') {
      return res.status(403).json({
        error: 'FORBIDDEN',
        message: 'God Admin may only register a Super Admin',
      });
    }
    if (!parsed.data.password) {
      return badRequest(res, 'Password is required for the first Super Admin');
    }
    const [saCount] = await pool.execute(
      `SELECT COUNT(*) AS c FROM users
       WHERE role = 'SUPER_ADMIN' AND lifecycle_state = 'ACTIVE'`,
    );
    if (Number(saCount[0].c) > 0) {
      return res.status(403).json({
        error: 'FORBIDDEN',
        message: 'A Super Admin already exists — God Admin bootstrap is complete',
      });
    }
  }

  if (parsed.data.role !== 'SUPER_ADMIN' && parsed.data.assignedSiteIds.length === 0) {
    return badRequest(res, 'REGIONAL_ADMIN, TECHNICIAN, and VENDOR require at least one assigned site');
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
        (id, display_name, email, password_hash, role, account_status, staff_id, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :displayName, :email, :passwordHash, :role, 'ACTIVE', :staffId, 1, 'ACTIVE', :now, :now)`,
      {
        id,
        displayName: parsed.data.displayName,
        email: parsed.data.email,
        passwordHash,
        role: parsed.data.role,
        staffId: parsed.data.staffId ?? null,
        now,
      },
    );
    await replaceAssignments(conn, id, parsed.data.assignedSiteIds);
    await replaceRegionAssignments(conn, id, parsed.data.assignedRegionIds);
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

  const primarySiteId =
    Array.isArray(parsed.data.assignedSiteIds) && parsed.data.assignedSiteIds.length
      ? parsed.data.assignedSiteIds[0]
      : null;
  await writeAudit({
    eventType: 'PERSONNEL_REGISTERED',
    actorUserId: actorUserIdFor(req),
    siteId: primarySiteId,
    entityType: 'USER',
    entityId: id,
    detail: parsed.data.displayName || 'USER_CREATED',
  });
  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id });
  return res.status(201).json(mapUser(rows[0], await assignedSites(id), await assignedRegions(id)));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    displayName: z.string().min(1),
    email: z.string().email(),
    role: z.enum(['SUPER_ADMIN', 'REGIONAL_ADMIN', 'TECHNICIAN', 'VENDOR']),
    assignedSiteIds: z.array(z.string().uuid()).default([]),
    assignedRegionIds: z.array(z.string().uuid()).default([]),
    staffId: z.string().max(128).nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid user update');
  if (parsed.data.role !== 'SUPER_ADMIN' && parsed.data.assignedSiteIds.length === 0) {
    return badRequest(res, 'REGIONAL_ADMIN, TECHNICIAN, and VENDOR require at least one assigned site');
  }

  const [existing] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'User not found');
  if (existing[0].role === 'GOD_ADMIN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'God Admin cannot be edited' });
  }
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE users
       SET display_name = :displayName, email = :email, role = :role, staff_id = :staffId,
           revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
      {
        id: req.params.id,
        displayName: parsed.data.displayName,
        email: parsed.data.email,
        role: parsed.data.role,
        staffId: parsed.data.staffId ?? null,
        expectedRevision: parsed.data.expectedRevision,
        now,
      },
    );
    if (result.affectedRows === 0) {
      await conn.rollback();
      return conflict(res);
    }
    await replaceAssignments(conn, req.params.id, parsed.data.assignedSiteIds);
    await replaceRegionAssignments(conn, req.params.id, parsed.data.assignedRegionIds);
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  const [rows] = await pool.execute(`SELECT * FROM users WHERE id = :id`, { id: req.params.id });
  return res.json(mapUser(rows[0], await assignedSites(req.params.id), await assignedRegions(req.params.id)));
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
  if (existing[0].role === 'GOD_ADMIN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'God Admin cannot be modified' });
  }
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
  return res.json(mapUser(rows[0], await assignedSites(req.params.id), await assignedRegions(req.params.id)));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'User not found');
  if (existing[0].role === 'GOD_ADMIN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'God Admin cannot be deleted' });
  }
  if (existing[0].id === req.auth.sub) {
    return badRequest(res, 'Cannot soft-delete the signed-in Super Admin');
  }

  const now = nowMs();
  // users.email keeps a plain UNIQUE KEY (uq_users_email) unscoped by lifecycle_state — MySQL has
  // no partial/filtered index — so a soft-deleted row's real email is parked in original_email
  // (migration 014) and swapped for a generated, guaranteed-unique placeholder. This is what lets
  // a brand-new account reuse the address while the old row sits in the Recycle Bin. The restore
  // route (recycleBin.js) moves it back, after checking no ACTIVE user has since taken it.
  const placeholderEmail = `deleted+${req.params.id}+${now}@deleted.local`;
  await pool.execute(
    `UPDATE users
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now, account_status = 'DISABLED',
         original_email = :originalEmail, email = :placeholderEmail
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      now,
      actor: req.auth.sub,
      originalEmail: existing[0].email,
      placeholderEmail,
    },
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
  return res.json(mapUser(rows[0], await assignedSites(req.params.id), await assignedRegions(req.params.id)));
});

export default router;
