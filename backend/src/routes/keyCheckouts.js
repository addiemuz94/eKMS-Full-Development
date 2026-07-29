import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, conflict, newId, notFound, nowMs, writeAudit } from '../util.js';

const router = Router();

/**
 * `status` on the row is only ever OPEN | RETURNED. OVERDUE is derived here at read time
 * (status = 'OPEN' AND due_at_epoch_ms < now) — confirmed decision, never stored or
 * cron-flipped, so a checkout can never silently go stale in the database.
 */
function mapCheckout(row) {
  const status = row.status;
  const effectiveStatus = status === 'OPEN' && Number(row.due_at_epoch_ms) < nowMs() ? 'OVERDUE' : status;
  return {
    id: row.id,
    keyId: row.key_id,
    userId: row.user_id,
    terminalId: row.terminal_id,
    takenAtEpochMillis: Number(row.taken_at_epoch_ms),
    dueAtEpochMillis: Number(row.due_at_epoch_ms),
    status,
    effectiveStatus,
    isEmergency: Boolean(row.is_emergency),
    emergencyWindowEndsAtEpochMillis:
      row.emergency_window_ends_at_epoch_ms == null ? null : Number(row.emergency_window_ends_at_epoch_ms),
    extensionRequestedAtEpochMillis:
      row.extension_requested_at_epoch_ms == null ? null : Number(row.extension_requested_at_epoch_ms),
    extensionStatus: row.extension_status,
    extensionApprovedByUserId: row.extension_approved_by_user_id,
    extensionNewDueAtEpochMillis:
      row.extension_new_due_at_epoch_ms == null ? null : Number(row.extension_new_due_at_epoch_ms),
    returnedAtEpochMillis: row.returned_at_epoch_ms == null ? null : Number(row.returned_at_epoch_ms),
    revision: Number(row.revision),
  };
}

router.get('/', async (req, res) => {
  const { siteId, terminalId } = req.query;
  // Defaults to OPEN (which includes what's derived as OVERDUE above) since "list-open" is the
  // documented purpose; pass ?status=ALL/RETURNED explicitly for history/reporting use.
  const status = req.query.status || 'OPEN';

  let sql = `SELECT kc.* FROM key_checkouts kc`;
  const params = {};
  const conditions = [];
  if (siteId) {
    sql += ` JOIN terminals t ON t.id = kc.terminal_id`;
    conditions.push(`t.site_id = :siteId`);
    params.siteId = siteId;
  }
  if (terminalId) {
    conditions.push(`kc.terminal_id = :terminalId`);
    params.terminalId = terminalId;
  }
  if (status !== 'ALL') {
    conditions.push(`kc.status = :status`);
    params.status = status;
  }
  if (conditions.length > 0) sql += ` WHERE ${conditions.join(' AND ')}`;
  sql += ` ORDER BY kc.taken_at_epoch_ms DESC`;

  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapCheckout) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM key_checkouts WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key checkout not found');
  return res.json(mapCheckout(rows[0]));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    keyId: z.string().uuid(),
    userId: z.string().uuid(),
    terminalId: z.string().uuid(),
    takenAtEpochMillis: z.number().int().positive(),
    dueAtEpochMillis: z.number().int().positive(),
    isEmergency: z.boolean().default(false),
    emergencyWindowEndsAtEpochMillis: z.number().int().positive().nullable().optional(),
    // Not stored — same "log which path set it via audit_events, no parallel column" decision
    // already applied to the PATCH route's dueDateSource. Phase 5 adds the EMERGENCY case here:
    // the close-to-deadline decision (auto-computed close time / manually entered / marked
    // emergency) happens once at take time, which is exactly this route. Migration 009 follow-up
    // adds PASSKEY_REQUEST: a passkey-authenticated take's due time was already fixed at
    // key-access-request approval time, not decided here at all — logged for provenance same as
    // every other source.
    dueDateSource: z.enum(['AUTO', 'MANUAL', 'EMERGENCY', 'PASSKEY_REQUEST']).default('AUTO'),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key checkout payload');

  const [[key], [user], [terminal]] = await Promise.all([
    pool.execute(`SELECT id FROM managed_keys WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`, {
      id: parsed.data.keyId,
    }),
    pool.execute(`SELECT id FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`, {
      id: parsed.data.userId,
    }),
    pool.execute(`SELECT id, site_id FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`, {
      id: parsed.data.terminalId,
    }),
  ]);
  if (!key[0]) return badRequest(res, 'keyId must reference an active key');
  if (!user[0]) return badRequest(res, 'userId must reference an active user');
  if (!terminal[0]) return badRequest(res, 'terminalId must reference an active terminal');

  const id = newId();
  await pool.execute(
    `INSERT INTO key_checkouts
      (id, key_id, user_id, terminal_id, taken_at_epoch_ms, due_at_epoch_ms,
       status, is_emergency, emergency_window_ends_at_epoch_ms, revision)
     VALUES
      (:id, :keyId, :userId, :terminalId, :takenAtEpochMillis, :dueAtEpochMillis,
       'OPEN', :isEmergency, :emergencyWindowEndsAtEpochMillis, 1)`,
    {
      id,
      keyId: parsed.data.keyId,
      userId: parsed.data.userId,
      terminalId: parsed.data.terminalId,
      takenAtEpochMillis: parsed.data.takenAtEpochMillis,
      dueAtEpochMillis: parsed.data.dueAtEpochMillis,
      isEmergency: parsed.data.isEmergency ? 1 : 0,
      emergencyWindowEndsAtEpochMillis: parsed.data.emergencyWindowEndsAtEpochMillis ?? null,
    },
  );
  await writeAudit({
    eventType: 'KEY_CHECKOUT_CREATED',
    actorUserId: req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub,
    siteId: terminal[0].site_id,
    terminalId: parsed.data.terminalId,
    entityType: 'KEY_CHECKOUT',
    entityId: id,
    detail: `dueDateSource=${parsed.data.dueDateSource}`,
  });

  const [rows] = await pool.execute(`SELECT * FROM key_checkouts WHERE id = :id`, { id });
  return res.status(201).json(mapCheckout(rows[0]));
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    dueAtEpochMillis: z.number().int().positive(),
    // Not stored — decision #2: due_at_epoch_ms is reused as the effective deadline regardless
    // of source, logged here via audit_events.detail instead of a parallel column.
    dueDateSource: z.enum(['AUTO', 'MANUAL']).default('AUTO'),
    status: z.enum(['OPEN', 'RETURNED']),
    returnedAtEpochMillis: z.number().int().positive().nullable().optional(),
    isEmergency: z.boolean(),
    emergencyWindowEndsAtEpochMillis: z.number().int().positive().nullable().optional(),
    extensionRequestedAtEpochMillis: z.number().int().positive().nullable().optional(),
    extensionStatus: z.enum(['PENDING', 'APPROVED', 'DENIED']).nullable().optional(),
    extensionApprovedByUserId: z.string().uuid().nullable().optional(),
    extensionNewDueAtEpochMillis: z.number().int().positive().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key checkout update');

  const [existing] = await pool.execute(`SELECT * FROM key_checkouts WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key checkout not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const [result] = await pool.execute(
    `UPDATE key_checkouts SET
       due_at_epoch_ms = :dueAtEpochMillis,
       status = :status,
       returned_at_epoch_ms = :returnedAtEpochMillis,
       is_emergency = :isEmergency,
       emergency_window_ends_at_epoch_ms = :emergencyWindowEndsAtEpochMillis,
       extension_requested_at_epoch_ms = :extensionRequestedAtEpochMillis,
       extension_status = :extensionStatus,
       extension_approved_by_user_id = :extensionApprovedByUserId,
       extension_new_due_at_epoch_ms = :extensionNewDueAtEpochMillis,
       revision = revision + 1
     WHERE id = :id AND revision = :expectedRevision`,
    {
      id: req.params.id,
      dueAtEpochMillis: parsed.data.dueAtEpochMillis,
      status: parsed.data.status,
      returnedAtEpochMillis: parsed.data.returnedAtEpochMillis ?? null,
      isEmergency: parsed.data.isEmergency ? 1 : 0,
      emergencyWindowEndsAtEpochMillis: parsed.data.emergencyWindowEndsAtEpochMillis ?? null,
      extensionRequestedAtEpochMillis: parsed.data.extensionRequestedAtEpochMillis ?? null,
      extensionStatus: parsed.data.extensionStatus ?? null,
      extensionApprovedByUserId: parsed.data.extensionApprovedByUserId ?? null,
      extensionNewDueAtEpochMillis: parsed.data.extensionNewDueAtEpochMillis ?? null,
      expectedRevision: parsed.data.expectedRevision,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: parsed.data.status === 'RETURNED' ? 'KEY_CHECKOUT_RETURNED' : 'KEY_CHECKOUT_UPDATED',
    actorUserId: req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub,
    terminalId: existing[0].terminal_id,
    entityType: 'KEY_CHECKOUT',
    entityId: req.params.id,
    detail: `dueDateSource=${parsed.data.dueDateSource}`,
  });

  const [rows] = await pool.execute(`SELECT * FROM key_checkouts WHERE id = :id`, { id: req.params.id });
  return res.json(mapCheckout(rows[0]));
});

export default router;
