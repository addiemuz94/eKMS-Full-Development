import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  assignedSiteIdsForUser,
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

/** TERMINAL_DEVICE-scoped tokens have no real user behind them — never attribute an audit
 * record's actorUserId to a terminal's own id. Only matters for POST / (Key Attachment's
 * new-key-registration widening, Jul 2026, see auth.js's allowlist doc comment) — every other
 * route here stays SUPER_ADMIN/REGIONAL_ADMIN-only where req.auth.sub is always a real user. */
function actorUserIdFor(req) {
  return req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
}

function mapSlot(row) {
  return {
    id: row.id,
    terminalId: row.terminal_id,
    nodeAddress: Number(row.node_address),
    managedKeyId: row.managed_key_id,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

async function terminalSiteId(terminalId) {
  const [rows] = await pool.execute(
    `SELECT site_id FROM terminals WHERE id = :id LIMIT 1`,
    { id: terminalId },
  );
  return rows[0]?.site_id ?? null;
}

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const terminalId = req.query.terminalId;
  let sql = `SELECT ks.* FROM key_slots ks`;
  const params = { state };
  const joins = [];
  const conditions = [`ks.lifecycle_state = :state`];

  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return res.json({ items: [] });
    joins.push(`INNER JOIN terminals t ON t.id = ks.terminal_id`);
    if (terminalId) {
      const siteId = await terminalSiteId(terminalId);
      if (!siteId || !assignedSiteIds.includes(siteId)) return res.json({ items: [] });
      conditions.push(`ks.terminal_id = :terminalId`);
      params.terminalId = terminalId;
    } else {
      const placeholders = assignedSiteIds.map((_, i) => `:site${i}`).join(', ');
      conditions.push(`t.site_id IN (${placeholders})`);
      assignedSiteIds.forEach((id, i) => {
        params[`site${i}`] = id;
      });
    }
  } else if (terminalId) {
    conditions.push(`ks.terminal_id = :terminalId`);
    params.terminalId = terminalId;
  }

  sql += ` ${joins.join(' ')} WHERE ${conditions.join(' AND ')} ORDER BY ks.node_address ASC`;
  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapSlot) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM key_slots WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key slot not found');
  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const siteId = await terminalSiteId(rows[0].terminal_id);
    if (!siteId || !(await isSiteAssignedToUser(req.auth.sub, siteId))) {
      return notFound(res, 'Key slot not found');
    }
  }
  return res.json(mapSlot(rows[0]));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    terminalId: z.string().uuid(),
    nodeAddress: z.number().int().positive(),
    managedKeyId: z.string().uuid().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key slot payload');

  const [terminals] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.terminalId },
  );
  if (!terminals[0]) return badRequest(res, 'terminalId must reference an active terminal');
  if (parsed.data.nodeAddress > Number(terminals[0].configured_slot_count)) {
    return badRequest(res, 'nodeAddress exceeds terminal configuredSlotCount');
  }

  const [dup] = await pool.execute(
    `SELECT id FROM key_slots
     WHERE terminal_id = :terminalId AND node_address = :nodeAddress AND lifecycle_state = 'ACTIVE'
     LIMIT 1`,
    { terminalId: parsed.data.terminalId, nodeAddress: parsed.data.nodeAddress },
  );
  if (dup[0]) return badRequest(res, 'Node address already assigned on this terminal');

  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO key_slots
      (id, terminal_id, node_address, managed_key_id, revision, lifecycle_state,
       created_at_epoch_ms, updated_at_epoch_ms)
     VALUES (:id, :terminalId, :nodeAddress, :managedKeyId, 1, 'ACTIVE', :now, :now)`,
    {
      id,
      terminalId: parsed.data.terminalId,
      nodeAddress: parsed.data.nodeAddress,
      managedKeyId: parsed.data.managedKeyId ?? null,
      now,
    },
  );
  await writeAudit({
    eventType: 'KEY_SLOT_CREATED',
    actorUserId: actorUserIdFor(req),
    terminalId: parsed.data.terminalId,
    entityType: 'KEY_SLOT',
    entityId: id,
  });
  const [rows] = await pool.execute(`SELECT * FROM key_slots WHERE id = :id`, { id });
  return res.status(201).json(mapSlot(rows[0]));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    terminalId: z.string().uuid(),
    nodeAddress: z.number().int().positive(),
    managedKeyId: z.string().uuid().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key slot update');

  const [existing] = await pool.execute(
    `SELECT * FROM key_slots WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Key slot not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE key_slots
     SET terminal_id = :terminalId, node_address = :nodeAddress, managed_key_id = :managedKeyId,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      terminalId: parsed.data.terminalId,
      nodeAddress: parsed.data.nodeAddress,
      managedKeyId: parsed.data.managedKeyId ?? null,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'KEY_SLOT_UPDATED',
    actorUserId: req.auth.sub,
    terminalId: parsed.data.terminalId,
    entityType: 'KEY_SLOT',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM key_slots WHERE id = :id`, { id: req.params.id });
  return res.json(mapSlot(rows[0]));
});

router.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM key_slots WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Key slot not found');

  const now = nowMs();
  await pool.execute(
    `UPDATE key_slots
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId: req.auth.sub,
    terminalId: existing[0].terminal_id,
    entityType: 'KEY_SLOT',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM key_slots WHERE id = :id`, { id: req.params.id });
  return res.json(mapSlot(rows[0]));
});

export default router;
