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

function mapSite(row) {
  return {
    id: row.id,
    name: row.name,
    address: row.address,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

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
  const schema = z.object({
    name: z.string().min(1),
    address: z.string().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid site payload');

  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO sites
      (id, name, address, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
     VALUES (:id, :name, :address, 1, 'ACTIVE', :now, :now)`,
    {
      id,
      name: parsed.data.name,
      address: parsed.data.address ?? null,
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
  const schema = z.object({
    name: z.string().min(1),
    address: z.string().nullable().optional(),
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

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE sites
     SET name = :name, address = :address, revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      name: parsed.data.name,
      address: parsed.data.address ?? null,
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
      (SELECT COUNT(*) FROM managed_keys WHERE site_id = :id AND lifecycle_state = 'ACTIVE') AS keys_count`,
    { id: req.params.id },
  );
  if (Number(deps.terminals) > 0 || Number(deps.keys_count) > 0) {
    return res.status(409).json({
      error: 'DEPENDENCY_BLOCKED',
      message: 'Site has active terminals or keys',
      dependentRecordCount: Number(deps.terminals) + Number(deps.keys_count),
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

export default router;
