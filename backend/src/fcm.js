/**
 * FCM push helper. Uses firebase-admin's HTTP v1 API (a service-account JSON, not the
 * deprecated legacy FCM_SERVER_KEY — a fresh Firebase project may not have legacy access at
 * all). When FCM_SERVICE_ACCOUNT_PATH is unset or the file can't be read/parsed, logs and
 * no-ops (dev-safe) — same graceful-skip contract the legacy implementation had, just a
 * different missing-credential reason string.
 *
 * firebase-admin v14+: use modular `firebase-admin/app` + `firebase-admin/messaging`
 * (`admin.credential.cert` no longer exists on the default export).
 */
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';
import fs from 'fs';
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

// Lazy, cached, and deliberately never throws — this module is imported at server boot (and
// called from an in-process interval, see deadlineMonitor.js) well before the user has
// necessarily finished Firebase signup, so initialization failure must degrade to a no-op, not
// crash the process or the tick that called it.
let firebaseApp;
let firebaseInitAttempted = false;

function getFirebaseApp() {
  if (firebaseInitAttempted) return firebaseApp;
  firebaseInitAttempted = true;
  const credentialPath = process.env.FCM_SERVICE_ACCOUNT_PATH;
  if (!credentialPath) {
    firebaseApp = null;
    return null;
  }
  try {
    const serviceAccount = JSON.parse(fs.readFileSync(credentialPath, 'utf8'));
    const existing = getApps();
    firebaseApp = existing.length
      ? existing[0]
      : initializeApp({ credential: cert(serviceAccount) });
  } catch (err) {
    console.warn(`[fcm] failed to init firebase-admin from FCM_SERVICE_ACCOUNT_PATH: ${err.message}`);
    firebaseApp = null;
  }
  return firebaseApp;
}

export async function sendPushToUser(userId, title, body, data = {}) {
  const [rows] = await pool.execute(
    `SELECT fcm_token FROM mobile_push_tokens WHERE user_id = :userId`,
    { userId },
  );
  if (!rows.length) return { sent: 0, skipped: 'no_tokens' };

  const app = getFirebaseApp();
  if (!app) {
    console.log(`[fcm] skip (no FCM_SERVICE_ACCOUNT_PATH configured): user=${userId} title=${title}`);
    return { sent: 0, skipped: 'no_service_account' };
  }

  const messaging = getMessaging(app);
  let sent = 0;
  for (const row of rows) {
    try {
      await messaging.send({
        token: row.fcm_token,
        notification: { title, body },
        data: Object.fromEntries(
          Object.entries(data).map(([k, v]) => [k, String(v)]),
        ),
      });
      sent += 1;
    } catch (err) {
      console.warn(`[fcm] send failed for user ${userId}: ${err.message}`);
    }
  }
  return { sent };
}
