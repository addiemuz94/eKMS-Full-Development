/**
 * Explicit cascade soft-delete for a cabinet (terminal) and its contents.
 * Never silent — callers must opt in (e.g. DELETE body cascade: true).
 */
import { nowMs, writeAudit } from './util.js';

async function softDeleteRow(conn, table, id, actorUserId, now) {
  await conn.execute(
    `UPDATE ${table}
     SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
         revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
    { id, now, actor: actorUserId },
  );
}

/**
 * Soft-delete terminal + ACTIVE slots + keys bound to those slots + grants referencing those keys.
 * Runs on the given connection (caller owns transaction).
 * @returns {{ slotCount: number, keyCount: number, grantCount: number }}
 */
export async function cascadeSoftDeleteTerminal(conn, { terminalId, siteId, actorUserId }) {
  const now = nowMs();
  const [slots] = await conn.execute(
    `SELECT id, managed_key_id FROM key_slots
     WHERE terminal_id = :id AND lifecycle_state = 'ACTIVE'`,
    { id: terminalId },
  );

  const keyIds = [
    ...new Set(slots.map((s) => s.managed_key_id).filter((id) => typeof id === 'string' && id.length > 0)),
  ];

  let grantCount = 0;
  if (keyIds.length > 0) {
    const placeholders = keyIds.map((_, i) => `:k${i}`).join(', ');
    const params = Object.fromEntries(keyIds.map((id, i) => [`k${i}`, id]));

    const [grantRows] = await conn.execute(
      `SELECT DISTINCT ag.id
       FROM access_grants ag
       INNER JOIN access_grant_keys agk ON agk.grant_id = ag.id
       WHERE ag.lifecycle_state = 'ACTIVE' AND agk.key_id IN (${placeholders})`,
      params,
    );

    for (const grant of grantRows) {
      await softDeleteRow(conn, 'access_grants', grant.id, actorUserId, now);
      await writeAudit({
        eventType: 'RECORD_MOVED_TO_BIN',
        actorUserId,
        siteId,
        terminalId,
        entityType: 'ACCESS_GRANT',
        entityId: grant.id,
        detail: 'CASCADE_CABINET_DELETE',
        conn,
      });
      grantCount += 1;
    }

    for (const keyId of keyIds) {
      // Unlink any remaining ACTIVE slots pointing at this key (other cabinets) before soft-delete.
      await conn.execute(
        `UPDATE key_slots
         SET managed_key_id = NULL, revision = revision + 1, updated_at_epoch_ms = :now
         WHERE managed_key_id = :keyId AND lifecycle_state = 'ACTIVE' AND terminal_id <> :terminalId`,
        { keyId, terminalId, now },
      );
      await softDeleteRow(conn, 'managed_keys', keyId, actorUserId, now);
      await writeAudit({
        eventType: 'RECORD_MOVED_TO_BIN',
        actorUserId,
        siteId,
        terminalId,
        entityType: 'KEY',
        entityId: keyId,
        detail: 'CASCADE_CABINET_DELETE',
        conn,
      });
    }
  }

  for (const slot of slots) {
    await softDeleteRow(conn, 'key_slots', slot.id, actorUserId, now);
    await writeAudit({
      eventType: 'RECORD_MOVED_TO_BIN',
      actorUserId,
      siteId,
      terminalId,
      entityType: 'KEY_SLOT',
      entityId: slot.id,
      detail: 'CASCADE_CABINET_DELETE',
      conn,
    });
  }

  await softDeleteRow(conn, 'terminals', terminalId, actorUserId, now);
  await writeAudit({
    eventType: 'RECORD_MOVED_TO_BIN',
    actorUserId,
    siteId,
    terminalId,
    entityType: 'TERMINAL',
    entityId: terminalId,
    detail: `CASCADE_CABINET_DELETE slots=${slots.length} keys=${keyIds.length} grants=${grantCount}`,
    conn,
  });

  return { slotCount: slots.length, keyCount: keyIds.length, grantCount };
}
