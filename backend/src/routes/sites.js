import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  badRequest,
  conflict,
  isSiteAssignedToUser,
  lifecycleFromRow,
  newId,
  notFound,
  nowMs,
  writeAudit,
} from '../util.js';

const router = Router();

function mapSite(row) {
  return {
    id: row.id,
    name: row.name,
    province: row.province ?? null,
    city: row.city ?? null,
    parentSiteId: row.parent_site_id ?? null,
    address: row.address,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

function derivedAddress({ address, city, province }) {
  if (address != null && String(address).trim()) return String(address).trim();
  const parts = [city, province].map((v) => (v == null ? '' : String(v).trim())).filter(Boolean);
  return parts.length > 0 ? parts.join(', ') : null;
}

async function assertValidParent(parentSiteId, { excludeSiteId = null } = {}) {
  if (parentSiteId == null || parentSiteId === '') return null;
  if (excludeSiteId && parentSiteId === excludeSiteId) {
    return 'A unit cannot be its own superior unit';
  }
  const [rows] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parentSiteId },
  );
  if (!rows[0]) return 'parentSiteId must reference an active unit';
  return null;
}

const upsertSchema = z.object({
  name: z.string().min(1),
  province: z.string().nullable().optional(),
  city: z.string().nullable().optional(),
  parentSiteId: z.string().uuid().nullable().optional(),
  address: z.string().nullable().optional(),
});

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const [rows] = await pool.execute(
    `SELECT * FROM sites WHERE lifecycle_state = :state ORDER BY name ASC`,
    { state },
  );
  res.json({ items: rows.map(mapSite) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM sites WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Site not found');
  return res.json(mapSite(rows[0]));
});

router.post('/', async (req, res) => {
  const parsed = upsertSchema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid site payload');

  const parentSiteId = parsed.data.parentSiteId ?? null;
  const parentError = await assertValidParent(parentSiteId);
  if (parentError) return badRequest(res, parentError);

  const id = newId();
  const now = nowMs();
  const province = parsed.data.province?.trim() || null;
  const city = parsed.data.city?.trim() || null;
  const address = derivedAddress({
    address: parsed.data.address,
    city,
    province,
  });

  await pool.execute(
    `INSERT INTO sites
      (id, name, province, city, parent_site_id, address, revision, lifecycle_state,
       created_at_epoch_ms, updated_at_epoch_ms)
     VALUES
      (:id, :name, :province, :city, :parentSiteId, :address, 1, 'ACTIVE', :now, :now)`,
    {
      id,
      name: parsed.data.name.trim(),
      province,
      city,
      parentSiteId,
      address,
      now,
    },
  );
  await writeAudit({
    eventType: 'SITE_CREATED',
    actorUserId: req.auth.sub,
    siteId: id,
    entityType: 'SITE',
    entityId: id,
  });
  const [rows] = await pool.execute(`SELECT * FROM sites WHERE id = :id`, { id });
  return res.status(201).json(mapSite(rows[0]));
});

router.patch('/:id', async (req, res) => {
  const schema = upsertSchema.extend({
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid site update');

  const [existing] = await pool.execute(
    `SELECT * FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Site not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) {
    return conflict(res);
  }

  const parentSiteId =
    parsed.data.parentSiteId === undefined
      ? existing[0].parent_site_id
      : parsed.data.parentSiteId;
  const parentError = await assertValidParent(parentSiteId, { excludeSiteId: req.params.id });
  if (parentError) return badRequest(res, parentError);

  const province =
    parsed.data.province === undefined
      ? existing[0].province
      : parsed.data.province?.trim() || null;
  const city =
    parsed.data.city === undefined ? existing[0].city : parsed.data.city?.trim() || null;
  const address =
    parsed.data.address === undefined
      ? derivedAddress({ address: existing[0].address, city, province })
      : derivedAddress({ address: parsed.data.address, city, province });

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE sites
     SET name = :name,
         province = :province,
         city = :city,
         parent_site_id = :parentSiteId,
         address = :address,
         revision = revision + 1,
         updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      name: parsed.data.name.trim(),
      province,
      city,
      parentSiteId,
      address,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'SITE_UPDATED',
    actorUserId: req.auth.sub,
    siteId: req.params.id,
    entityType: 'SITE',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM sites WHERE id = :id`, { id: req.params.id });
  return res.json(mapSite(rows[0]));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Site not found');

  const [[deps]] = await pool.execute(
    `SELECT
      (SELECT COUNT(*) FROM terminals WHERE site_id = :id AND lifecycle_state = 'ACTIVE') AS terminals,
      (SELECT COUNT(*) FROM managed_keys WHERE site_id = :id AND lifecycle_state = 'ACTIVE') AS keys_count,
      (SELECT COUNT(*) FROM sites WHERE parent_site_id = :id AND lifecycle_state = 'ACTIVE') AS child_units`,
    { id: req.params.id },
  );
  const dependent =
    Number(deps.terminals) + Number(deps.keys_count) + Number(deps.child_units);
  if (dependent > 0) {
    return res.status(409).json({
      error: 'DEPENDENCY_BLOCKED',
      message: 'Site has active terminals, keys, or subordinate units',
      dependentRecordCount: dependent,
    });
  }

  const now = nowMs();
  await pool.execute(
    `UPDATE sites
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId: req.auth.sub,
    siteId: req.params.id,
    entityType: 'SITE',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM sites WHERE id = :id`, { id: req.params.id });
  return res.json(mapSite(rows[0]));
});

const OFFICE_HOURS_TIME_REGEX = /^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$/;

function mapOfficeHours(row) {
  return {
    siteId: row.site_id,
    openTime: row.open_time,
    closeTime: row.close_time,
    timezone: row.timezone,
    updatedByUserId: row.updated_by_user_id ?? null,
    updatedAtEpochMillis: Number(row.updated_at_epoch_ms),
    revision: Number(row.revision),
  };
}

async function insertDefaultOfficeHours(connOrPool, siteId, now = nowMs()) {
  await connOrPool.execute(
    `INSERT INTO site_office_hours (site_id, open_time, close_time, timezone, revision, updated_at_epoch_ms)
     VALUES (:siteId, '08:00:00', '17:00:00', 'Asia/Kuala_Lumpur', 1, :now)
     ON DUPLICATE KEY UPDATE site_id = site_id`,
    { siteId, now },
  );
}

async function loadOfficeHours(siteId) {
  const [rows] = await pool.execute(
    `SELECT * FROM site_office_hours WHERE site_id = :siteId LIMIT 1`,
    { siteId },
  );
  if (rows[0]) return mapOfficeHours(rows[0]);
  await insertDefaultOfficeHours(pool, siteId);
  const [created] = await pool.execute(
    `SELECT * FROM site_office_hours WHERE site_id = :siteId LIMIT 1`,
    { siteId },
  );
  return mapOfficeHours(created[0]);
}

/**
 * Super Admin may read/write any site's office hours; Regional Admin only their assigned
 * sites, for both read and write here (unlike some resources, read isn't broader than write
 * for this one). `requireSuperAdminOrAllowlistedRole` (the ambient `/v1/admin` gate in
 * index.js) now admits a REGIONAL_ADMIN token to this exact route — that outer gate controls
 * *which routes* Regional Admin can reach at all; this function controls *which rows* (their
 * own assigned sites only). Both layers are required together, neither alone is sufficient.
 */
async function assertMayAccessOfficeHours(req, siteId) {
  if (req.auth?.role === 'SUPER_ADMIN') return true;
  if (req.auth?.role === 'REGIONAL_ADMIN') return isSiteAssignedToUser(req.auth.sub, siteId);
  return false;
}

router.get('/:id/office-hours', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Site not found');
  // Out-of-scope reads as "not found", not "forbidden" — avoids confirming a site's existence
  // to a Regional Admin who isn't assigned to it.
  if (!(await assertMayAccessOfficeHours(req, req.params.id))) return notFound(res, 'Site not found');
  return res.json(await loadOfficeHours(req.params.id));
});

router.patch('/:id/office-hours', async (req, res) => {
  const schema = z.object({
    openTime: z.string().regex(OFFICE_HOURS_TIME_REGEX, 'openTime must be HH:MM:SS'),
    closeTime: z.string().regex(OFFICE_HOURS_TIME_REGEX, 'closeTime must be HH:MM:SS'),
    timezone: z.string().min(1),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid office hours update');

  const [existingSite] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existingSite[0]) return notFound(res, 'Site not found');

  if (!(await assertMayAccessOfficeHours(req, req.params.id))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: "Not permitted to edit this site's office hours" });
  }

  await insertDefaultOfficeHours(pool, req.params.id);

  const [existing] = await pool.execute(
    `SELECT * FROM site_office_hours WHERE site_id = :id LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Office hours not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE site_office_hours SET
       open_time = :openTime,
       close_time = :closeTime,
       timezone = :timezone,
       updated_by_user_id = :actorUserId,
       revision = revision + 1,
       updated_at_epoch_ms = :now
     WHERE site_id = :id AND revision = :expectedRevision`,
    {
      id: req.params.id,
      openTime: parsed.data.openTime,
      closeTime: parsed.data.closeTime,
      timezone: parsed.data.timezone,
      actorUserId,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'SITE_OFFICE_HOURS_UPDATED',
    actorUserId,
    siteId: req.params.id,
    entityType: 'SITE',
    entityId: req.params.id,
  });

  const [rows] = await pool.execute(
    `SELECT * FROM site_office_hours WHERE site_id = :id LIMIT 1`,
    { id: req.params.id },
  );
  return res.json(mapOfficeHours(rows[0]));
});

export default router;
