/**
 * Super-Admin hard flush — permanent wipe of selected scopes (not Recycle Bin).
 * Never deletes SUPER_ADMIN / GOD_ADMIN users. Audit events are retained.
 */
import { Router } from 'express';
import { z } from 'zod';
import crypto from 'crypto';
import pool from '../db.js';
import { badRequest, nowMs, writeAudit } from '../util.js';
import { requireSuperAdmin } from '../middleware/auth.js';

const router = Router();
router.use(requireSuperAdmin);

const SCOPES = ['TERMINALS', 'KEYS', 'USERS', 'SITES', 'ACCESS_GRANTS', 'ALL'];
const CONFIRM_TOKEN = 'I_UNDERSTAND_PERMANENT_FLUSH';

/** One-time tokens from preview → flush (in-memory; fine for single API process). */
const previewTokens = new Map();

function issuePreviewToken(scope) {
  const token = crypto.randomBytes(16).toString('hex');
  previewTokens.set(token, { scope, expiresAt: Date.now() + 5 * 60 * 1000 });
  return token;
}

function consumePreviewToken(token, scope) {
  const entry = previewTokens.get(token);
  previewTokens.delete(token);
  if (!entry) return false;
  if (entry.expiresAt < Date.now()) return false;
  return entry.scope === scope;
}

async function countActive(conn, sql, params = {}) {
  const [[row]] = await conn.execute(sql, params);
  return Number(row.c || 0);
}

async function safeCount(conn, sql) {
  try {
    return await countActive(conn, sql);
  } catch {
    return 0;
  }
}

async function previewCounts(conn, scope) {
  const counts = {
    terminals: 0,
    keySlots: 0,
    keys: 0,
    accessGrants: 0,
    users: 0,
    sites: 0,
    eventDefinitions: 0,
    schedules: 0,
    personnelGroups: 0,
    keyGroups: 0,
    multiAuthRules: 0,
  };

  if (scope === 'TERMINALS' || scope === 'ALL') {
    counts.terminals = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM terminals WHERE lifecycle_state = 'ACTIVE'`,
    );
    counts.keySlots = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM key_slots WHERE lifecycle_state = 'ACTIVE'`,
    );
  }
  if (scope === 'KEYS' || scope === 'ALL' || scope === 'TERMINALS') {
    if (scope === 'KEYS' || scope === 'ALL') {
      counts.keys = await safeCount(
        conn,
        `SELECT COUNT(*) AS c FROM managed_keys WHERE lifecycle_state = 'ACTIVE'`,
      );
    } else {
      counts.keys = await safeCount(
        conn,
        `SELECT COUNT(DISTINCT managed_key_id) AS c FROM key_slots
         WHERE lifecycle_state = 'ACTIVE' AND managed_key_id IS NOT NULL`,
      );
    }
  }
  if (scope === 'ACCESS_GRANTS' || scope === 'ALL' || scope === 'KEYS' || scope === 'TERMINALS') {
    counts.accessGrants = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM access_grants WHERE lifecycle_state = 'ACTIVE'`,
    );
  }
  if (scope === 'USERS' || scope === 'ALL') {
    counts.users = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM users
       WHERE lifecycle_state = 'ACTIVE' AND role NOT IN ('SUPER_ADMIN', 'GOD_ADMIN')`,
    );
  }
  if (scope === 'SITES' || scope === 'ALL') {
    counts.sites = await safeCount(conn, `SELECT COUNT(*) AS c FROM sites WHERE lifecycle_state = 'ACTIVE'`);
    counts.eventDefinitions = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM event_definitions WHERE lifecycle_state = 'ACTIVE'`,
    );
    counts.schedules = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM schedules WHERE lifecycle_state = 'ACTIVE'`,
    );
    counts.personnelGroups = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM personnel_groups WHERE lifecycle_state = 'ACTIVE'`,
    );
    counts.keyGroups = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM key_groups WHERE lifecycle_state = 'ACTIVE'`,
    );
    counts.multiAuthRules = await safeCount(
      conn,
      `SELECT COUNT(*) AS c FROM multi_authentication_rules WHERE lifecycle_state = 'ACTIVE'`,
    );
  }
  return counts;
}

async function hardDeleteAccessGrants(conn) {
  await conn.execute(`DELETE FROM access_grant_keys`);
  const [r] = await conn.execute(`DELETE FROM access_grants`);
  return r.affectedRows ?? 0;
}

async function hardDeleteKeys(conn) {
  await conn.execute(`UPDATE key_slots SET managed_key_id = NULL WHERE managed_key_id IS NOT NULL`);
  await conn.execute(`DELETE FROM key_access_request_keys`).catch(() => undefined);
  await conn.execute(`DELETE FROM appointment_keys`).catch(() => undefined);
  // key_checkout_notifications (migration 012) FK's to key_checkouts with no cascade — must clear
  // it before key_checkouts or the delete below throws ER_ROW_IS_REFERENCED_2. Once this real
  // dependent is cleared first, key_checkouts is left unguarded on purpose: if something else
  // still blocks it, that should surface as a real error, not silently no-op.
  await conn.execute(`DELETE FROM key_checkout_notifications`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_checkouts`);
  const [r] = await conn.execute(`DELETE FROM managed_keys`);
  return r.affectedRows ?? 0;
}

async function hardDeleteTerminals(conn) {
  // Keys bound only via slots: cascade to key wipe for those IDs first is caller's job for TERMINALS scope.
  const [keyRows] = await conn.execute(
    `SELECT DISTINCT managed_key_id AS id FROM key_slots WHERE managed_key_id IS NOT NULL`,
  );
  const keyIds = keyRows.map((r) => r.id).filter(Boolean);
  if (keyIds.length) {
    // Soft path not used — hard-delete grants that reference these keys, then the keys.
    const placeholders = keyIds.map((_, i) => `:k${i}`).join(', ');
    const params = Object.fromEntries(keyIds.map((id, i) => [`k${i}`, id]));
    const [grantIds] = await conn.execute(
      `SELECT DISTINCT grant_id AS id FROM access_grant_keys WHERE key_id IN (${placeholders})`,
      params,
    );
    for (const g of grantIds) {
      await conn.execute(`DELETE FROM access_grant_keys WHERE grant_id = :id`, { id: g.id });
      await conn.execute(`DELETE FROM access_grants WHERE id = :id`, { id: g.id });
    }
    await conn.execute(`UPDATE key_slots SET managed_key_id = NULL WHERE managed_key_id IS NOT NULL`);
    for (const id of keyIds) {
      await conn.execute(`DELETE FROM key_access_request_keys WHERE key_id = :id`, { id }).catch(() => undefined);
      await conn.execute(`DELETE FROM appointment_keys WHERE key_id = :id`, { id }).catch(() => undefined);
      // Same key_checkout_notifications FK gap as hardDeleteKeys() above — clear it before the
      // real key_checkouts delete, then leave key_checkouts unguarded.
      await conn.execute(
        `DELETE FROM key_checkout_notifications WHERE checkout_id IN
           (SELECT id FROM key_checkouts WHERE key_id = :id)`,
        { id },
      ).catch(() => undefined);
      await conn.execute(`DELETE FROM key_checkouts WHERE key_id = :id`, { id });
      await conn.execute(`DELETE FROM managed_keys WHERE id = :id`, { id });
    }
  }

  // Same key_checkout_notifications FK gap as above, for any remaining checkouts tied to this
  // terminal directly (terminal_id) rather than via one of the keyIds just handled.
  await conn.execute(`DELETE FROM key_checkout_notifications`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_checkouts`);
  await conn.execute(`DELETE FROM terminal_cabinet_settings`).catch(() => undefined);
  await conn.execute(`DELETE FROM terminal_refresh_tokens`).catch(() => undefined);
  await conn.execute(`DELETE FROM sync_conflicts`).catch(() => undefined);
  await conn.execute(`DELETE FROM terminal_sync_state`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_slots`);
  // Appointments that reference terminals
  await conn.execute(`DELETE FROM appointment_keys`).catch(() => undefined);
  await conn.execute(`DELETE FROM appointments`).catch(() => undefined);
  const [r] = await conn.execute(`DELETE FROM terminals`);
  return r.affectedRows ?? 0;
}

async function hardDeleteUsers(conn) {
  const [users] = await conn.execute(
    `SELECT id FROM users WHERE role NOT IN ('SUPER_ADMIN', 'GOD_ADMIN')`,
  );
  for (const u of users) {
    await conn.execute(`DELETE FROM user_site_assignments WHERE user_id = :id`, { id: u.id });
    await conn.execute(`DELETE FROM user_region_assignments WHERE user_id = :id`, { id: u.id }).catch(
      () => undefined,
    );
    await conn.execute(`DELETE FROM refresh_tokens WHERE user_id = :id`, { id: u.id });
    await conn.execute(`DELETE FROM credential_statuses WHERE user_id = :id`, { id: u.id }).catch(
      () => undefined,
    );
    await conn.execute(`DELETE FROM mobile_push_tokens WHERE user_id = :id`, { id: u.id }).catch(
      () => undefined,
    );
    await conn.execute(`DELETE FROM key_access_requests WHERE requester_user_id = :id`, { id: u.id }).catch(
      () => undefined,
    );
    // vendor_passkey_requests.vendor_user_id (fk_vendor_passkey_vendor) and
    // key_access_requests.pic_user_id (fk_key_access_requests_pic) both FK to users but weren't
    // cleaned above — requester_user_id only covers a user's own requests, not requests where
    // this user is someone else's PIC. Unlike requester_user_id's request row (deleted outright,
    // it's that user's own), a pic_user_id reference is nulled rather than deleting the whole
    // request — the request itself still belongs to a different (possibly surviving) requester.
    await conn.execute(`DELETE FROM vendor_passkey_requests WHERE vendor_user_id = :id`, { id: u.id }).catch(
      () => undefined,
    );
    await conn.execute(`UPDATE key_access_requests SET pic_user_id = NULL WHERE pic_user_id = :id`, {
      id: u.id,
    }).catch(() => undefined);
    await conn.execute(`DELETE FROM users WHERE id = :id`, { id: u.id });
  }
  return users.length;
}

async function hardDeleteSites(conn) {
  // Phase4 + office hours + regions links that block sites
  await conn.execute(`DELETE FROM multi_authentication_rules`).catch(() => undefined);
  await conn.execute(`DELETE FROM personnel_groups`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_groups`).catch(() => undefined);
  await conn.execute(`DELETE FROM event_definitions`).catch(() => undefined);
  await conn.execute(`DELETE FROM schedules`).catch(() => undefined);
  await conn.execute(`DELETE FROM appointment_reasons`).catch(() => undefined);
  await conn.execute(`DELETE FROM site_office_hours`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_access_request_documents`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_access_request_keys`).catch(() => undefined);
  await conn.execute(`DELETE FROM key_access_requests`).catch(() => undefined);
  await conn.execute(`DELETE FROM vendor_passkey_requests`).catch(() => undefined);
  // user_site_assignments / user_region_assignments for preserved SUPER_ADMIN/GOD_ADMIN users
  // (never wiped by hardDeleteUsers, which only touches non-SA/GOD_ADMIN accounts) still FK to
  // sites/regions and block the DELETE FROM sites below. Unscoped, same as every other table in
  // this function — every site is going away in this scope, so every assignment row is moot
  // regardless of which user it belongs to (this also covers the SITES-only scope, where
  // hardDeleteUsers never runs at all, not just the ALL scope's leftover SA/GOD_ADMIN rows).
  await conn.execute(`DELETE FROM user_site_assignments`).catch(() => undefined);
  await conn.execute(`DELETE FROM user_region_assignments`).catch(() => undefined);
  await conn.execute(`UPDATE sites SET parent_site_id = NULL`);
  await conn.execute(`UPDATE sites SET region_id = NULL`).catch(() => undefined);
  const [r] = await conn.execute(`DELETE FROM sites`);
  return r.affectedRows ?? 0;
}

async function runFlush(conn, scope) {
  const result = {
    accessGrants: 0,
    keys: 0,
    terminals: 0,
    users: 0,
    sites: 0,
  };

  if (scope === 'ACCESS_GRANTS') {
    result.accessGrants = await hardDeleteAccessGrants(conn);
  } else if (scope === 'KEYS') {
    result.accessGrants = await hardDeleteAccessGrants(conn);
    result.keys = await hardDeleteKeys(conn);
  } else if (scope === 'TERMINALS') {
    result.terminals = await hardDeleteTerminals(conn);
  } else if (scope === 'USERS') {
    result.users = await hardDeleteUsers(conn);
  } else if (scope === 'SITES') {
    // Sites only when empty of terminals/keys — wipe dependents first within sites scope
    result.accessGrants = await hardDeleteAccessGrants(conn);
    result.keys = await hardDeleteKeys(conn);
    result.terminals = await hardDeleteTerminals(conn);
    result.sites = await hardDeleteSites(conn);
  } else if (scope === 'ALL') {
    result.accessGrants = await hardDeleteAccessGrants(conn);
    result.keys = await hardDeleteKeys(conn);
    result.terminals = await hardDeleteTerminals(conn);
    result.users = await hardDeleteUsers(conn);
    result.sites = await hardDeleteSites(conn);
  }

  return result;
}

router.get('/preview', async (req, res) => {
  const scope = String(req.query.scope || '');
  if (!SCOPES.includes(scope)) {
    return badRequest(res, `scope must be one of ${SCOPES.join(', ')}`);
  }
  const counts = await previewCounts(pool, scope);
  const previewToken = issuePreviewToken(scope);
  res.json({
    scope,
    counts,
    previewToken,
    confirmTokenRequired: CONFIRM_TOKEN,
    note: 'Super Admin / God Admin accounts are never deleted. Audit history is retained.',
  });
});

router.post('/', async (req, res) => {
  const schema = z.object({
    scope: z.enum(SCOPES),
    confirmToken: z.literal(CONFIRM_TOKEN),
    previewToken: z.string().min(16),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    return badRequest(
      res,
      'Invalid flush payload — require scope, confirmToken I_UNDERSTAND_PERMANENT_FLUSH, and previewToken from GET /preview',
    );
  }
  if (!consumePreviewToken(parsed.data.previewToken, parsed.data.scope)) {
    return res.status(409).json({
      error: 'PREVIEW_TOKEN_INVALID',
      message: 'Preview token missing, expired, or scope mismatch. Call GET /flush/preview again.',
    });
  }

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const deleted = await runFlush(conn, parsed.data.scope);
    await writeAudit({
      eventType: 'ADMIN_DATA_FLUSH',
      actorUserId: req.auth.sub,
      entityType: 'FLUSH',
      entityId: parsed.data.scope,
      detail: JSON.stringify({ scope: parsed.data.scope, deleted }),
      conn,
    });
    await conn.commit();
    res.json({
      ok: true,
      scope: parsed.data.scope,
      deleted,
      serverTimeEpochMillis: nowMs(),
    });
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
});

export default router;
