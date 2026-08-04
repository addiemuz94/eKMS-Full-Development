import crypto from 'crypto';
import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import { sendPushToUser } from '../fcm.js';
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
 * Only-B key access requests for Technician + Vendor.
 * Vendor uses staged PENDING_PIC → PENDING_RA → APPROVED (replaces vendor_passkey_requests).
 * Technician uses PENDING → APPROVED (Regional Admin).
 * Past return/passkey window → EXPIRED (must resubmit; no revive).
 */

/**
 * Lazy expire: PENDING/PENDING_PIC/PENDING_RA rows whose return window passes before ever being
 * approved — those never had a PIN to extend, so a lapsed return window is unconditionally
 * terminal for them.
 *
 * Deliberately does NOT touch APPROVED rows anymore (narrowed — used to also flip an APPROVED
 * row to EXPIRED on either a lapsed return_at_epoch_ms OR a lapsed passkey_expires_at_epoch_ms).
 * Both fields are now owned by keyAccessAutoExtend.js for an APPROVED-and-never-used row (it
 * bumps both together by 1 hour, repeating, rather than letting either lapse count as terminal);
 * leaving either branch scoped to APPROVED here would race the auto-extend tick — a live API call
 * landing between two ticks could flip the row to EXPIRED before the job ever got to extend it.
 * An APPROVED row's only remaining routes to EXPIRED are: passkeyLogin()'s own inline check
 * (only for a row that has ALREADY been used at least once, per first_used_at_epoch_ms — a lapse
 * after first use is genuinely terminal, auto-extend never touches a used row) — or explicit
 * REVOKED (admin)/CANCELLED (requester) actions, which are unconditional regardless of expiry.
 */
async function expireOverdueRequests() {
  const now = nowMs();
  await pool.execute(
    `UPDATE key_access_requests SET
       status = 'EXPIRED',
       generated_passkey = NULL
     WHERE status IN ('PENDING', 'PENDING_PIC', 'PENDING_RA')
       AND return_at_epoch_ms IS NOT NULL AND return_at_epoch_ms < :now`,
    { now },
  );
}

// Exported (Aug 2026, checkout-deadline notifications) — deadlineMonitor.js reuses this as-is
// for the same "resolve site -> region -> covering Regional Admins" logic, not a reimplementation.
export async function notifyRegionalAdminsForSite(siteId, title, body, data = {}) {
  const regionId = await regionIdForSite(siteId);
  if (!regionId) return;
  const [rows] = await pool.execute(
    `SELECT u.id FROM users u
     INNER JOIN user_region_assignments ura ON ura.user_id = u.id
     WHERE ura.region_id = :regionId AND u.role = 'REGIONAL_ADMIN'
       AND u.lifecycle_state = 'ACTIVE'`,
    { regionId },
  );
  for (const row of rows) {
    await sendPushToUser(row.id, title, body, data);
  }
}

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
  if (
    req.auth?.role === 'TECHNICIAN' &&
    row.pic_user_id === req.auth.sub &&
    (row.status === 'PENDING_PIC' || row.status === 'PENDING_RA' || row.status === 'APPROVED')
  ) {
    return true;
  }
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

/** Metadata only (id/kind/name/type/size/uploadedAt) — never selects `content`, so this never
 * pulls document bytes into a list/get response. `LENGTH(content)` is computed server-side rather
 * than fetched-then-measured, for the same reason: keep bytes out of every response but the
 * dedicated download route below. */
async function requestDocuments(requestId) {
  const [rows] = await pool.execute(
    `SELECT id, doc_kind, file_name, content_type, LENGTH(content) AS size_bytes, created_at_epoch_ms
     FROM key_access_request_documents WHERE request_id = :requestId ORDER BY created_at_epoch_ms ASC`,
    { requestId },
  );
  return rows.map((r) => ({
    id: r.id,
    docKind: r.doc_kind,
    fileName: r.file_name,
    contentType: r.content_type,
    sizeBytes: Number(r.size_bytes),
    createdAtEpochMillis: Number(r.created_at_epoch_ms),
  }));
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
function mapRequest(
  row,
  keyIds,
  viewerUserId,
  siteName = null,
  cabinetNames = [],
  requesterDisplayName = null,
  documents = [],
) {
  return {
    id: row.id,
    requesterUserId: row.requester_user_id,
    requesterRole: row.requester_role,
    siteId: row.site_id,
    siteName,
    requesterDisplayName,
    cabinetNames,
    keyIds,
    requestedAtEpochMillis: Number(row.requested_at_epoch_ms),
    requestedDurationMinutes: Number(row.requested_duration_minutes),
    reason: row.reason ?? null,
    pickupAtEpochMillis: row.pickup_at_epoch_ms == null ? null : Number(row.pickup_at_epoch_ms),
    returnAtEpochMillis: row.return_at_epoch_ms == null ? null : Number(row.return_at_epoch_ms),
    status: row.status,
    picUserId: row.pic_user_id ?? null,
    picApprovedAtEpochMillis:
      row.pic_approved_at_epoch_ms == null ? null : Number(row.pic_approved_at_epoch_ms),
    documents,
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
  const [requesters] = await pool.execute(
    `SELECT display_name FROM users WHERE id = :id LIMIT 1`,
    { id: row.requester_user_id },
  );
  const documents = await requestDocuments(row.id);
  return mapRequest(
    row,
    keyIds,
    viewerUserId,
    sites[0]?.name ?? null,
    terminals.map((t) => t.name),
    requesters[0]?.display_name ?? null,
    documents,
  );
}

router.get('/', async (req, res) => {
  await expireOverdueRequests();
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

  // PIC queue: Technicians see PENDING_PIC assigned to them when status=PENDING_PIC and not self-list
  // (self-list handled above). Regional/Super continue below.

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
    // SA/RA "PENDING" queue includes Vendor stage-2 PENDING_RA (and Technician PENDING).
    if (
      status === 'PENDING' &&
      (req.auth?.role === 'SUPER_ADMIN' || req.auth?.role === 'REGIONAL_ADMIN')
    ) {
      conditions.push(`status IN ('PENDING', 'PENDING_RA')`);
    } else {
      conditions.push(`status = :status`);
      params.status = status;
    }
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

/**
 * GET /pic-inbox — Vendor Stage-1 queue for the signed-in Technician (as PIC).
 */
router.get('/pic-inbox', async (req, res) => {
  await expireOverdueRequests();
  if (req.auth?.role !== 'TECHNICIAN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'PIC inbox is for Technicians' });
  }
  const [rows] = await pool.execute(
    `SELECT * FROM key_access_requests
     WHERE pic_user_id = :picUserId AND status = 'PENDING_PIC'
     ORDER BY requested_at_epoch_ms DESC`,
    { picUserId: req.auth.sub },
  );
  const items = [];
  for (const row of rows) {
    items.push(await mapRequestEnriched(row, await requestKeyIds(row.id), req.auth?.sub));
  }
  return res.json({ items });
});

/**
 * GET /site-pics/:siteId — Technicians assigned to a site (Vendor PIC picker).
 */
router.get('/site-pics/:siteId', async (req, res) => {
  if (req.auth?.role !== 'VENDOR' && req.auth?.role !== 'SUPER_ADMIN' && req.auth?.role !== 'REGIONAL_ADMIN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'PIC list is for Vendor self-service' });
  }
  if (req.auth?.role === 'VENDOR' && !(await assertMayCreateForSite(req, req.params.siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted for this site' });
  }
  if (
    (req.auth?.role === 'SUPER_ADMIN' || req.auth?.role === 'REGIONAL_ADMIN') &&
    !(await assertMayAccessRequestSite(req, req.params.siteId))
  ) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted for this site' });
  }
  const [rows] = await pool.execute(
    `SELECT u.id, u.display_name, u.email FROM users u
     INNER JOIN user_site_assignments usa ON usa.user_id = u.id
     WHERE usa.site_id = :siteId AND u.role = 'TECHNICIAN' AND u.lifecycle_state = 'ACTIVE'
     ORDER BY u.display_name ASC`,
    { siteId: req.params.siteId },
  );
  return res.json({
    items: rows.map((r) => ({ id: r.id, displayName: r.display_name, email: r.email })),
  });
});

router.get('/:id', async (req, res) => {
  await expireOverdueRequests();
  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayReadRequest(req, rows[0]))) {
    return notFound(res, 'Key access request not found');
  }
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(rows[0].id), req.auth?.sub));
});

/**
 * GET /:id/documents/:documentId — one attached document's raw bytes, for a PIC/Regional Admin
 * (or the requester themselves) to view/download before acting on the request. Metadata for all
 * of a request's documents is already embedded on the request DTO (`documents`, via
 * mapRequestEnriched) — this route is bytes-only, deliberately not duplicated as a separate list
 * endpoint. Same read-access model as GET /:id (assertMayReadRequest: requester, assigned PIC
 * while PENDING_PIC/PENDING_RA/APPROVED, or Regional/Super Admin scoped to the request's site) —
 * a PIC/RA cannot fetch a document belonging to a request they're not the approver on.
 */
router.get('/:id/documents/:documentId', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayReadRequest(req, rows[0]))) {
    return notFound(res, 'Key access request not found');
  }
  const [docs] = await pool.execute(
    `SELECT * FROM key_access_request_documents WHERE id = :documentId AND request_id = :id LIMIT 1`,
    { documentId: req.params.documentId, id: req.params.id },
  );
  if (!docs[0]) return notFound(res, 'Document not found');

  const doc = docs[0];
  // Strip CR/LF before it reaches a response header — file_name is requester-controlled input
  // (the original upload's filename) and an unsanitized value here is a header-injection vector.
  const safeFileName = doc.file_name.replace(/[\r\n]/g, '_');
  res.setHeader('Content-Type', doc.content_type || 'application/octet-stream');
  res.setHeader(
    'Content-Disposition',
    `attachment; filename="${safeFileName.replace(/"/g, "'")}"; filename*=UTF-8''${encodeURIComponent(doc.file_name)}`,
  );
  return res.send(doc.content);
});

/**
 * Create schema — Technician → PENDING; Vendor → PENDING_PIC (+ docs + picUserId).
 */
const createSchema = z.object({
  requesterUserId: z.string().uuid().optional(),
  requesterRole: z.enum(['TECHNICIAN', 'VENDOR']).optional(),
  siteId: z.string().uuid(),
  keyIds: z.array(z.string().uuid()).min(1),
  reason: z.string().trim().min(1).max(2000),
  pickupAtEpochMillis: z.number().int().positive(),
  returnAtEpochMillis: z.number().int().positive(),
  /** Required when requester is VENDOR — Person In Charge (Technician at the site). */
  picUserId: z.string().uuid().optional(),
  /** Optional base64 document uploads at create time (Work Permit / NIOSH / IC). */
  documents: z
    .array(
      z.object({
        docKind: z.enum(['WORK_PERMIT', 'NIOSH', 'IC']),
        fileName: z.string().trim().min(1).max(255),
        contentType: z.string().trim().min(1).max(128).default('application/octet-stream'),
        contentBase64: z.string().min(1),
      }),
    )
    .optional(),
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

  let picUserId = null;
  let initialStatus = 'PENDING';
  if (requesterRole === 'VENDOR') {
    if (!parsed.data.picUserId) {
      return badRequest(res, 'picUserId is required for Vendor requests');
    }
    const [pic] = await pool.execute(
      `SELECT u.id FROM users u
       INNER JOIN user_site_assignments usa ON usa.user_id = u.id
       WHERE u.id = :id AND u.role = 'TECHNICIAN' AND u.lifecycle_state = 'ACTIVE'
         AND usa.site_id = :siteId
       LIMIT 1`,
      { id: parsed.data.picUserId, siteId: parsed.data.siteId },
    );
    if (!pic[0]) {
      return badRequest(res, 'picUserId must be an active Technician assigned to the requested site');
    }
    picUserId = parsed.data.picUserId;
    initialStatus = 'PENDING_PIC';
    if (!parsed.data.documents || parsed.data.documents.length < 1) {
      return badRequest(res, 'Vendor requests require at least one document (Work Permit, NIOSH, or IC)');
    }
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
        (id, requester_user_id, requester_role, pic_user_id, site_id, reason, requested_at_epoch_ms,
         requested_duration_minutes, pickup_at_epoch_ms, return_at_epoch_ms, status)
       VALUES (:id, :requesterUserId, :requesterRole, :picUserId, :siteId, :reason, :now,
         :requestedDurationMinutes, :pickupAt, :returnAt, :status)`,
      {
        id,
        requesterUserId,
        requesterRole,
        picUserId,
        siteId: parsed.data.siteId,
        reason: parsed.data.reason,
        now,
        requestedDurationMinutes: derivedDurationMinutes,
        pickupAt: parsed.data.pickupAtEpochMillis,
        returnAt: parsed.data.returnAtEpochMillis,
        status: initialStatus,
      },
    );
    for (const keyId of parsed.data.keyIds) {
      await conn.execute(
        `INSERT INTO key_access_request_keys (request_id, key_id) VALUES (:id, :keyId)`,
        { id, keyId },
      );
    }
    if (parsed.data.documents?.length) {
      for (const doc of parsed.data.documents) {
        let buf;
        try {
          buf = Buffer.from(doc.contentBase64, 'base64');
        } catch {
          throw new Error('INVALID_DOC');
        }
        // 5 MB is the real enforced ceiling (matches KeyAccessRequestScreen.kt's client-side
        // check, the intended product limit — see "Only B decisions locked" in CLAUDE_MOBILE.md).
        // The client check is UX-only (a client can be bypassed/modified); this server check is
        // the actual gate. `content`'s column type is MEDIUMBLOB (up to 16 MB) — that's headroom
        // for the column, not the enforced limit, and must not be confused with it.
        if (buf.length === 0 || buf.length > 5 * 1024 * 1024) {
          throw new Error('INVALID_DOC_SIZE');
        }
        await conn.execute(
          `INSERT INTO key_access_request_documents
            (id, request_id, doc_kind, file_name, content_type, content, created_at_epoch_ms)
           VALUES (:id, :requestId, :docKind, :fileName, :contentType, :content, :now)`,
          {
            id: newId(),
            requestId: id,
            docKind: doc.docKind,
            fileName: doc.fileName,
            contentType: doc.contentType,
            content: buf,
            now,
          },
        );
      }
    }
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    if (err.message === 'INVALID_DOC' || err.message === 'INVALID_DOC_SIZE') {
      return badRequest(res, 'Invalid document payload (base64, max 5MB each)');
    }
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

  if (initialStatus === 'PENDING_PIC' && picUserId) {
    await sendPushToUser(picUserId, 'Vendor access — PIC review', 'A vendor request needs your approval', {
      requestId: id,
      stage: 'PENDING_PIC',
    });
  } else if (initialStatus === 'PENDING') {
    await notifyRegionalAdminsForSite(parsed.data.siteId, 'Key access request', 'A technician request awaits approval', {
      requestId: id,
      stage: 'PENDING',
    });
  }

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, { id });
  return res.status(201).json(await mapRequestEnriched(rows[0], parsed.data.keyIds, req.auth?.sub));
});

router.post('/:id/approve', async (req, res) => {
  await expireOverdueRequests();
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayAccessRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Key access request not found');
  }

  // Technician: PENDING. Vendor after PIC: PENDING_RA. Never approve PENDING_PIC from RA.
  const expectedStatus = existing[0].requester_role === 'VENDOR' ? 'PENDING_RA' : 'PENDING';
  if (existing[0].status !== expectedStatus) {
    return conflict(res, `Request is not awaiting Regional Admin approval (status=${existing[0].status})`);
  }

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
     WHERE id = :id AND status = :expectedStatus`,
    { id: req.params.id, actorUserId, now, code, expiresAt, expectedStatus },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer pending Regional Admin approval');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_APPROVED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  await sendPushToUser(
    existing[0].requester_user_id,
    'Key access approved',
    `Your PIN is ready (valid until return window).`,
    { requestId: req.params.id, stage: 'APPROVED' },
  );

  return res.json({
    id: req.params.id,
    status: 'APPROVED',
    generatedPasskey: code,
    passkeyExpiresAtEpochMillis: expiresAt,
  });
});

/** PIC Stage-1 approve — Technician assigned as pic_user_id only. */
router.post('/:id/pic-approve', async (req, res) => {
  await expireOverdueRequests();
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  if (req.auth?.role !== 'TECHNICIAN' || existing[0].pic_user_id !== req.auth.sub) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Only the assigned PIC may approve Stage 1' });
  }
  if (existing[0].status !== 'PENDING_PIC') {
    return conflict(res, 'Request is not awaiting PIC approval');
  }

  const now = nowMs();
  if (existing[0].return_at_epoch_ms != null && Number(existing[0].return_at_epoch_ms) <= now) {
    return badRequest(res, 'Request return window has already ended — cannot approve');
  }

  const [result] = await pool.execute(
    `UPDATE key_access_requests SET
       status = 'PENDING_RA',
       pic_approved_at_epoch_ms = :now
     WHERE id = :id AND status = 'PENDING_PIC'`,
    { id: req.params.id, now },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer awaiting PIC approval');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_PIC_APPROVED',
    actorUserId: req.auth.sub,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  await notifyRegionalAdminsForSite(
    existing[0].site_id,
    'Vendor access — RA review',
    'A vendor request passed PIC and needs Regional Admin approval',
    { requestId: req.params.id, stage: 'PENDING_RA' },
  );

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(req.params.id), req.auth?.sub));
});

router.post('/:id/reject', async (req, res) => {
  await expireOverdueRequests();
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');

  const isPic =
    req.auth?.role === 'TECHNICIAN' &&
    existing[0].pic_user_id === req.auth.sub &&
    existing[0].status === 'PENDING_PIC';
  if (!isPic && !(await assertMayAccessRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Key access request not found');
  }

  const rejectable = ['PENDING', 'PENDING_PIC', 'PENDING_RA'];
  if (!rejectable.includes(existing[0].status)) {
    return conflict(res, 'Request is no longer rejectable');
  }
  if (isPic && existing[0].status !== 'PENDING_PIC') {
    return conflict(res, 'PIC may only reject at Stage 1');
  }
  if (!isPic && existing[0].status === 'PENDING_PIC' && req.auth?.role !== 'SUPER_ADMIN') {
    return conflict(res, 'Regional Admin cannot reject until PIC has acted');
  }
  // Super Admin can reject PENDING_PIC; RA only PENDING / PENDING_RA
  if (
    !isPic &&
    req.auth?.role === 'REGIONAL_ADMIN' &&
    existing[0].status === 'PENDING_PIC'
  ) {
    return conflict(res, 'Awaiting PIC first');
  }

  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
  const [result] = await pool.execute(
    `UPDATE key_access_requests SET status = 'REJECTED' WHERE id = :id AND status = :status`,
    { id: req.params.id, status: existing[0].status },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer rejectable');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_REJECTED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  await sendPushToUser(existing[0].requester_user_id, 'Key access rejected', 'Your request was rejected. Submit a new one if needed.', {
    requestId: req.params.id,
    stage: 'REJECTED',
  });

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(req.params.id), req.auth?.sub));
});

/**
 * POST /:id/revoke — Super Admin / Regional Admin only. Kills an APPROVED passkey so
 * terminal passkey-login fails immediately. PENDING stays on reject; REJECTED/REVOKED are no-ops.
 */
router.post('/:id/revoke', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  if (!(await assertMayAccessRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Key access request not found');
  }
  if (existing[0].status !== 'APPROVED') {
    return conflict(res, 'Only an approved request can be revoked');
  }

  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
  const [result] = await pool.execute(
    `UPDATE key_access_requests SET
       status = 'REVOKED',
       generated_passkey = NULL,
       passkey_expires_at_epoch_ms = NULL
     WHERE id = :id AND status = 'APPROVED'`,
    { id: req.params.id },
  );
  if (result.affectedRows === 0) return conflict(res, 'Only an approved request can be revoked');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_REVOKED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'KEY_ACCESS_REQUEST',
    entityId: req.params.id,
  });

  await sendPushToUser(existing[0].requester_user_id, 'Key access revoked', 'Your passkey was revoked.', {
    requestId: req.params.id,
    stage: 'REVOKED',
  });

  const [rows] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(await mapRequestEnriched(rows[0], await requestKeyIds(req.params.id), req.auth?.sub));
});

/** Requester-facing self-cancel — Technician/Vendor only, and only their OWN request (never an
 * admin action; Super Admin/Regional Admin use /revoke instead, which stays admin-only). Ends
 * the keyAccessAutoExtend.js auto-extend cycle immediately (that job's own query already
 * excludes non-APPROVED rows, but clearing the passkey here — same as /revoke — means a
 * concurrent passkey-login attempt fails immediately too, not just on the next tick). Terminal,
 * no-revive: reaching the cabinet again requires a brand-new request, not a resubmission of
 * this row. */
const CANCELLABLE_STATUSES = ['PENDING', 'PENDING_PIC', 'PENDING_RA', 'APPROVED'];

router.post('/:id/cancel', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM key_access_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Key access request not found');
  // Self-service only — reads as "not found" for anyone but the request's own requester, same
  // not-confirming-existence convention as every other route in this file.
  if (existing[0].requester_user_id !== req.auth?.sub) {
    return notFound(res, 'Key access request not found');
  }
  if (!CANCELLABLE_STATUSES.includes(existing[0].status)) {
    return conflict(res, 'Request can no longer be cancelled');
  }

  const [result] = await pool.execute(
    `UPDATE key_access_requests SET
       status = 'CANCELLED',
       generated_passkey = NULL,
       passkey_expires_at_epoch_ms = NULL
     WHERE id = :id AND status = :status`,
    { id: req.params.id, status: existing[0].status },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request can no longer be cancelled');

  await writeAudit({
    eventType: 'KEY_ACCESS_REQUEST_CANCELLED',
    actorUserId: req.auth.sub,
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
 * POST /v1/terminal/passkey-login — unauthenticated. Only APPROVED + within pickup/return window.
 */
export async function passkeyLogin(req, res) {
  await expireOverdueRequests();
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

  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT * FROM key_access_requests
     WHERE generated_passkey = :code
       AND site_id = :siteId
       AND status = 'APPROVED'
     LIMIT 1`,
    { code: parsed.data.passkey, siteId: terminal.site_id },
  );
  const request = rows[0];

  if (!request) {
    await recordLoginAttempt(ipAddress, false);
    await writeAudit({
      eventType: 'KEY_ACCESS_SESSION_LOGIN_FAILED',
      terminalId: terminal.id,
      siteId: terminal.site_id,
      detail: 'Invalid, wrong-site, expired, or non-approved passkey',
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired passkey' });
  }

  const expiresAt = Number(request.passkey_expires_at_epoch_ms);
  const neverUsed = request.first_used_at_epoch_ms == null;
  // A lapsed-but-never-used APPROVED passkey is deliberately NOT hard-failed here — the whole
  // point of keyAccessAutoExtend.js's recurring extend is that the PIN never dies from tick lag
  // (up to ~60s between ticks). Login proceeds exactly as if it hadn't lapsed; the unconditional
  // first-use stamp below then stops any future auto-extend tick from touching this row — no
  // separate "catch-up extend" of passkey_expires_at_epoch_ms/return_at_epoch_ms is needed for
  // this path, per instruction. Once a row HAS been used at least once (first_used_at_epoch_ms
  // set), auto-extension has permanently stopped for it, so a lapse after that point is
  // genuinely terminal and still hard-fails below — same as an unparseable/missing expiry value,
  // which is a data-integrity case, not a legitimate lapse, and always hard-fails regardless of
  // first_used_at_epoch_ms.
  if (!Number.isFinite(expiresAt) || (expiresAt <= now && !neverUsed)) {
    await pool.execute(
      `UPDATE key_access_requests SET status = 'EXPIRED', generated_passkey = NULL
       WHERE id = :id AND status = 'APPROVED'`,
      { id: request.id },
    );
    await recordLoginAttempt(ipAddress, false);
    await writeAudit({
      eventType: 'KEY_ACCESS_SESSION_LOGIN_FAILED',
      terminalId: terminal.id,
      siteId: terminal.site_id,
      entityType: 'KEY_ACCESS_REQUEST',
      entityId: request.id,
      detail: 'Expired passkey',
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Passkey has expired — submit a new request' });
  }

  if (request.pickup_at_epoch_ms != null && Number(request.pickup_at_epoch_ms) > now) {
    await recordLoginAttempt(ipAddress, false);
    await writeAudit({
      eventType: 'KEY_ACCESS_SESSION_LOGIN_FAILED',
      terminalId: terminal.id,
      siteId: terminal.site_id,
      entityType: 'KEY_ACCESS_REQUEST',
      entityId: request.id,
      detail: 'Passkey used before pickup window',
    });
    const pickupAt = Number(request.pickup_at_epoch_ms);
    return res.status(401).json({
      error: 'UNAUTHORIZED',
      message: `Passkey not valid until pickup time (${new Date(pickupAt).toLocaleString('en-MY', { timeZone: 'Asia/Kuala_Lumpur' })})`,
    });
  }

  const keyIds = await requestKeyIds(request.id);
  const accessToken = signKeyAccessSessionToken(request, keyIds);

  const [requesterRows] = await pool.execute(
    `SELECT id, display_name, email, role FROM users
     WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: request.requester_user_id },
  );
  const requester = requesterRows[0];
  if (!requester) {
    await recordLoginAttempt(ipAddress, false);
    return res.status(401).json({
      error: 'UNAUTHORIZED',
      message: 'Passkey requester account is no longer available',
    });
  }

  await recordLoginAttempt(ipAddress, true);
  // First successful login only — stops future keyAccessAutoExtend.js auto-extension without
  // invalidating this session or the PIN's remaining validity. `first_used_at_epoch_ms IS NULL`
  // in the WHERE guards against overwriting it on a second/third login within the same window.
  if (request.first_used_at_epoch_ms == null) {
    await pool.execute(
      `UPDATE key_access_requests SET first_used_at_epoch_ms = :now
       WHERE id = :id AND first_used_at_epoch_ms IS NULL`,
      { id: request.id, now },
    );
  }
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
    requesterUserId: requester.id,
    requesterDisplayName: requester.display_name,
    requesterEmail: requester.email,
    requesterRole: requester.role,
    siteId: request.site_id,
    keyIds,
    expiresAtEpochMillis: Number(request.passkey_expires_at_epoch_ms),
  });
}
