import { v4 as uuidv4 } from 'uuid';
import pool from './db.js';

export function nowMs() {
  return Date.now();
}

export function newId() {
  return uuidv4();
}

export function lifecycleFromRow(row) {
  return {
    state: row.lifecycle_state,
    createdAtEpochMillis: Number(row.created_at_epoch_ms),
    updatedAtEpochMillis: Number(row.updated_at_epoch_ms),
    deletedAtEpochMillis: row.deleted_at_epoch_ms == null ? null : Number(row.deleted_at_epoch_ms),
    deletedByUserId: row.deleted_by_user_id || null,
  };
}

export async function writeAudit({
  eventType,
  actorUserId = null,
  terminalId = null,
  siteId = null,
  entityType = null,
  entityId = null,
  detail = null,
  conn = pool,
}) {
  await conn.execute(
    `INSERT INTO audit_events
      (id, event_type, actor_user_id, terminal_id, site_id, entity_type, entity_id, occurred_at_epoch_ms, detail)
     VALUES (:id, :eventType, :actorUserId, :terminalId, :siteId, :entityType, :entityId, :now, :detail)`,
    {
      id: newId(),
      eventType,
      actorUserId,
      terminalId,
      siteId,
      entityType,
      entityId,
      now: nowMs(),
      detail,
    },
  );
}

export function conflict(res, message = 'expectedRevision does not match current revision') {
  return res.status(409).json({ error: 'CONFLICT', message });
}

export function badRequest(res, message) {
  return res.status(400).json({ error: 'BAD_REQUEST', message });
}

export function notFound(res, message = 'Not found') {
  return res.status(404).json({ error: 'NOT_FOUND', message });
}
