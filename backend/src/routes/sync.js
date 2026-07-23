import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { badRequest, newId, notFound, nowMs, writeAudit } from '../util.js';

const bootstrapRouter = Router();
const conflictsRouter = Router();

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

  res.json({
    serverRevision: Number(state.server_revision),
    issuedAtEpochMillis: now,
    changesJson: [],
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
      accepted.push(change.operationId);
    }
  }

  await pool.execute(
    `UPDATE terminal_sync_state
     SET server_revision = server_revision + 1,
         last_push_at_epoch_ms = :now
     WHERE terminal_id = :terminalId`,
    { now, terminalId: parsed.data.terminalId },
  );

  await writeAudit({
    eventType: conflicts.length > 0 ? 'CONFLICT_CREATED' : 'TERMINAL_UPDATED',
    actorUserId: req.auth?.sub || null,
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
  res.json({
    ok: true,
    terminalId: parsed.data.terminalId,
    serverRevision: Number(state.server_revision),
    issuedAtEpochMillis: now,
    message: 'Download staged. Terminal confirms receipt locally.',
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

  const now = nowMs();
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
    terminalId: rows[0].terminal_id,
    entityType: rows[0].entity_type,
    entityId: rows[0].entity_id,
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
