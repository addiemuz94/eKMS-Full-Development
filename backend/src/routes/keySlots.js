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

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const terminalId = req.query.terminalId;
  let sql = `SELECT * FROM key_slots WHERE lifecycle_state = :state`;
  const params = { state };
  if (terminalId) {
    sql += ` AND terminal_id = :terminalId`;
    params.terminalId = terminalId;
  }
  sql += ` ORDER BY node_address ASC`;
  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapSlot) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM key_slots WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key slot not found');
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
    actorUserId: req.auth.sub,
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
