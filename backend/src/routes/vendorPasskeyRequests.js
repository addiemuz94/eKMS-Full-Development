import crypto from 'crypto';
import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  assignedSiteIdsForUser,
  badRequest,
  conflict,
  isSiteAssignedToUser,
  newId,
  notFound,
  nowMs,
  writeAudit,
} from '../util.js';

const router = Router();

/** Same two-layer model as sites.js/terminals.js/accessGrants.js — see isSiteAssignedToUser's
 * doc. Regional Admin may only act on vendor passkey requests for their assigned sites. */
async function assertMayAccessPasskeyRequestSite(req, siteId) {
  if (req.auth?.role === 'SUPER_ADMIN') return true;
  if (req.auth?.role === 'REGIONAL_ADMIN') return isSiteAssignedToUser(req.auth.sub, siteId);
  return false;
}

// Placeholder duration — no expiry length was specified for this deliberately minimal schema.
// Chosen to comfortably cover a single vendor site visit, not a short pairing-style window;
// revisit once the full request/approval UX is designed in the mobileApp phase.
const PASSKEY_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

function mapRequest(row) {
  return {
    id: row.id,
    vendorUserId: row.vendor_user_id,
    siteId: row.site_id,
    requestedAtEpochMillis: Number(row.requested_at_epoch_ms),
    status: row.status,
    approvedByUserId: row.approved_by_user_id,
    approvedAtEpochMillis: row.approved_at_epoch_ms == null ? null : Number(row.approved_at_epoch_ms),
    // passkeyCode is only ever non-null on the approve response itself — see the note on
    // ApproveVendorPasskeyRequestResponse in ApiContracts.kt. GET responses omit it below.
    passkeyExpiresAtEpochMillis:
      row.passkey_expires_at_epoch_ms == null ? null : Number(row.passkey_expires_at_epoch_ms),
  };
}

router.get('/', async (req, res) => {
  const { siteId } = req.query;
  const status = req.query.status || 'PENDING';

  if (siteId && req.auth?.role === 'REGIONAL_ADMIN' && !(await isSiteAssignedToUser(req.auth.sub, siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted to view requests for this site' });
  }

  let sql = `SELECT * FROM vendor_passkey_requests`;
  const params = {};
  const conditions = [];
  if (siteId) {
    conditions.push(`site_id = :siteId`);
    params.siteId = siteId;
  } else if (req.auth?.role === 'REGIONAL_ADMIN') {
    // No specific siteId requested — scope the list down to the Regional Admin's own region
    // rather than 403ing, same semantics as accessGrants.js's list route.
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return res.json({ items: [] });
    conditions.push(`site_id IN (${assignedSiteIds.map((_, i) => `:site${i}`).join(', ')})`);
    assignedSiteIds.forEach((id, i) => {
      params[`site${i}`] = id;
    });
  }
  if (status !== 'ALL') {
    conditions.push(`status = :status`);
    params.status = status;
  }
  if (conditions.length > 0) sql += ` WHERE ${conditions.join(' AND ')}`;
  sql += ` ORDER BY requested_at_epoch_ms DESC`;

  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapRequest) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM vendor_passkey_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Vendor passkey request not found');
  // Out-of-scope reads as "not found", not "forbidden" — avoids confirming a request's
  // existence to a Regional Admin who isn't assigned to its site.
  if (!(await assertMayAccessPasskeyRequestSite(req, rows[0].site_id))) {
    return notFound(res, 'Vendor passkey request not found');
  }
  return res.json(mapRequest(rows[0]));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    vendorUserId: z.string().uuid(),
    siteId: z.string().uuid(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid vendor passkey request payload');

  if (!(await assertMayAccessPasskeyRequestSite(req, parsed.data.siteId))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Not permitted to create a request for this site' });
  }

  const [vendor] = await pool.execute(
    `SELECT id FROM users WHERE id = :id AND role = 'VENDOR' AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.vendorUserId },
  );
  if (!vendor[0]) return badRequest(res, 'vendorUserId must reference an active VENDOR-role user');

  const [site] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.siteId },
  );
  if (!site[0]) return badRequest(res, 'siteId must reference an active site');

  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO vendor_passkey_requests (id, vendor_user_id, site_id, requested_at_epoch_ms, status)
     VALUES (:id, :vendorUserId, :siteId, :now, 'PENDING')`,
    { id, vendorUserId: parsed.data.vendorUserId, siteId: parsed.data.siteId, now },
  );
  await writeAudit({
    eventType: 'VENDOR_PASSKEY_REQUESTED',
    actorUserId: req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub,
    siteId: parsed.data.siteId,
    entityType: 'VENDOR_PASSKEY_REQUEST',
    entityId: id,
  });

  const [rows] = await pool.execute(`SELECT * FROM vendor_passkey_requests WHERE id = :id`, { id });
  return res.status(201).json(mapRequest(rows[0]));
});

router.post('/:id/approve', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM vendor_passkey_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Vendor passkey request not found');
  // Out-of-scope reads as "not found" here too, same reasoning as the GET routes above.
  if (!(await assertMayAccessPasskeyRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Vendor passkey request not found');
  }
  if (existing[0].status !== 'PENDING') {
    return conflict(res, 'Request is no longer pending');
  }

  // crypto.randomInt is uniform over the range (no modulo bias), same approach as terminal
  // pairing codes in pairing.js — just 4 digits here instead of 6.
  const code = String(crypto.randomInt(0, 10_000)).padStart(4, '0');
  const now = nowMs();
  const expiresAt = now + PASSKEY_TTL_MS;
  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;

  // Double-checked guard against a concurrent approve/reject, same spirit as expectedRevision
  // elsewhere: application-level check above AND status = 'PENDING' in the UPDATE itself.
  const [result] = await pool.execute(
    `UPDATE vendor_passkey_requests SET
       status = 'APPROVED',
       approved_by_user_id = :actorUserId,
       approved_at_epoch_ms = :now,
       passkey_code = :code,
       passkey_expires_at_epoch_ms = :expiresAt
     WHERE id = :id AND status = 'PENDING'`,
    { id: req.params.id, actorUserId, now, code, expiresAt },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer pending');

  await writeAudit({
    eventType: 'VENDOR_PASSKEY_APPROVED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'VENDOR_PASSKEY_REQUEST',
    entityId: req.params.id,
  });

  // The plaintext code is shown exactly once, in this response only — same "shown once"
  // treatment as terminal pairing codes. Nothing else re-reads passkey_code afterward.
  return res.json({
    id: req.params.id,
    status: 'APPROVED',
    passkeyCode: code,
    passkeyExpiresAtEpochMillis: expiresAt,
  });
});

router.post('/:id/reject', async (req, res) => {
  const [existing] = await pool.execute(`SELECT * FROM vendor_passkey_requests WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!existing[0]) return notFound(res, 'Vendor passkey request not found');
  if (!(await assertMayAccessPasskeyRequestSite(req, existing[0].site_id))) {
    return notFound(res, 'Vendor passkey request not found');
  }
  if (existing[0].status !== 'PENDING') {
    return conflict(res, 'Request is no longer pending');
  }

  // No rejected-by/rejected-at columns exist on this deliberately minimal table (only
  // approved_by_user_id/approved_at_epoch_ms, which are for the approve path specifically —
  // reusing them here would misleadingly label a rejection as "approved by"). Who rejected a
  // request is captured by the VENDOR_PASSKEY_REJECTED audit event's actorUserId instead.
  const actorUserId = req.auth?.role === 'TERMINAL_DEVICE' ? null : req.auth.sub;
  const [result] = await pool.execute(
    `UPDATE vendor_passkey_requests SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'`,
    { id: req.params.id },
  );
  if (result.affectedRows === 0) return conflict(res, 'Request is no longer pending');

  await writeAudit({
    eventType: 'VENDOR_PASSKEY_REJECTED',
    actorUserId,
    siteId: existing[0].site_id,
    entityType: 'VENDOR_PASSKEY_REQUEST',
    entityId: req.params.id,
  });

  const [rows] = await pool.execute(`SELECT * FROM vendor_passkey_requests WHERE id = :id`, {
    id: req.params.id,
  });
  return res.json(mapRequest(rows[0]));
});

export default router;
