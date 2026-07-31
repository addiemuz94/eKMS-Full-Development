import crypto from 'crypto';
import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { signKeyAccessSessionToken } from '../middleware/auth.js';
import {
  assignedRegionIdsForUser,
  assignedSiteIdsForUser,
  badRequest,
  conflict,
  isRegionAssignedToUser,
  isSiteAssignedToUser,
  newId,
  notFound,
  nowMs,
  regionIdForSite,
  writeAudit,
} from '../util.js';

const router = Router();

/**
 * An ADDITIVE, more general request mechanism alongside routes/vendorPasskeyRequests.js (Phase
 * 1) — NOT a replacement, see the corrected note at the top of
 * 009_regions_and_key_access_requests.sql. vendorPasskeyRequests.js stays exactly as-is, still
 * backing terminalApp's deployed Phase 7 Vendor Passkey screen. This file adds Technician
 * support (not just Vendor), multi-key requests, and Region-routed (not Site-routed) approval
 * for mobileApp's future request form — see the design-choice comments on that same migration
 * file for the full reasoning.
 */

// Same two-layer model as every other Regional-Admin-scoped route (sites.js/accessGrants.js/
// vendorPasskeyRequests.js) — this is the row-level half; REGIONAL_ADMIN_ALLOWED_ROUTES in
// middleware/auth.js is the route-level half. Routes via the request's Site's REGION, not the
// Site itself — a Regional Admin need not be individually Site-assigned to approve this.
async function assertMayAccessRequestSite(req, siteId) {
  if (req.auth?.role === 'SUPER_ADMIN') return true;
  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const regionId = await regionIdForSite(siteId);
    return isRegionAssignedToUser(req.auth.sub, regionId);
  }
  return false;
}

/** Read access is broader than approve/reject access: a requester (TECHNICIAN/VENDOR) may
 * always read their OWN request, on top of the SUPER_ADMIN/REGIONAL_ADMIN site/region check
 * above. Never used for approve/reject — those stay gated by [assertMayAccessRequestSite]
 * alone, since a requester approving their own request would defeat the whole point. */
async function assertMayReadRequest(req, row) {
  if (row.requester_user_id === req.auth?.sub) return true;
  return assertMayAccessRequestSite(req, row.site_id);
}

/**
 * Site-access check for CREATING a request — Only B (exception access): a self-service
 * Technician/Vendor may ONLY target a site they are NOT already assigned to. Standing home
 * sites stay on terminal Key Menu / access grants — this form is for exception locations.
 * Super Admin / Regional Admin creating on someone's behalf still use Region/admin visibility.
 */
async function assertMayCreateForSite(req, siteId) {
  if (req.auth?.role === 'TECHNICIAN' || req.auth?.role === 'VENDOR') {
    const assigned = await isSiteAssignedToUser(req.auth.sub, siteId);
    return !assigned;
  }
  return assertMayAccessRequestSite(req, siteId);
}

function mapSiteBrief(row) {
  return {
    id: row.id,
    name: row.name,
    province: row.province ?? null,
    city: row.city ?? null,
    parentSiteId: row.parent_site_id ?? null,
    address: row.address,
    regionId: row.region_id ?? null,
    revision: Number(row.revision),
  };
}

function mapKeyBrief(row) {
  return {
    id: row.id,
    siteId: row.site_id,
    displayName: row.display_name,
    fobEnrollmentReference: row.fob_enrollment_reference,
    revision: Number(row.revision),
  };
}

/**
 * GET /key-access-requests/exception-sites — ACTIVE sites outside the caller's standing
 * user_site_assignments (Only B picker). TECHNICIAN/VENDOR only for self-service.
 */
router.get('/exception-sites', async (req, res) => {
  if (req.auth?.role !== 'TECHNICIAN' && req.auth?.role !== 'VENDOR') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Exception site list is for Technician/Vendor self-service' });
  }
  const assigned = await assignedSiteIdsForUser(req.auth.sub);
  let sql = `SELECT * FROM sites WHERE lifecycle_state = 'ACTIVE'`;
  const params = {};
  if (assigned.length > 0) {
    const placeholders = assigned.map((_, i) => `:site${i}`).join(', ');
    assigned.forEach((id, i) => {
      params[`site${i}`] = id;
    });
    sql += ` AND id NOT IN (${placeholders})`;
  }
  sql += ` ORDER BY name ASC`;
  const [rows] = await pool.execute(sql, params);
  return res.json({ items: rows.map(mapSiteBrief) });
});

/**
 * GET /key-access-requests/exception-sites/:siteId/keys — keys at an exception-eligible site.
 */
router.get('/exception-sites/:siteId/keys', async (req, res) => {
  const { siteId } = req.params;
  if (!(await assertMayCreateForSite(req, siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not an exception-eligible site for this caller' });
  }
  const [sites] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: siteId },
  );
  if (!sites[0]) return notFound(res, 'Site not found');
  const [rows] = await pool.execute(
    `SELECT * FROM managed_keys WHERE site_id = :siteId AND lifecycle_state = 'ACTIVE' ORDER BY display_name ASC`,
    { siteId },
  );
  return res.json({ items: rows.map(mapKeyBrief) });
});

/**
 * GET /key-access-requests/site-policy/:siteId — legacy duration ceiling read. Only B no longer
 * clamps to this value; kept for older clients. Caller must still be allowed to create for the site.
 */
router.get('/site-policy/:siteId', async (req, res) => {
  const { siteId } = req.params;
  if (!(await assertMayCreateForSite(req, siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted to view this site' });
  }

  const [sites] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: siteId },
  );
  if (!sites[0]) return notFound(res, 'Site not found');

  const regionId = await regionIdForSite(siteId);
  let maxKeyAccessDurationMinutes = null;
  if (regionId) {
    const [[region]] = await pool.execute(
      `SELECT max_key_access_duration_minutes FROM regions WHERE id = :id LIMIT 1`,
      { id: regionId },
    );
    if (region) maxKeyAccessDurationMinutes = Number(region.max_key_access_duration_minutes);
  }

  return res.json({ siteId, regionId, maxKeyAccessDurationMinutes });
});

async function requestKeyIds(requestId) {
  const [rows] = await pool.execute(
    `SELECT key_id FROM key_access_request_keys WHERE request_id = :requestId`,
    { requestId },
  );
  return rows.map((r) => r.key_id);
}

/**
 * `viewerUserId` controls `generatedPasskey` visibility on GET responses — corrected from an
 * earlier draft that omitted it from every GET response the same way the old
 * vendor-passkey-requests table does. That "shown once, on the approve response only" model
 * only ever worked there because the approver and the vendor were physically at the same
 * terminal (the admin read the code off their own screen and told the vendor out loud); the
 * mobile Key Access Request form has NO such shared-screen moment — the requester is on their
 * own separate phone/session and would otherwise never receive the code through any API call at
 * all. Fixed: a GET response includes the real `generatedPasskey` only when the caller IS the
 * request's own requester (`viewerUserId === row.requester_user_id`) — everyone else (Regional
 * Admin, Super Admin, a different requester) still never sees it via GET, preserving
 * confidentiality from anyone but its intended recipient. The approve-action response
 * (`ApproveKeyAccessRequestResponse`) is unrelated and still returns it directly to whoever
 * calls approve, unchanged.
 */
function mapRequest(row, keyIds, viewerUserId, siteName = null, cabinetNames = []) {
  return {
    id: row.id,
    requesterUserId: row.requester_user_id,
    requesterRole: row.requester_role,
    siteId: row.site_id,
    siteName,
    cabinetNames,
    keyIds,
    requestedAtEpochMillis: Number(row.requested_at_epoch_ms),
    requestedDurationMinutes: Number(row.requested_duration_minutes),
    reason: row.reason ?? null,
    pickupAtEpochMillis: row.pickup_at_epoch_ms == null ? null : Number(row.pickup_at_epoch_ms),
    returnAtEpochMillis: row.return_at_epoch_ms == null ? null : Number(row.return_at_epoch_ms),
    status: row.status,
    approvedByUserId: row.approved_by_user_id,
    approvedAtEpochMillis: row.approved_at_epoch_ms == null ? null : Number(row.approved_at_epoch_ms),
    generatedPasskey: viewerUserId != null && viewerUserId === row.requester_user_id
      ? row.generated_passkey ?? null
      : null,
    passkeyExpiresAtEpochMillis:
      row.passkey_expires_at_epoch_ms == null ? null : Number(row.passkey_expires_at_epoch_ms),
  };
}

/** Site + cabinet names for mobile/portal display (technician PIN screen needs cabinet). */
async function mapRequestEnriched(row, keyIds, viewerUserId) {
  const [sites] = await pool.execute(
    `SELECT name FROM sites WHERE id = :id LIMIT 1`,
    { id: row.site_id },
  );
  const [terminals] = await pool.execute(
    `SELECT name FROM terminals WHERE site_id = :siteId AND lifecycle_state = 'ACTIVE' ORDER BY name ASC`,
    { siteId: row.site_id },
  );
  return mapRequest(
    row,
    keyIds,
    viewerUserId,
    sites[0]?.name ?? null,
    terminals.map((t) => t.name),
  );
}

router.get('/', async (req, res) => {
  const { siteId } = req.query;
  const status = req.query.status || 'PENDING';

  // Technician/Vendor: always self-scoped, regardless of any siteId filter — they may only ever
  // see requests they themselves filed, never another requester's or a whole site's queue.
  if (req.auth?.role === 'TECHNICIAN' || req.auth?.role === 'VENDOR') {
    let sql = `SELECT * FROM key_access_requests WHERE requester_user_id = :requesterUserId`;
    const params = { requesterUserId: req.auth.sub };
    if (status !== 'ALL') {
      sql += ` AND status = :status`;
      params.status = status;
    }
    sql += ` ORDER BY requested_at_epoch_ms DESC`;
    const [rows] = await pool.execute(sql, params);
    const items = [];
    for (const row of rows) {
      items.push(await mapRequestEnriched(row, await requestKeyIds(row.id), req.auth?.sub));
    }
    return res.json({ items });
  }

  if (siteId && !(await assertMayAccessRequestSite(req, siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted to view requests for this site' });
  }

  let sql = `SELECT * FROM key_access_requests`;
  const params = {};
  const conditions = [];
  if (siteId) {
    conditions.push(`site_id = :siteId`);
    params.siteId = siteId;
  } else if (req.auth?.role === 'REGIONAL_ADMIN') {
    // No specific siteId requested — scope the list down to every site inside the Regional
    // Admin's own assigned regions, rather than 403ing (same semantics as the old
    // vendorPasskeyRequests.js list route, generalized from Site to Region).
    const regionIds = await assignedRegionIdsForUser(req.auth.sub);
    if (regionIds.length === 0) return res.json({ items: [] });
    const placeholders = regionIds.map((_, i) => `:region${i}`).join(', ');
    regionIds.forEach((id, i) => {
      params[`region${i}`] = id;
    });
    conditions.push(
      `site_id IN (SELECT id FROM sites WHERE region_id IN (${placeholders}))`,
    );
  }
  if (status !== 'ALL') {
    conditions.push(`status = :status`);
    params.status = status;
  }
  if (conditions.length > 0) sql += ` WHERE ${conditions.join(' AND ')}`;
  sql += ` ORDER BY requested_at_epoch_ms DESC`;

  const [rows] = await pool.execute(sql, params);
  const items = [];
  for (const row of rows) {
    items.push(await mapRequestEnriched(row, await requestKeyIds(row.id), req.auth?.sub));
  }
  res.json({ items });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key access request not found');
  // Out-of-scope reads as "not found", not "forbidden" — avoids confirming a request's
  // existence to a caller who isn't its requester and whose regions don't cover its site.
  if (!(await assertMayReadRequest(req, rows[0]))) {
    return notFound(res, 'Key access request not found');
  }
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(rows[0].id), req.auth?.sub));
});

const createSchema = z.object({
  requesterUserId: z.string().uuid().optional(),
  requesterRole: z.enum(['TECHNICIAN', 'VENDOR']).optional(),
  siteId: z.string().uuid(),
  keyIds: z.array(z.string().uuid()).min(1),
  reason: z.string().trim().min(1).max(2000),
  pickupAtEpochMillis: z.number().int().positive(),
  returnAtEpochMillis: z.number().int().positive(),
});

router.post('/', async (req, res) => {
  const parsed = createSchema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid key access request payload');

  if (parsed.data.returnAtEpochMillis <= parsed.data.pickupAtEpochMillis) {
    return badRequest(res, 'returnAtEpochMillis must be after pickupAtEpochMillis');
  }

  let requesterUserId;
  let requesterRole;
  if (req.auth?.role === 'TECHNICIAN' || req.auth?.role === 'VENDOR') {
    requesterUserId = req.auth.sub;
    requesterRole = req.auth.role;
  } else if (req.auth?.role === 'SUPER_ADMIN' || req.auth?.role === 'REGIONAL_ADMIN') {
    if (!parsed.data.requesterUserId || !parsed.data.requesterRole) {
      return badRequest(res, 'requesterUserId and requesterRole are required for admin-created requests');
    }
    requesterUserId = parsed.data.requesterUserId;
    requesterRole = parsed.data.requesterRole;
  } else {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted to create a key access request' });
  }

  if (!(await assertMayCreateForSite(req, parsed.data.siteId))) {
    return res.status(403).json({
      error: 'FORBIDDEN',
      message: 'Only B: request a site outside your standing assignments (or not permitted for this site)',
    });
  }

  const [requester] = await pool.execute(
    `SELECT id FROM users WHERE id = :id AND role = :role AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: requesterUserId, role: requesterRole },
  );
  if (!requester[0]) return badRequest(res, 'requesterUserId must reference an active user with the given requesterRole');

  const [site] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.siteId },
  );
  if (!site[0]) return badRequest(res, 'siteId must reference an active site');

  const placeholders = parsed.data.keyIds.map((_, i) => `:key${i}`).join(', ');
  const keyParams = {};
  parsed.data.keyIds.forEach((id, i) => {
    keyParams[`key${i}`] = id;
  });
  const [keys] = await pool.execute(
    `SELECT id FROM managed_keys WHERE id IN (${placeholders}) AND site_id = :siteId AND lifecycle_state = 'ACTIVE'`,
    { ...keyParams, siteId: parsed.data.siteId },
  );
  if (keys.length !== parsed.data.keyIds.length) {
    return badRequest(res, 'One or more keyIds are not active keys at the given site');
  }

  const derivedDurationMinutes = Math.max(
    1,
    Math.ceil((parsed.data.returnAtEpochMillis - parsed.data.pickupAtEpochMillis) / 60_000),
  );

  const id = newId();
  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    await conn.execute(
      `INSERT INTO key_access_requests
        (id, requester_user_id, requester_role, site_id, reason, requested_at_epoch_ms,
         requested_duration_minutes, pickup_at_epoch_ms, return_at_epoch_ms, status)
       VALUES (:id, :requesterUserId, :requesterRole, :siteId, :reason, :now,
         :requestedDurationMinutes, :pickupAt, :returnAt, 'PENDING')`,
      {
        id,
        requesterUserId,
        requesterRole,
        siteId: parsed.data.siteId,
        reason: parsed.data.reason,
        now,
        requestedDurationMinutes: derivedDurationMinutes,
        pickupAt: parsed.data.pickupAtEpochMillis,
        returnAt: parsed.data.returnAtEpochMillis,
      },
    );
    for (const keyId of parsed.data.keyIds) {
      await conn.execute(
        `INSERT INTO key_access_request_keys (request_id, key_id) VALUES (:id, :keyId)`,
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
    eventType: 'KEY_ACCESS_REQUEST_REQUESTED',
    actorUserId: req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: id,
  });

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, { id });
  return res.status(201).json(await mapRequestEnriched(rows[0], parsed.data.keyIds, req.auth?.sub));
});

router.post('/:id/approve', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayAccessRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Key access request not found');
  }
  if (existing[0].status !== 'PENDING') {
    return conflict(res, 'Request is no longer pending');
  }

  // Only B: PIN valid until return datetime. No region duration clamp.
  // Legacy rows without return_at fall back to requested_duration_minutes from now.
  const now = nowMs();
  let expiresAt;
  if (existing[0].return_at_epoch_ms != null) {
    expiresAt = Number(existing[0].return_at_epoch_ms);
    if (expiresAt <= now) {
      return badRequest(res, 'Request return window has already ended — cannot approve');
    }
  } else {
    expiresAt = now + Number(existing[0].requested_duration_minutes) * 60_000;
  }

  const code = String(crypto.randomInt(0, 10_000)).padStart(4, '0');
  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;

  const [result] = await pool.execute(
    `UPDATE key_access_requests SET
       status = 'APPROVED',
       approved_by_user_id = :actorUserId,
       approved_at_epoch_ms = :now,
       generated_passkey = :code,
       passkey_expires_at_epoch_ms = :expiresAt
     WHERE id = :id AND status = 'PENDING'`,
    { id: req.params.id, actorUserId, now, code, expiresAt },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer pending');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_APPROVED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  return res.json({
    id: req.params.id,
    status: 'APPROVED',
    generatedPasskey: code,
    passkeyExpiresAtEpochMillis: expiresAt,
  });
});

router.post('/:id/reject', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayAccessRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Key access request not found');
  }
  if (existing[0].status !== 'PENDING') {
    return conflict(res, 'Request is no longer pending');
  }

  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
  const [result] = await pool.execute(
    `UPDATE key_access_requests SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'`,
    { id: req.params.id },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer pending');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_REJECTED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(req.params.id), req.auth?.sub));
});

export default router;

// --- Terminal-side passkey login (unauthenticated route, mounted directly in index.js) --------

const PASSKEY_LOGIN_RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const PASSKEY_LOGIN_RATE_LIMIT_MAX_FAILURES = 10;

async function recordLoginAttempt(ipAddress, succeeded) {
  await pool.execute(
    `INSERT INTO key_access_login_attempts (id, ip_address, succeeded, attempted_at_epoch_ms)
     VALUES (:id, :ipAddress, :succeeded, :now)`,
    { id: newId(), ipAddress, succeeded: succeeded ? 1 : 0, now: nowMs() },
  );
}

async function isLoginRateLimited(ipAddress) {
  const [rows] = await pool.execute(
    `SELECT COUNT(*) AS c FROM key_access_login_attempts
     WHERE ip_address = :ipAddress AND succeeded = 0 AND attempted_at_epoch_ms > :windowStart`,
    { ipAddress, windowStart: nowMs() - PASSKEY_LOGIN_RATE_LIMIT_WINDOW_MS },
  );
  return Number(rows[0].c) >= PASSKEY_LOGIN_RATE_LIMIT_MAX_FAILURES;
}

const passkeyLoginSchema = z.object({
  passkey: z.string().regex(/^\d{4}$/, 'passkey must be exactly 4 digits'),
  terminalId: z.string().uuid(),
});

/**
 * POST /v1/terminal/passkey-login — unauthenticated by necessity, same reasoning as
 * pair-with-code: the whole point is to hand a terminal-side operator (who has no token yet at
 * the login screen) a session from just the 4-digit code. See TerminalPasskeyLoginRequest's doc
 * in ApiContracts.kt for the full contract.
 *
 * Backend route only, per this pass's explicit scope — terminalApp's `TerminalPasskeyLoginScreen`
 * is NOT wired to this endpoint yet (still the disabled UI shell it was in Phase 3); that wiring
 * is separate, deliberately deferred follow-up work, not part of this change.
 *
 * Beyond the request's own approved-and-unexpired check, this also confirms the submitted
 * `terminalId` belongs to the SAME site the request was approved for — not explicitly asked for
 * in the task, but without it a passkey approved for one cabinet's site could be replayed at any
 * other terminal in the system, which would defeat the whole point of scoping the session to a
 * specific site's keys. Flagged as a deliberate addition, not silently assumed.
 */
export async function passkeyLogin(req, res) {
  const ipAddress = req.ip || 'unknown';

  if (await isLoginRateLimited(ipAddress)) {
    return res.status(429).json({
      error: 'RATE_LIMITED',
      message: 'Too many failed passkey attempts. Try again later.',
    });
  }

  const parsed = passkeyLoginSchema.safeParse(req.body);
  if (!parsed.success) {
    await recordLoginAttempt(ipAddress, false);
    return badRequest(res, 'passkey must be exactly 4 digits and terminalId must be a valid id');
  }

  const [terminals] = await pool.execute(
    `SELECT id, site_id FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.terminalId },
  );
  const terminal = terminals[0];
  if (!terminal) {
    await recordLoginAttempt(ipAddress, false);
    return badRequest(res, 'terminalId must reference an active terminal');
  }

  // Deliberately does NOT mark the request "consumed" on a successful login — unlike a pairing
  // code (strictly single-use), a passkey stays valid for repeated logins throughout its whole
  // approved window (e.g. a vendor tapping in and out of a site visit more than once). Flagged:
  // if single-use-per-approval turns out to be the intended behavior instead, add a
  // `used_at_epoch_ms` guard here the same way pair-with-code guards on
  // `pairing_code_consumed_at_epoch_ms`.
  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT * FROM key_access_requests
     WHERE generated_passkey = :code
       AND site_id = :siteId
       AND status = 'APPROVED'
       AND passkey_expires_at_epoch_ms > :now
     LIMIT 1`,
    { code: parsed.data.passkey, siteId: terminal.site_id, now },
  );
  const request = rows[0];

  if (!request) {
    await recordLoginAttempt(ipAddress, false);
    await writeAudit({
      eventType: 'KEY_ACCESS_SESSION_LOGIN_FAILED',
      terminalId: terminal.id,
      siteId: terminal.site_id,
      detail: 'Invalid, expired, or wrong-site passkey',
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired passkey' });
  }

  const keyIds = await requestKeyIds(request.id);
  const accessToken = signKeyAccessSessionToken(request, keyIds);

  await recordLoginAttempt(ipAddress, true);
  await writeAudit({
    eventType: 'KEY_ACCESS_SESSION_STARTED',
    actorUserId: request.requester_user_id,
    terminalId: terminal.id,
    siteId: terminal.site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: request.id,
  });

  return res.json({
    accessToken,
    keyAccessRequestId: request.id,
    requesterUserId: request.requester_user_id,
    siteId: request.site_id,
    keyIds,
    expiresAtEpochMillis: Number(request.passkey_expires_at_epoch_ms),
  });
}
