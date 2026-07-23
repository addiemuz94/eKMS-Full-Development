import { Router } from 'express';
import pool from '../db.js';

const router = Router();

router.get('/', async (req, res) => {
  const siteId = req.query.siteId;
  const actorUserId = req.query.actorUserId;
  const fromMs = req.query.fromEpochMillis ? Number(req.query.fromEpochMillis) : null;
  const toMs = req.query.toEpochMillis ? Number(req.query.toEpochMillis) : null;
  const limit = Math.min(Number(req.query.limit || 100), 500);

  let sql = `SELECT * FROM audit_events WHERE 1=1`;
  const params = {};
  if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }
  if (actorUserId) {
    sql += ` AND actor_user_id = :actorUserId`;
    params.actorUserId = actorUserId;
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
  res.json({
    items: rows.map((row) => ({
      id: row.id,
      eventType: row.event_type,
      actorUserId: row.actor_user_id,
      terminalId: row.terminal_id,
      siteId: row.site_id,
      entityType: row.entity_type,
      entityId: row.entity_id,
      occurredAtEpochMillis: Number(row.occurred_at_epoch_ms),
      detail: row.detail,
    })),
  });
});

export default router;
