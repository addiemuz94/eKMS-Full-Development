import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, conflict, newId, notFound, nowMs, writeAudit } from '../util.js';

function softDeleteRouter({ table, mapRow, createSchema, updateSchema, insertSql, updateSql, entityType }) {
  const router = Router();

  router.get('/', async (req, res) => {
    const state = req.query.state || 'ACTIVE';
    const siteId = req.query.siteId;
    let sql = `SELECT * FROM ${table} WHERE lifecycle_state = :state`;
    const params = { state };
    if (siteId) {
      sql += ` AND site_id = :siteId`;
      params.siteId = siteId;
    }
    sql += ` ORDER BY updated_at_epoch_ms DESC`;
    const [rows] = await pool.execute(sql, params);
    res.json({ items: rows.map(mapRow) });
  });

  router.get('/:id', async (req, res) => {
    const [rows] = await pool.execute(`SELECT * FROM ${table} WHERE id = :id LIMIT 1`, {
      id: req.params.id,
    });
    if (!rows[0]) return notFound(res);
    return res.json(mapRow(rows[0]));
  });

  router.post('/', async (req, res) => {
    const parsed = createSchema.safeParse(req.body);
    if (!parsed.success) return badRequest(res, 'Invalid create payload');
    const id = newId();
    const now = nowMs();
    await pool.execute(insertSql, {
      id,
      now,
      ...Object.fromEntries(Object.entries(parsed.data).map(([key, value]) => [key, value ?? null])),
    });
    await writeAudit({
      eventType: `${entityType}_CREATED`,
      actorUserId: req.auth.sub,
      siteId: parsed.data.siteId ?? null,
      entityType,
      entityId: id,
    });
    const [rows] = await pool.execute(`SELECT * FROM ${table} WHERE id = :id`, { id });
    return res.status(201).json(mapRow(rows[0]));
  });

  router.patch('/:id', async (req, res) => {
    const parsed = updateSchema.safeParse(req.body);
    if (!parsed.success) return badRequest(res, 'Invalid update payload');
    const [existing] = await pool.execute(
      `SELECT * FROM ${table} WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
      { id: req.params.id },
    );
    if (!existing[0]) return notFound(res);
    if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);
    const now = nowMs();
    const [result] = await pool.execute(updateSql, {
      id: req.params.id,
      now,
      ...Object.fromEntries(Object.entries(parsed.data).map(([key, value]) => [key, value ?? null])),
    });
    if (result.affectedRows === 0) return conflict(res);
    await writeAudit({
      eventType: `${entityType}_UPDATED`,
      actorUserId: req.auth.sub,
      siteId: parsed.data.siteId ?? existing[0].site_id,
      entityType,
      entityId: req.params.id,
    });
    const [rows] = await pool.execute(`SELECT * FROM ${table} WHERE id = :id`, { id: req.params.id });
    return res.json(mapRow(rows[0]));
  });

  router.delete('/:id', async (req, res) => {
    const [existing] = await pool.execute(
      `SELECT * FROM ${table} WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
      { id: req.params.id },
    );
    if (!existing[0]) return notFound(res);
    const now = nowMs();
    await pool.execute(
      `UPDATE ${table}
       SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
           revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
      { id: req.params.id, now, actor: req.auth.sub },
    );
    await writeAudit({
      eventType: 'RECORD_MOVED_TO_BIN',
      actorUserId: req.auth.sub,
      entityType,
      entityId: req.params.id,
    });
    const [rows] = await pool.execute(`SELECT * FROM ${table} WHERE id = :id`, { id: req.params.id });
    return res.json(mapRow(rows[0]));
  });

  return router;
}

const eventDefinitionsRouter = softDeleteRouter({
  table: 'event_definitions',
  entityType: 'EVENT_DEFINITION',
  mapRow: (row) => ({
    id: row.id,
    siteId: row.site_id,
    name: row.name,
    eventNumber: row.event_number,
    requirement: row.requirement,
    revision: Number(row.revision),
  }),
  createSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    eventNumber: z.string().min(1),
    requirement: z.string().nullable().optional(),
  }),
  updateSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    eventNumber: z.string().min(1),
    requirement: z.string().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  }),
  insertSql: `INSERT INTO event_definitions
    (id, site_id, name, event_number, requirement, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
   VALUES (:id, :siteId, :name, :eventNumber, :requirement, 1, 'ACTIVE', :now, :now)`,
  updateSql: `UPDATE event_definitions
   SET site_id = :siteId, name = :name, event_number = :eventNumber, requirement = :requirement,
       revision = revision + 1, updated_at_epoch_ms = :now
   WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
});

const schedulesRouter = softDeleteRouter({
  table: 'schedules',
  entityType: 'SCHEDULE',
  mapRow: (row) => ({
    id: row.id,
    siteId: row.site_id,
    name: row.name,
    frequency: row.frequency,
    timeWindowLabel: row.time_window_label,
    revision: Number(row.revision),
  }),
  createSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    frequency: z.enum(['DAILY', 'WEEKLY', 'MONTHLY']),
    timeWindowLabel: z.string().min(1),
  }),
  updateSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    frequency: z.enum(['DAILY', 'WEEKLY', 'MONTHLY']),
    timeWindowLabel: z.string().min(1),
    expectedRevision: z.number().int().nonnegative(),
  }),
  insertSql: `INSERT INTO schedules
    (id, site_id, name, frequency, time_window_label, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
   VALUES (:id, :siteId, :name, :frequency, :timeWindowLabel, 1, 'ACTIVE', :now, :now)`,
  updateSql: `UPDATE schedules
   SET site_id = :siteId, name = :name, frequency = :frequency, time_window_label = :timeWindowLabel,
       revision = revision + 1, updated_at_epoch_ms = :now
   WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
});

function namedGroupRouter(table, entityType) {
  return softDeleteRouter({
    table,
    entityType,
    mapRow: (row) => ({
      id: row.id,
      siteId: row.site_id,
      name: row.name,
      code: row.code,
      revision: Number(row.revision),
    }),
    createSchema: z.object({
      siteId: z.string().uuid(),
      name: z.string().min(1),
      code: z.string().min(1),
    }),
    updateSchema: z.object({
      siteId: z.string().uuid(),
      name: z.string().min(1),
      code: z.string().min(1),
      expectedRevision: z.number().int().nonnegative(),
    }),
    insertSql: `INSERT INTO ${table}
      (id, site_id, name, code, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
     VALUES (:id, :siteId, :name, :code, 1, 'ACTIVE', :now, :now)`,
    updateSql: `UPDATE ${table}
     SET site_id = :siteId, name = :name, code = :code,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
  });
}

const multiAuthRulesRouter = softDeleteRouter({
  table: 'multi_authentication_rules',
  entityType: 'MULTI_AUTH_RULE',
  mapRow: (row) => ({
    id: row.id,
    siteId: row.site_id,
    primaryPersonnelGroupId: row.primary_personnel_group_id,
    assistantGroupOneId: row.assistant_group_one_id,
    assistantGroupTwoId: row.assistant_group_two_id,
    keyGroupId: row.key_group_id,
    revision: Number(row.revision),
  }),
  createSchema: z.object({
    siteId: z.string().uuid(),
    primaryPersonnelGroupId: z.string().uuid(),
    assistantGroupOneId: z.string().uuid().nullable().optional(),
    assistantGroupTwoId: z.string().uuid().nullable().optional(),
    keyGroupId: z.string().uuid(),
  }),
  updateSchema: z.object({
    siteId: z.string().uuid(),
    primaryPersonnelGroupId: z.string().uuid(),
    assistantGroupOneId: z.string().uuid().nullable().optional(),
    assistantGroupTwoId: z.string().uuid().nullable().optional(),
    keyGroupId: z.string().uuid(),
    expectedRevision: z.number().int().nonnegative(),
  }),
  insertSql: `INSERT INTO multi_authentication_rules
    (id, site_id, primary_personnel_group_id, assistant_group_one_id, assistant_group_two_id, key_group_id,
     revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
   VALUES (:id, :siteId, :primaryPersonnelGroupId, :assistantGroupOneId, :assistantGroupTwoId, :keyGroupId,
     1, 'ACTIVE', :now, :now)`,
  updateSql: `UPDATE multi_authentication_rules
   SET site_id = :siteId, primary_personnel_group_id = :primaryPersonnelGroupId,
       assistant_group_one_id = :assistantGroupOneId, assistant_group_two_id = :assistantGroupTwoId,
       key_group_id = :keyGroupId, revision = revision + 1, updated_at_epoch_ms = :now
   WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
});

const appointmentReasonsRouter = softDeleteRouter({
  table: 'appointment_reasons',
  entityType: 'APPOINTMENT_REASON',
  mapRow: (row) => ({
    id: row.id,
    siteId: row.site_id,
    name: row.name,
    active: Boolean(row.active),
    revision: Number(row.revision),
  }),
  createSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    active: z.boolean().optional(),
  }),
  updateSchema: z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    active: z.boolean().optional(),
    expectedRevision: z.number().int().nonnegative(),
  }),
  insertSql: `INSERT INTO appointment_reasons
    (id, site_id, name, active, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
   VALUES (:id, :siteId, :name, :active, 1, 'ACTIVE', :now, :now)`,
  updateSql: `UPDATE appointment_reasons
   SET site_id = :siteId, name = :name, active = :active,
       revision = revision + 1, updated_at_epoch_ms = :now
   WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
});

// Normalize optional booleans for appointment reasons insert
const appointmentReasonsCreate = appointmentReasonsRouter;
const patchedReasons = Router();
patchedReasons.use((req, _res, next) => {
  if (req.method === 'POST' || req.method === 'PATCH') {
    if (req.body && req.body.active === undefined) req.body.active = true;
  }
  next();
});
patchedReasons.use(appointmentReasonsCreate);

async function appointmentKeyIds(appointmentId) {
  const [rows] = await pool.execute(
    `SELECT key_id FROM appointment_keys WHERE appointment_id = :id`,
    { id: appointmentId },
  );
  return rows.map((r) => r.key_id);
}

function mapAppointment(row, keyIds) {
  return {
    id: row.id,
    siteId: row.site_id,
    terminalId: row.terminal_id,
    userId: row.user_id,
    reasonId: row.reason_id,
    reasonLabel: row.reason_label,
    keyIds,
    pickupWindowLabel: row.pickup_window_label,
    validFromEpochMillis: row.valid_from_epoch_ms == null ? null : Number(row.valid_from_epoch_ms),
    validUntilEpochMillis: row.valid_until_epoch_ms == null ? null : Number(row.valid_until_epoch_ms),
    status: row.status,
    reviewerUserId: row.reviewer_user_id,
    reviewDetail: row.review_detail,
    revision: Number(row.revision),
  };
}

const appointmentsRouter = Router();

appointmentsRouter.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  let sql = `SELECT * FROM appointments WHERE lifecycle_state = :state`;
  const params = { state };
  if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }
  sql += ` ORDER BY created_at_epoch_ms DESC`;
  const [rows] = await pool.execute(sql, params);
  const items = [];
  for (const row of rows) {
    items.push(mapAppointment(row, await appointmentKeyIds(row.id)));
  }
  res.json({ items });
});

appointmentsRouter.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Appointment not found');
  return res.json(mapAppointment(rows[0], await appointmentKeyIds(rows[0].id)));
});

appointmentsRouter.post('/', async (req, res) => {
  const schema = z.object({
    siteId: z.string().uuid(),
    terminalId: z.string().uuid(),
    userId: z.string().uuid(),
    reasonId: z.string().uuid().nullable().optional(),
    reasonLabel: z.string().nullable().optional(),
    keyIds: z.array(z.string().uuid()).default([]),
    pickupWindowLabel: z.string().min(1),
    validFromEpochMillis: z.number().int().nullable().optional(),
    validUntilEpochMillis: z.number().int().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid appointment payload');

  const id = newId();
  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    await conn.execute(
      `INSERT INTO appointments
        (id, site_id, terminal_id, user_id, reason_id, reason_label, pickup_window_label,
         valid_from_epoch_ms, valid_until_epoch_ms, status, revision, lifecycle_state,
         created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :siteId, :terminalId, :userId, :reasonId, :reasonLabel, :pickupWindowLabel,
         :validFrom, :validUntil, 'PENDING', 1, 'ACTIVE', :now, :now)`,
      {
        id,
        siteId: parsed.data.siteId,
        terminalId: parsed.data.terminalId,
        userId: parsed.data.userId,
        reasonId: parsed.data.reasonId ?? null,
        reasonLabel: parsed.data.reasonLabel ?? null,
        pickupWindowLabel: parsed.data.pickupWindowLabel,
        validFrom: parsed.data.validFromEpochMillis ?? null,
        validUntil: parsed.data.validUntilEpochMillis ?? null,
        now,
      },
    );
    for (const keyId of parsed.data.keyIds) {
      await conn.execute(
        `INSERT INTO appointment_keys (appointment_id, key_id) VALUES (:id, :keyId)`,
        { id, keyId },
      );
    }
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  await writeAudit({
    eventType: 'APPOINTMENT_CREATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    terminalId: parsed.data.terminalId,
    entityType: 'APPOINTMENT',
    entityId: id,
  });
  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id`, { id });
  return res.status(201).json(mapAppointment(rows[0], await appointmentKeyIds(id)));
});

appointmentsRouter.post('/:id/review', async (req, res) => {
  const schema = z.object({
    status: z.enum(['APPROVED', 'REJECTED']),
    reviewDetail: z.string().nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid review payload');

  const [existing] = await pool.execute(
    `SELECT * FROM appointments WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Appointment not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);
  if (existing[0].status !== 'PENDING') {
    return badRequest(res, 'Only PENDING appointments can be reviewed');
  }

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE appointments
     SET status = :status, reviewer_user_id = :reviewer, review_detail = :detail,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      status: parsed.data.status,
      reviewer: req.auth.sub,
      detail: parsed.data.reviewDetail ?? null,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'APPOINTMENT_REVIEWED',
    actorUserId: req.auth.sub,
    siteId: existing[0].site_id,
    terminalId: existing[0].terminal_id,
    entityType: 'APPOINTMENT',
    entityId: req.params.id,
    detail: parsed.data.status,
  });
  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id`, { id: req.params.id });
  return res.json(mapAppointment(rows[0], await appointmentKeyIds(req.params.id)));
});

appointmentsRouter.patch('/:id/permissions', async (req, res) => {
  const schema = z.object({
    keyIds: z.array(z.string().uuid()),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid permissions payload');

  const [existing] = await pool.execute(
    `SELECT * FROM appointments WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Appointment not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE appointments
       SET revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
      { id: req.params.id, expectedRevision: parsed.data.expectedRevision, now },
    );
    if (result.affectedRows === 0) {
      await conn.rollback();
      return conflict(res);
    }
    await conn.execute(`DELETE FROM appointment_keys WHERE appointment_id = :id`, { id: req.params.id });
    for (const keyId of parsed.data.keyIds) {
      await conn.execute(
        `INSERT INTO appointment_keys (appointment_id, key_id) VALUES (:id, :keyId)`,
        { id: req.params.id, keyId },
      );
    }
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id`, { id: req.params.id });
  return res.json(mapAppointment(rows[0], await appointmentKeyIds(req.params.id)));
});

appointmentsRouter.delete('/:id', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM appointments WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Appointment not found');
  const now = nowMs();
  await pool.execute(
    `UPDATE appointments
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: req.params.id, now, actor: req.auth.sub },
  );
  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id`, { id: req.params.id });
  return res.json(mapAppointment(rows[0], await appointmentKeyIds(req.params.id)));
});

// Handover path: PATCH /v1/admin/appointment-permissions/{id}
const appointmentPermissionsRouter = Router();
appointmentPermissionsRouter.patch('/:id', async (req, res) => {
  const schema = z.object({
    keyIds: z.array(z.string().uuid()),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid permissions payload');

  const [existing] = await pool.execute(
    `SELECT * FROM appointments WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Appointment not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE appointments
       SET revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
      { id: req.params.id, expectedRevision: parsed.data.expectedRevision, now },
    );
    if (result.affectedRows === 0) {
      await conn.rollback();
      return conflict(res);
    }
    await conn.execute(`DELETE FROM appointment_keys WHERE appointment_id = :id`, { id: req.params.id });
    for (const keyId of parsed.data.keyIds) {
      await conn.execute(
        `INSERT INTO appointment_keys (appointment_id, key_id) VALUES (:id, :keyId)`,
        { id: req.params.id, keyId },
      );
    }
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  const [rows] = await pool.execute(`SELECT * FROM appointments WHERE id = :id`, { id: req.params.id });
  return res.json(mapAppointment(rows[0], await appointmentKeyIds(req.params.id)));
});

async function queryAudit(req, eventTypes) {
  const siteId = req.query.siteId;
  const terminalId = req.query.terminalId;
  const userId = req.query.userId;
  const keyId = req.query.keyId;
  const fromMs = req.query.fromEpochMillis ? Number(req.query.fromEpochMillis) : null;
  const toMs = req.query.untilEpochMillis ? Number(req.query.untilEpochMillis) : null;
  const limit = Math.min(Number(req.query.limit || 100), 500);

  let sql = `SELECT * FROM audit_events WHERE event_type IN (${eventTypes.map((_, i) => `:t${i}`).join(',')})`;
  const params = {};
  eventTypes.forEach((t, i) => {
    params[`t${i}`] = t;
  });
  if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }
  if (terminalId) {
    sql += ` AND terminal_id = :terminalId`;
    params.terminalId = terminalId;
  }
  if (userId) {
    sql += ` AND actor_user_id = :userId`;
    params.userId = userId;
  }
  if (keyId) {
    sql += ` AND entity_id = :keyId`;
    params.keyId = keyId;
  }
  if (fromMs != null && !Number.isNaN(fromMs)) {
    sql += ` AND occurred_at_epoch_ms >= :fromMs`;
    params.fromMs = fromMs;
  }
  if (toMs != null && !Number.isNaN(toMs)) {
    sql += ` AND occurred_at_epoch_ms <= :toMs`;
    params.toMs = toMs;
  }
  sql += ` ORDER BY occurred_at_epoch_ms DESC LIMIT ${limit}`;
  const [rows] = await pool.execute(sql, params);
  return rows.map((row) => ({
    id: row.id,
    occurredAtEpochMillis: Number(row.occurred_at_epoch_ms),
    eventType: row.event_type,
    terminalId: row.terminal_id,
    siteId: row.site_id,
    actorUserId: row.actor_user_id,
    entityId: row.entity_id,
    detail: row.detail,
  }));
}

const reportsRouter = Router();

reportsRouter.get('/key-operations', async (req, res) => {
  res.json({ items: await queryAudit(req, ['KEY_TAKEN', 'KEY_RETURNED']) });
});

reportsRouter.get('/system-operation-logs', async (req, res) => {
  res.json({
    items: await queryAudit(req, [
      'LOGIN_SUCCEEDED',
      'LOGIN_DENIED',
      'USER_ACCOUNT_STATUS_CHANGED',
      'RECORD_MOVED_TO_BIN',
      'RECORD_RESTORED',
      'RECORD_PURGED',
      'SITE_CREATED',
      'SITE_UPDATED',
      'TERMINAL_CREATED',
      'TERMINAL_UPDATED',
      'APPOINTMENT_CREATED',
      'APPOINTMENT_REVIEWED',
      'CONFLICT_CREATED',
      'CONFLICT_RESOLVED',
    ]),
  });
});

reportsRouter.get('/equipment-operation-logs', async (req, res) => {
  res.json({
    items: await queryAudit(req, [
      'KEY_TAKEN',
      'KEY_RETURNED',
      'TERMINAL_HARDWARE_CONFIGURATION_CHANGED',
      'KEY_FOB_ENROLLED',
    ]),
  });
});

reportsRouter.post('/exports', async (req, res) => {
  const schema = z.object({
    kind: z.enum(['KEY_OPERATIONS', 'SYSTEM_OPERATION_LOGS', 'EQUIPMENT_OPERATION_LOGS']),
    format: z.enum(['PDF', 'EXCEL']),
    filter: z
      .object({
        siteId: z.string().uuid().optional(),
        terminalId: z.string().uuid().optional(),
        userId: z.string().uuid().optional(),
        keyId: z.string().uuid().optional(),
        fromEpochMillis: z.number().optional(),
        untilEpochMillis: z.number().optional(),
        limit: z.number().int().positive().optional(),
      })
      .default({}),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid export request');

  const fakeReq = { query: { ...parsed.data.filter } };
  const eventMap = {
    KEY_OPERATIONS: ['KEY_TAKEN', 'KEY_RETURNED'],
    SYSTEM_OPERATION_LOGS: ['LOGIN_SUCCEEDED', 'LOGIN_DENIED', 'APPOINTMENT_REVIEWED'],
    EQUIPMENT_OPERATION_LOGS: ['KEY_TAKEN', 'KEY_RETURNED', 'KEY_FOB_ENROLLED'],
  };
  const rows = await queryAudit(fakeReq, eventMap[parsed.data.kind]);
  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO report_export_jobs
      (id, kind, format, status, filter_json, row_count, download_path, created_by_user_id, created_at_epoch_ms)
     VALUES
      (:id, :kind, :format, 'READY', :filterJson, :rowCount, :downloadPath, :actor, :now)`,
    {
      id,
      kind: parsed.data.kind,
      format: parsed.data.format,
      filterJson: JSON.stringify(parsed.data.filter),
      rowCount: rows.length,
      downloadPath: `/v1/reports/exports/${id}`,
      actor: req.auth.sub,
      now,
    },
  );

  res.status(201).json({
    jobId: id,
    kind: parsed.data.kind,
    format: parsed.data.format,
    status: 'READY',
    createdAtEpochMillis: now,
    downloadPath: `/v1/reports/exports/${id}`,
    rowCount: rows.length,
  });
});

export {
  eventDefinitionsRouter,
  schedulesRouter,
  namedGroupRouter,
  multiAuthRulesRouter,
  patchedReasons as appointmentReasonsRouter,
  appointmentsRouter,
  appointmentPermissionsRouter,
  reportsRouter,
};
