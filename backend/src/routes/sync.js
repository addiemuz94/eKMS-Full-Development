import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, lifecycleFromRow, newId, notFound, nowMs, writeAudit } from '../util.js';

const bootstrapRouter = Router();
const conflictsRouter = Router();

/** TERMINAL_DEVICE-scoped tokens have no real user behind them — never attribute an audit
 * record's actorUserId to a terminal's own id. See requireSuperAdminOrAllowedTerminalDevice.
 * (`conflictsRouter` is admin-only and unreachable by terminal tokens, so this only matters
 * for `bootstrapRouter`'s routes.) */
function actorUserIdFor(req) {
  return req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth?.sub || null;
}

function mapUser(row, siteIds) {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    assignedSiteIds: siteIds,
    accountStatus: row.account_status,
    lifecycle: lifecycleFromRow(row),
  };
}

function mapKey(row) {
  return {
    id: row.id,
    siteId: row.site_id,
    displayName: row.display_name,
    fobEnrollmentReference: row.fob_enrollment_reference,
    lifecycle: lifecycleFromRow(row),
  };
}

function mapSlot(row) {
  return {
    id: row.id,
    terminalId: row.terminal_id,
    nodeAddress: Number(row.node_address),
    managedKeyId: row.managed_key_id,
    lifecycle: lifecycleFromRow(row),
  };
}

function mapGrant(row, keyIds) {
  return {
    id: row.id,
    userId: row.user_id,
    siteId: row.site_id,
    keyIds,
    validFromEpochMillis: row.valid_from_epoch_ms == null ? null : Number(row.valid_from_epoch_ms),
    validUntilEpochMillis:
      row.valid_until_epoch_ms == null ? null : Number(row.valid_until_epoch_ms),
    lifecycle: lifecycleFromRow(row),
  };
}

function mapTerminal(row) {
  return {
    id: row.id,
    siteId: row.site_id,
    name: row.name,
    boxAddress: Number(row.box_address),
    serialNumber: row.serial_number,
    configuredSlotCount: Number(row.configured_slot_count),
    cabinetSerialPort: row.cabinet_serial_port,
    cabinetBaudRate: row.cabinet_baud_rate == null ? null : Number(row.cabinet_baud_rate),
    connectionState: row.connection_state,
    lifecycle: lifecycleFromRow(row),
  };
}

async function ensureSyncState(terminalId) {
  const [rows] = await pool.execute(
    `SELECT * FROM terminal_sync_state WHERE terminal_id = :terminalId LIMIT 1`,
    { terminalId },
  );
  if (rows[0]) return rows[0];
  await pool.execute(
    `INSERT INTO terminal_sync_state (terminal_id, server_revision)
     VALUES (:terminalId, 1)`,
    { terminalId },
  );
  const [created] = await pool.execute(
    `SELECT * FROM terminal_sync_state WHERE terminal_id = :terminalId LIMIT 1`,
    { terminalId },
  );
  return created[0];
}

async function assertTerminal(terminalId) {
  const [rows] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: terminalId },
  );
  return rows[0] || null;
}

async function buildSnapshot(terminalRow) {
  const siteId = terminalRow.site_id;
  const terminalId = terminalRow.id;

  const [userRows] = await pool.execute(
    `SELECT u.* FROM users u
     WHERE u.lifecycle_state = 'ACTIVE'
       AND (
         u.role = 'SUPER_ADMIN'
         OR u.id IN (SELECT user_id FROM user_site_assignments WHERE site_id = :siteId)
       )
     ORDER BY u.display_name ASC`,
    { siteId },
  );
  const users = [];
  for (const row of userRows) {
    const [sites] = await pool.execute(
      `SELECT site_id FROM user_site_assignments WHERE user_id = :userId`,
      { userId: row.id },
    );
    users.push(mapUser(row, sites.map((s) => s.site_id)));
  }

  const [keyRows] = await pool.execute(
    `SELECT * FROM managed_keys WHERE site_id = :siteId AND lifecycle_state = 'ACTIVE'
     ORDER BY display_name ASC`,
    { siteId },
  );
  const [slotRows] = await pool.execute(
    `SELECT * FROM key_slots WHERE terminal_id = :terminalId AND lifecycle_state = 'ACTIVE'
     ORDER BY node_address ASC`,
    { terminalId },
  );
  const [grantRows] = await pool.execute(
    `SELECT * FROM access_grants WHERE site_id = :siteId AND lifecycle_state = 'ACTIVE'
     ORDER BY created_at_epoch_ms DESC`,
    { siteId },
  );
  const accessGrants = [];
  for (const row of grantRows) {
    const [keyLinks] = await pool.execute(
      `SELECT key_id FROM access_grant_keys WHERE grant_id = :grantId`,
      { grantId: row.id },
    );
    accessGrants.push(mapGrant(row, keyLinks.map((k) => k.key_id)));
  }

  return {
    terminal: mapTerminal(terminalRow),
    users,
    keys: keyRows.map(mapKey),
    keySlots: slotRows.map(mapSlot),
    accessGrants,
  };
}

async function ingestAuditEvents(terminalId, siteId, events) {
  if (!Array.isArray(events) || events.length === 0) return 0;
  let count = 0;
  for (const event of events) {
    if (!event?.eventType) continue;
    await writeAudit({
      eventType: String(event.eventType),
      actorUserId: event.actorUserId || null,
      terminalId: event.terminalId || terminalId,
      siteId: event.siteId || siteId,
      entityType: event.entityType || null,
      entityId: event.entityId || null,
      detail: event.detail || null,
    });
    count += 1;
  }
  return count;
}

async function applyOfflineChange(terminalRow, change) {
  const now = nowMs();
  let payload = {};
  try {
    payload = JSON.parse(change.payloadJson || '{}');
  } catch {
    payload = {};
  }
  const type = String(change.entityType || '').toUpperCase();
  const entityId = change.entityId;

  if (type === 'TERMINAL') {
    const name = payload.cabinetName ?? payload.name;
    const slots = payload.configuredKeyNodeCount ?? payload.configuredSlotCount;
    await pool.execute(
      `UPDATE terminals SET
         name = COALESCE(:name, name),
         configured_slot_count = COALESCE(:slots, configured_slot_count),
         revision = revision + 1,
         updated_at_epoch_ms = :now
       WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
      {
        id: terminalRow.id,
        name: name != null && String(name).trim() !== '' ? String(name).trim() : null,
        slots: Number.isFinite(Number(slots)) ? Number(slots) : null,
        now,
      },
    );
    return true;
  }

  if (type === 'KEY') {
    const [existing] = await pool.execute(
      `SELECT id FROM managed_keys WHERE id = :id LIMIT 1`,
      { id: entityId },
    );
    if (existing[0]) {
      await pool.execute(
        `UPDATE managed_keys SET
           display_name = COALESCE(:displayName, display_name),
           revision = revision + 1,
           updated_at_epoch_ms = :now
         WHERE id = :id`,
        {
          id: entityId,
          displayName: payload.displayName ? String(payload.displayName) : null,
          now,
        },
      );
      if (Number.isFinite(Number(payload.nodeAddress))) {
        const nodeAddress = Number(payload.nodeAddress);
        const [slot] = await pool.execute(
          `SELECT id FROM key_slots
           WHERE terminal_id = :terminalId AND node_address = :node AND lifecycle_state = 'ACTIVE'
           LIMIT 1`,
          { terminalId: terminalRow.id, node: nodeAddress },
        );
        if (slot[0]) {
          await pool.execute(
            `UPDATE key_slots SET managed_key_id = :keyId, revision = revision + 1, updated_at_epoch_ms = :now
             WHERE id = :id`,
            { id: slot[0].id, keyId: entityId, now },
          );
        } else {
          await pool.execute(
            `INSERT INTO key_slots
              (id, terminal_id, node_address, managed_key_id, revision, lifecycle_state,
               created_at_epoch_ms, updated_at_epoch_ms)
             VALUES
              (:id, :terminalId, :node, :keyId, 1, 'ACTIVE', :now, :now)`,
            {
              id: newId(),
              terminalId: terminalRow.id,
              node: nodeAddress,
              keyId: entityId,
              now,
            },
          );
        }
      }
      return true;
    }
    await pool.execute(
      `INSERT INTO managed_keys
        (id, site_id, display_name, fob_enrollment_reference, revision, lifecycle_state,
         created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :siteId, :displayName, NULL, 1, 'ACTIVE', :now, :now)`,
      {
        id: entityId,
        siteId: terminalRow.site_id,
        displayName: String(payload.displayName || 'Key').trim() || 'Key',
        now,
      },
    );
    if (Number.isFinite(Number(payload.nodeAddress))) {
      await pool.execute(
        `INSERT INTO key_slots
          (id, terminal_id, node_address, managed_key_id, revision, lifecycle_state,
           created_at_epoch_ms, updated_at_epoch_ms)
         VALUES
          (:id, :terminalId, :node, :keyId, 1, 'ACTIVE', :now, :now)`,
        {
          id: newId(),
          terminalId: terminalRow.id,
          node: Number(payload.nodeAddress),
          keyId: entityId,
          now,
        },
      );
    }
    return true;
  }

  if (type === 'USER') {
    const [existing] = await pool.execute(`SELECT id FROM users WHERE id = :id LIMIT 1`, {
      id: entityId,
    });
    if (existing[0]) {
      await pool.execute(
        `UPDATE users SET
           display_name = COALESCE(:displayName, display_name),
           revision = revision + 1,
           updated_at_epoch_ms = :now
         WHERE id = :id`,
        {
          id: entityId,
          displayName: payload.displayName ? String(payload.displayName) : null,
          now,
        },
      );
      return true;
    }
    // Terminal-created users without a password cannot authenticate on the
    // server until a Super Admin sets one on the portal; still create the row
    // so download stays consistent with the outbox.
    const email =
      payload.email ||
      `${String(payload.username || entityId).toLowerCase().replace(/[^a-z0-9]+/g, '.')}@terminal.local`;
    await pool.execute(
      `INSERT INTO users
        (id, display_name, email, password_hash, role, account_status, revision, lifecycle_state,
         created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :displayName, :email, NULL, :role, 'ACTIVE', 1, 'ACTIVE', :now, :now)`,
      {
        id: entityId,
        displayName: String(payload.displayName || payload.username || 'User').trim() || 'User',
        email,
        role: ['SUPER_ADMIN', 'TECHNICIAN', 'VENDOR'].includes(payload.role)
          ? payload.role
          : 'TECHNICIAN',
        now,
      },
    );
    await pool.execute(
      `INSERT IGNORE INTO user_site_assignments (user_id, site_id) VALUES (:userId, :siteId)`,
      { userId: entityId, siteId: terminalRow.site_id },
    );
    return true;
  }

  if (type === 'ACCESS_GRANT') {
    if (payload.revoked === true) {
      await pool.execute(
        `UPDATE access_grants SET
           lifecycle_state = 'RECYCLE_BIN',
           deleted_at_epoch_ms = :now,
           updated_at_epoch_ms = :now,
           revision = revision + 1
         WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
        { id: entityId, now },
      );
      return true;
    }
    const userId = payload.userId;
    const keyId = payload.keyId;
    if (!userId || !keyId) return true;
    const [existing] = await pool.execute(
      `SELECT id FROM access_grants WHERE id = :id LIMIT 1`,
      { id: entityId },
    );
    if (!existing[0]) {
      await pool.execute(
        `INSERT INTO access_grants
          (id, user_id, site_id, revision, lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
         VALUES
          (:id, :userId, :siteId, 1, 'ACTIVE', :now, :now)`,
        {
          id: entityId,
          userId,
          siteId: terminalRow.site_id,
          now,
        },
      );
      await pool.execute(
        `INSERT INTO access_grant_keys (grant_id, key_id) VALUES (:grantId, :keyId)`,
        { grantId: entityId, keyId },
      );
    }
    return true;
  }

  // Unknown entity types are accepted into revision history without failing the push.
  return true;
}

bootstrapRouter.post('/bootstrap', async (req, res) => {
  const schema = z.object({
    terminalId: z.string().uuid(),
    lastSuccessfulSyncEpochMillis: z.number().nullable().optional(),
    localRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid bootstrap payload');

  const terminal = await assertTerminal(parsed.data.terminalId);
  if (!terminal) return notFound(res, 'Terminal not found');

  const state = await ensureSyncState(parsed.data.terminalId);
  const now = nowMs();
  await pool.execute(
    `UPDATE terminal_sync_state
     SET last_bootstrap_at_epoch_ms = :now
     WHERE terminal_id = :terminalId`,
    { now, terminalId: parsed.data.terminalId },
  );

  const snapshot = await buildSnapshot(terminal);
  res.json({
    serverRevision: Number(state.server_revision),
    issuedAtEpochMillis: now,
    changesJson: [],
    snapshot,
  });
});

bootstrapRouter.post('/push', async (req, res) => {
  const schema = z.object({
    terminalId: z.string().uuid(),
    changes: z
      .array(
        z.object({
          operationId: z.string().min(1),
          entityType: z.string().min(1),
          entityId: z.string().min(1),
          baseRevision: z.number().int().nonnegative(),
          submittedAtEpochMillis: z.number().int().nonnegative(),
          submittedByUserId: z.string().min(1),
          payloadJson: z.string(),
        }),
      )
      .default([]),
    auditEvents: z.array(z.any()).default([]),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid sync push payload');

  const terminal = await assertTerminal(parsed.data.terminalId);
  if (!terminal) return notFound(res, 'Terminal not found');

  const state = await ensureSyncState(parsed.data.terminalId);
  const serverRevision = Number(state.server_revision);
  const now = nowMs();
  const accepted = [];
  const conflicts = [];

  for (const change of parsed.data.changes) {
    if (change.baseRevision < serverRevision) {
      const conflictId = newId();
      await pool.execute(
        `INSERT INTO sync_conflicts
          (id, terminal_id, entity_type, entity_id, server_revision, local_operation_id,
           local_base_revision, local_payload_json, submitted_by_user_id, submitted_at_epoch_ms, created_at_epoch_ms)
         VALUES
          (:id, :terminalId, :entityType, :entityId, :serverRevision, :opId,
           :baseRevision, :payload, :submittedBy, :submittedAt, :now)`,
        {
          id: conflictId,
          terminalId: parsed.data.terminalId,
          entityType: change.entityType,
          entityId: change.entityId,
          serverRevision,
          opId: change.operationId,
          baseRevision: change.baseRevision,
          payload: change.payloadJson,
          submittedBy: change.submittedByUserId,
          submittedAt: change.submittedAtEpochMillis,
          now,
        },
      );
      conflicts.push({
        id: conflictId,
        entityType: change.entityType,
        entityId: change.entityId,
        serverRevision,
        localChange: change,
        createdAtEpochMillis: now,
        requiresSuperAdminReview: true,
        resolution: null,
      });
    } else {
      await applyOfflineChange(terminal, change);
      accepted.push(change.operationId);
    }
  }

  await ingestAuditEvents(parsed.data.terminalId, terminal.site_id, parsed.data.auditEvents);

  await pool.execute(
    `UPDATE terminal_sync_state
     SET server_revision = server_revision + 1,
         last_push_at_epoch_ms = :now
     WHERE terminal_id = :terminalId`,
    { now, terminalId: parsed.data.terminalId },
  );

  await writeAudit({
    eventType: conflicts.length > 0 ? 'CONFLICT_CREATED' : 'TERMINAL_UPDATED',
    actorUserId: actorUserIdFor(req),
    terminalId: parsed.data.terminalId,
    siteId: terminal.site_id,
    entityType: 'TERMINAL',
    entityId: parsed.data.terminalId,
    detail: `accepted=${accepted.length};conflicts=${conflicts.length}`,
  });

  res.json({ acceptedOperationIds: accepted, conflicts });
});

bootstrapRouter.post('/read', async (req, res) => {
  const schema = z.object({ terminalId: z.string().uuid() });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid read payload');
  const terminal = await assertTerminal(parsed.data.terminalId);
  if (!terminal) return notFound(res, 'Terminal not found');
  await ensureSyncState(parsed.data.terminalId);
  const now = nowMs();
  res.json({
    ok: true,
    terminalId: parsed.data.terminalId,
    requestedAtEpochMillis: now,
    message: 'Read request accepted. Terminal must push offline edits via /push.',
  });
});

bootstrapRouter.post('/download', async (req, res) => {
  const schema = z.object({ terminalId: z.string().uuid() });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid download payload');
  const terminal = await assertTerminal(parsed.data.terminalId);
  if (!terminal) return notFound(res, 'Terminal not found');
  const state = await ensureSyncState(parsed.data.terminalId);
  const now = nowMs();
  await pool.execute(
    `UPDATE terminal_sync_state
     SET last_download_at_epoch_ms = :now
     WHERE terminal_id = :terminalId`,
    { now, terminalId: parsed.data.terminalId },
  );
  const snapshot = await buildSnapshot(terminal);
  res.json({
    ok: true,
    terminalId: parsed.data.terminalId,
    serverRevision: Number(state.server_revision),
    issuedAtEpochMillis: now,
    message: `Download ready · ${snapshot.keys.length} keys, ${snapshot.keySlots.length} slots, ${snapshot.users.length} users.`,
    snapshot,
  });
});

conflictsRouter.get('/', async (_req, res) => {
  const [rows] = await pool.execute(
    `SELECT * FROM sync_conflicts WHERE resolved_at_epoch_ms IS NULL ORDER BY created_at_epoch_ms DESC`,
  );
  res.json({
    items: rows.map((row) => ({
      id: row.id,
      entityType: row.entity_type,
      entityId: row.entity_id,
      serverRevision: Number(row.server_revision),
      localChange: {
        operationId: row.local_operation_id,
        entityType: row.entity_type,
        entityId: row.entity_id,
        baseRevision: Number(row.local_base_revision),
        submittedAtEpochMillis: Number(row.submitted_at_epoch_ms),
        submittedByUserId: row.submitted_by_user_id || 'unknown',
        payloadJson: row.local_payload_json,
      },
      createdAtEpochMillis: Number(row.created_at_epoch_ms),
      requiresSuperAdminReview: true,
      resolution: null,
      terminalId: row.terminal_id,
    })),
  });
});

conflictsRouter.post('/:id/resolve', async (req, res) => {
  const schema = z.object({
    strategy: z.enum(['KEEP_SERVER', 'KEEP_TERMINAL_CHANGE', 'MERGE_MANUALLY']),
    mergedPayloadJson: z.string().nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid conflict resolution');

  const [rows] = await pool.execute(
    `SELECT * FROM sync_conflicts WHERE id = :id AND resolved_at_epoch_ms IS NULL LIMIT 1`,
    { id: req.params.id },
  );
  if (!rows[0]) return notFound(res, 'Open conflict not found');

  const conflictRow = rows[0];
  const now = nowMs();

  if (parsed.data.strategy === 'KEEP_TERMINAL_CHANGE' || parsed.data.strategy === 'MERGE_MANUALLY') {
    const terminal = await assertTerminal(conflictRow.terminal_id);
    if (terminal) {
      const payloadJson =
        parsed.data.strategy === 'MERGE_MANUALLY' && parsed.data.mergedPayloadJson
          ? parsed.data.mergedPayloadJson
          : conflictRow.local_payload_json;
      await applyOfflineChange(terminal, {
        entityType: conflictRow.entity_type,
        entityId: conflictRow.entity_id,
        payloadJson,
      });
    }
  }

  await pool.execute(
    `UPDATE sync_conflicts
     SET resolved_at_epoch_ms = :now,
         resolved_by_user_id = :actor,
         resolution_strategy = :strategy,
         merged_payload_json = :merged
     WHERE id = :id`,
    {
      id: req.params.id,
      now,
      actor: req.auth.sub,
      strategy: parsed.data.strategy,
      merged: parsed.data.mergedPayloadJson ?? null,
    },
  );

  await writeAudit({
    eventType: 'CONFLICT_RESOLVED',
    actorUserId: req.auth.sub,
    terminalId: conflictRow.terminal_id,
    entityType: conflictRow.entity_type,
    entityId: conflictRow.entity_id,
    detail: parsed.data.strategy,
  });

  res.json({
    ok: true,
    id: req.params.id,
    strategy: parsed.data.strategy,
    resolvedAtEpochMillis: now,
  });
});

export { bootstrapRouter as terminalSyncRouter, conflictsRouter as syncConflictsRouter };
