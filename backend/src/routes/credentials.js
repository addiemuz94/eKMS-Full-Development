import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, lifecycleFromRow, newId, notFound, nowMs, writeAudit } from '../util.js';

const router = Router({ mergeParams: true });

const KINDS = [
  'NFC_CARD',
  'STATIC_UID_DIGITAL_KEY_PROTOTYPE',
  'FINGERPRINT',
  'FACE_RECOGNITION',
  'VENDOR_PASSKEY',
];

function mapCred(row) {
  return {
    id: row.id,
    userId: row.user_id,
    credentialKind: row.credential_kind,
    enrollmentStatus: row.enrollment_status,
    terminalId: row.terminal_id,
    note: row.note,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

router.get('/', async (req, res) => {
  const userId = req.params.userId;
  const [users] = await pool.execute(
    `SELECT id FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: userId },
  );
  if (!users[0]) return notFound(res, 'User not found');

  const [rows] = await pool.execute(
    `SELECT * FROM credential_statuses
     WHERE user_id = :userId AND lifecycle_state = 'ACTIVE'
     ORDER BY credential_kind ASC`,
    { userId },
  );
  res.json({ items: rows.map(mapCred) });
});

router.post('/', async (req, res) => {
  const schema = z.object({
    credentialKind: z.enum(KINDS),
    terminalId: z.string().uuid().nullable().optional(),
    expectedRevision: z.number().int().nonnegative().optional(),
    note: z.string().max(255).optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid credential enrollment request');

  const userId = req.params.userId;
  const [users] = await pool.execute(
    `SELECT id FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: userId },
  );
  if (!users[0]) return notFound(res, 'User not found');

  const now = nowMs();
  const [existing] = await pool.execute(
    `SELECT * FROM credential_statuses
     WHERE user_id = :userId AND credential_kind = :kind LIMIT 1`,
    { userId, kind: parsed.data.credentialKind },
  );

  let id;
  if (existing[0]) {
    if (
      parsed.data.expectedRevision != null &&
      Number(existing[0].revision) !== parsed.data.expectedRevision
    ) {
      return res.status(409).json({ error: 'CONFLICT', message: 'expectedRevision does not match' });
    }
    id = existing[0].id;
    await pool.execute(
      `UPDATE credential_statuses
       SET enrollment_status = 'PENDING_TERMINAL_ENROLLMENT',
           terminal_id = :terminalId,
           note = :note,
           revision = revision + 1,
           updated_at_epoch_ms = :now,
           lifecycle_state = 'ACTIVE',
           deleted_at_epoch_ms = NULL,
           deleted_by_user_id = NULL
       WHERE id = :id`,
      {
        id,
        terminalId: parsed.data.terminalId ?? null,
        note: parsed.data.note ?? 'Enrollment requested from Website',
        now,
      },
    );
  } else {
    id = newId();
    await pool.execute(
      `INSERT INTO credential_statuses
        (id, user_id, credential_kind, enrollment_status, terminal_id, note, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :userId, :kind, 'PENDING_TERMINAL_ENROLLMENT', :terminalId, :note, 1,
         'ACTIVE', :now, :now)`,
      {
        id,
        userId,
        kind: parsed.data.credentialKind,
        terminalId: parsed.data.terminalId ?? null,
        note: parsed.data.note ?? 'Enrollment requested from Website',
        now,
      },
    );
  }

  await writeAudit({
    eventType: 'USER_CREDENTIAL_ENROLLMENT_REQUESTED',
    actorUserId: req.auth.sub,
    terminalId: parsed.data.terminalId ?? null,
    entityType: 'CREDENTIAL',
    entityId: id,
    detail: parsed.data.credentialKind,
  });

  const [rows] = await pool.execute(`SELECT * FROM credential_statuses WHERE id = :id`, { id });
  return res.status(201).json(mapCred(rows[0]));
});

export default router;
