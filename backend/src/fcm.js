/**
 * FCM push helper. When FCM_SERVER_KEY is unset, logs and no-ops (dev-safe).
 * Uses legacy HTTP FCM API for simplicity on the VPS.
 */
import pool from './db.js';
import { newId, nowMs } from './util.js';

export async function registerPushToken(userId, fcmToken, platform = 'ANDROID') {
  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO mobile_push_tokens (id, user_id, fcm_token, platform, updated_at_epoch_ms)
     VALUES (:id, :userId, :fcmToken, :platform, :now)
     ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), platform = VALUES(platform),
       updated_at_epoch_ms = VALUES(updated_at_epoch_ms)`,
    { id, userId, fcmToken, platform, now },
  );
}

export async function sendPushToUser(userId, title, body, data = {}) {
  const serverKey = process.env.FCM_SERVER_KEY;
  const [rows] = await pool.execute(
    `SELECT fcm_token FROM mobile_push_tokens WHERE user_id = :userId`,
    { userId },
  );
  if (!rows.length) return { sent: 0, skipped: 'no_tokens' };
  if (!serverKey) {
    console.log(`[fcm] skip (no FCM_SERVER_KEY): user=${userId} title=${title}`);
    return { sent: 0, skipped: 'no_server_key' };
  }

  let sent = 0;
  for (const row of rows) {
    try {
      const res = await fetch('https://fcm.googleapis.com/fcm/send', {
        method: 'POST',
        headers: {
          Authorization: `key=${serverKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          to: row.fcm_token,
          notification: { title, body },
          data: Object.fromEntries(
            Object.entries(data).map(([k, v]) => [k, String(v)]),
          ),
        }),
      });
      if (res.ok) sent += 1;
      else console.warn(`[fcm] HTTP ${res.status} for user ${userId}`);
    } catch (err) {
      console.warn('[fcm] send failed', err.message);
    }
  }
  return { sent };
}
