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

function mapKey(row) {
  return {
    id: row.id,
    siteId: row.site_id,
    displayName: row.display_name,
    fobEnrollmentReference: row.fob_enrollment_reference,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;

  // Technician/Vendor (added for the mobile Key Access Request form, migration 009 follow-up):
  // always self-scoped to their OWN assigned sites, regardless of any siteId filter — a
  // Technician/Vendor building their request form's key picker must never be able to list keys
  // at a site they have no assignment to, just by passing a different siteId.
  if (req.auth?.role === 'TECHNICIAN' || req.auth?.role === 'VENDOR') {
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return res.json({ items: [] });
    const placeholders = assignedSiteIds.map((_, i) => `:site${i}`).join(', ');
    const params = { state };
    assignedSiteIds.forEach((id, i) => {
      params[`site${i}`] = id;
    });
    const [rows] = await pool.execute(
      `SELECT * FROM managed_keys WHERE lifecycle_state = :state AND site_id IN (${placeholders}) ORDER BY display_name ASC`,
      params,
    );
    return res.json({ items: rows.map(mapKey) });
  }

  let sql = `SELECT * FROM managed_keys WHERE lifecycle_state = :state`;
  const params = { state };
  if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }
  sql += ` ORDER BY display_name ASC`;
  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapKey) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM managed_keys WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key not found');
  return res.json(mapKey(rows[0]));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    siteId: z.string().uuid(),
    displayName: z.string().min(1),
    fobEnrollmentReference: z.string().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key payload');

  const [sites] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.siteId },
  );
  if (!sites[0]) return badRequest(res, 'siteId must reference an active site');

  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO managed_keys
      (id, site_id, display_name, fob_enrollment_reference, revision, lifecycle_state,
       created_at_epoch_ms, updated_at_epoch_ms)
     VALUES (:id, :siteId, :displayName, :fobRef, 1, 'ACTIVE', :now, :now)`,
    {
      id,
      siteId: parsed.data.siteId,
      displayName: parsed.data.displayName,
      fobRef: parsed.data.fobEnrollmentReference ?? null,
      now,
    },
  );
  await writeAudit({
    eventType: 'KEY_CREATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'KEY',
    entityId: id,
  });
  const [rows] = await pool.execute(`SELECT * FROM managed_keys WHERE id = :id`, { id });
  return res.status(201).json(mapKey(rows[0]));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    siteId: z.string().uuid(),
    displayName: z.string().min(1),
    fobEnrollmentReference: z.string().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key update');

  const [existing] = await pool.execute(
    `SELECT * FROM managed_keys WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Key not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE managed_keys
     SET site_id = :siteId, display_name = :displayName,
         fob_enrollment_reference = :fobRef, revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      siteId: parsed.data.siteId,
      displayName: parsed.data.displayName,
      fobRef: parsed.data.fobEnrollmentReference ?? null,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'KEY_UPDATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'KEY',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM managed_keys WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(mapKey(rows[0]));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM managed_keys WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Key not found');

  const [[deps]] = await pool.execute(
    `SELECT
      (SELECT COUNT(*) FROM key_slots WHERE managed_key_id = :id AND lifecycle_state = 'ACTIVE') AS slots,
      (SELECT COUNT(*) FROM access_grant_keys agk
         INNER JOIN access_grants ag ON ag.id = agk.grant_id
         WHERE agk.key_id = :id AND ag.lifecycle_state = 'ACTIVE') AS grants`,
    { id: req.params.id },
  );
  if (Number(deps.slots) > 0 || Number(deps.grants) > 0) {
    return res.status(409).json({
      error: 'DEPENDENCY_BLOCKED',
      message: 'Key is referenced by active slots or grants',
      dependentRecordCount: Number(deps.slots) + Number(deps.grants),
    });
  }

  const now = nowMs();
  await pool.execute(
    `UPDATE managed_keys
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId: req.auth.sub,
    siteId: existing[0].site_id,
    entityType: 'KEY',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM managed_keys WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(mapKey(rows[0]));
});

export default router;
