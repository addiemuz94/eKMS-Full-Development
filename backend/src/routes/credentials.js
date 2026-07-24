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
    enrollmentReference: row.enrollment_reference ?? null,
    note: row.note,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

/** TERMINAL_DEVICE-scoped tokens have no real user behind them — never attribute an audit
 * record's actorUserId to a terminal's own id. See requireSuperAdminOrAllowedTerminalDevice. */
function actorUserIdFor(req) {
  return req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
}

async function requireActiveUser(userId, res) {
  const [users] = await pool.execute(
    `SELECT id FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: userId },
  );
  if (!users[0]) {
    notFound(res, 'User not found');
    return false;
  }
  return true;
}

router.get('/', async (req, res) => {
  const userId = req.params.userId;
  if (!(await requireActiveUser(userId, res))) return;

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
  if (!(await requireActiveUser(userId, res))) return;

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
           enrollment_reference = NULL,
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
        (id, user_id, credential_kind, enrollment_status, terminal_id, enrollment_reference, note, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :userId, :kind, 'PENDING_TERMINAL_ENROLLMENT', :terminalId, NULL, :note, 1,
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
    actorUserId: actorUserIdFor(req),
    terminalId: parsed.data.terminalId ?? null,
    entityType: 'CREDENTIAL',
    entityId: id,
    detail: parsed.data.credentialKind,
  });

  const [rows] = await pool.execute(`SELECT * FROM credential_statuses WHERE id = :id`, { id });
  return res.status(201).json(mapCred(rows[0]));
});

router.post('/complete', async (req, res) => {
  const schema = z.object({
    credentialKind: z.enum(KINDS),
    enrollmentReference: z.string().min(1).max(128),
    terminalId: z.string().uuid().nullable().optional(),
    expectedRevision: z.number().int().nonnegative().optional(),
    note: z.string().max(255).optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid credential enrollment completion');

  // Reject payloads that look like raw UIDs (hex blobs) — only opaque refs.
  const ref = parsed.data.enrollmentReference.trim();
  if (!/^cardref_[A-Za-z0-9_-]+$/i.test(ref) && !/^ref_[A-Za-z0-9_-]+$/i.test(ref)) {
    if (/^[0-9A-Fa-f]{4,32}$/.test(ref)) {
      return badRequest(res, 'Raw NFC UIDs must not be sent; use an opaque enrollment reference');
    }
  }

  const userId = req.params.userId;
  if (!(await requireActiveUser(userId, res))) return;

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
       SET enrollment_status = 'ACTIVE',
           terminal_id = :terminalId,
           enrollment_reference = :enrollmentReference,
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
        enrollmentReference: ref,
        note: parsed.data.note ?? 'Enrolled on Terminal',
        now,
      },
    );
  } else {
    id = newId();
    await pool.execute(
      `INSERT INTO credential_statuses
        (id, user_id, credential_kind, enrollment_status, terminal_id, enrollment_reference, note, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :userId, :kind, 'ACTIVE', :terminalId, :enrollmentReference, :note, 1,
         'ACTIVE', :now, :now)`,
      {
        id,
        userId,
        kind: parsed.data.credentialKind,
        terminalId: parsed.data.terminalId ?? null,
        enrollmentReference: ref,
        note: parsed.data.note ?? 'Enrolled on Terminal',
        now,
      },
    );
  }

  await writeAudit({
    eventType: 'USER_CREDENTIAL_ENROLLED',
    actorUserId: actorUserIdFor(req),
    terminalId: parsed.data.terminalId ?? null,
    entityType: 'CREDENTIAL',
    entityId: id,
    detail: parsed.data.credentialKind,
  });

  const [rows] = await pool.execute(`SELECT * FROM credential_statuses WHERE id = :id`, { id });
  return res.status(200).json(mapCred(rows[0]));
});

router.post('/revoke', async (req, res) => {
  const schema = z.object({
    credentialKind: z.enum(KINDS),
    expectedRevision: z.number().int().nonnegative().optional(),
    note: z.string().max(255).optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid credential enrollment revoke');

  const userId = req.params.userId;
  if (!(await requireActiveUser(userId, res))) return;

  const [existing] = await pool.execute(
    `SELECT * FROM credential_statuses
     WHERE user_id = :userId AND credential_kind = :kind LIMIT 1`,
    { userId, kind: parsed.data.credentialKind },
  );
  if (!existing[0]) return notFound(res, 'Credential enrollment not found');

  if (
    parsed.data.expectedRevision != null &&
    Number(existing[0].revision) !== parsed.data.expectedRevision
  ) {
    return res.status(409).json({ error: 'CONFLICT', message: 'expectedRevision does not match' });
  }

  const now = nowMs();
  await pool.execute(
    `UPDATE credential_statuses
     SET enrollment_status = 'NOT_ASSIGNED',
         terminal_id = NULL,
         enrollment_reference = NULL,
         note = :note,
         revision = revision + 1,
         updated_at_epoch_ms = :now
     WHERE id = :id`,
    {
      id: existing[0].id,
      note: parsed.data.note ?? 'Revoked on Terminal',
      now,
    },
  );

  await writeAudit({
    eventType: 'USER_CREDENTIAL_REVOKED',
    actorUserId: actorUserIdFor(req),
    terminalId: null,
    entityType: 'CREDENTIAL',
    entityId: existing[0].id,
    detail: parsed.data.credentialKind,
  });

  const [rows] = await pool.execute(`SELECT * FROM credential_statuses WHERE id = :id`, {
    id: existing[0].id,
  });
  return res.json(mapCred(rows[0]));
});

export default router;
