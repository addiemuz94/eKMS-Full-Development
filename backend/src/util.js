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
  id = newId(),
}) {
  await conn.execute(
    `INSERT INTO audit_events
      (id, event_type, actor_user_id, terminal_id, site_id, entity_type, entity_id, occurred_at_epoch_ms, detail)
     VALUES (:id, :eventType, :actorUserId, :terminalId, :siteId, :entityType, :entityId, :now, :detail)`,
    {
      id,
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
  return { id };
}

/**
 * Row-level half of Regional Admin's two-layer access control — the other half is the
 * REGIONAL_ADMIN_ALLOWED_ROUTES allowlist in middleware/auth.js, which controls *which
 * routes* are reachable at all. This controls *which rows* within those routes: a Regional
 * Admin may only act on records tied to one of their own `user_site_assignments`. Both layers
 * are required together; neither alone is sufficient. Super Admin callers should check
 * `req.auth.role === 'SUPER_ADMIN'` first and skip this entirely (unrestricted, as before).
 */
export async function isSiteAssignedToUser(userId, siteId) {
  const [rows] = await pool.execute(
    `SELECT 1 FROM user_site_assignments WHERE user_id = :userId AND site_id = :siteId LIMIT 1`,
    { userId, siteId },
  );
  return Boolean(rows[0]);
}

/** All site ids a user is assigned to — used to scope a Regional Admin's list views down to
 * their own region when no specific `?siteId=` filter is given. */
export async function assignedSiteIdsForUser(userId) {
  const [rows] = await pool.execute(
    `SELECT site_id FROM user_site_assignments WHERE user_id = :userId`,
    { userId },
  );
  return rows.map((r) => r.site_id);
}

/**
 * Region-scoped counterpart of [isSiteAssignedToUser], added for key-access-request routing
 * (migration 009). Deliberately checks `user_region_assignments`, NOT `user_site_assignments` —
 * a Regional Admin may approve a key-access request for any Site inside a Region they're
 * assigned to, even one they have no individual Site assignment for (see the "deliberate
 * simplification, no consistency check" note on user_region_assignments in
 * 009_regions_and_key_access_requests.sql). Same two-layer model as every other Regional Admin
 * route: this is the row-level half, REGIONAL_ADMIN_ALLOWED_ROUTES in middleware/auth.js is the
 * route-level half.
 */
export async function isRegionAssignedToUser(userId, regionId) {
  if (!regionId) return false;
  const [rows] = await pool.execute(
    `SELECT 1 FROM user_region_assignments WHERE user_id = :userId AND region_id = :regionId LIMIT 1`,
    { userId, regionId },
  );
  return Boolean(rows[0]);
}

/** All region ids a user is assigned to — used to scope a Regional Admin's key-access-request
 * list view down to their own regions when no specific `?siteId=` filter is given. */
export async function assignedRegionIdsForUser(userId) {
  const [rows] = await pool.execute(
    `SELECT region_id FROM user_region_assignments WHERE user_id = :userId`,
    { userId },
  );
  return rows.map((r) => r.region_id);
}

/** A site's region, or `null` if the site has no region assigned yet (region_id is nullable —
 * see migration 009). A request tied to a regionless site cannot be routed to any Regional
 * Admin until a Super Admin assigns that site to a region. */
export async function regionIdForSite(siteId) {
  const [rows] = await pool.execute(`SELECT region_id FROM sites WHERE id = :id LIMIT 1`, { id: siteId });
  return rows[0]?.region_id ?? null;
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
