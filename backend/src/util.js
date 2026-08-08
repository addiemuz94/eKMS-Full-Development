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
  occurredAtEpochMillis = null,
  conn = pool,
  id = newId(),
}) {
  const when =
    occurredAtEpochMillis != null && !Number.isNaN(Number(occurredAtEpochMillis))
      ? Number(occurredAtEpochMillis)
      : nowMs();
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
      now: when,
      detail,
    },
  );
  return { id };
}

/**
 * Row-level half of Regional Admin's two-layer access control — the other half is the
 * REGIONAL_ADMIN_ALLOWED_ROUTES allowlist in middleware/auth.js, which controls *which
 * routes* are reachable at all. This controls *which rows* within those routes: a Regional
 * Admin may act on records tied to their `user_site_assignments`, OR (legacy / dual coverage)
 * any active site whose `region_id` is in their `user_region_assignments`. Super Admin callers
 * should check `req.auth.role === 'SUPER_ADMIN'` first and skip this entirely.
 */
export async function isSiteAssignedToUser(userId, siteId) {
  if (!userId || !siteId) return false;
  const [direct] = await pool.execute(
    `SELECT 1 FROM user_site_assignments WHERE user_id = :userId AND site_id = :siteId LIMIT 1`,
    { userId, siteId },
  );
  if (direct[0]) return true;
  const [viaRegion] = await pool.execute(
    `SELECT 1 FROM sites s
     INNER JOIN user_region_assignments ura ON ura.region_id = s.region_id
     WHERE ura.user_id = :userId AND s.id = :siteId
       AND s.lifecycle_state = 'ACTIVE' AND s.region_id IS NOT NULL
     LIMIT 1`,
    { userId, siteId },
  );
  return Boolean(viaRegion[0]);
}

/**
 * Effective site ids for a user — direct `user_site_assignments` plus sites in any region
 * they still hold via `user_region_assignments`. Used to scope RA lists, SSE tickets, and
 * key-access queues. Region half is deliberate dual coverage so RAs who were only ever
 * region-assigned (before site-based Assign User) still receive PENDING_RA work.
 */
export async function assignedSiteIdsForUser(userId) {
  const [direct] = await pool.execute(
    `SELECT site_id FROM user_site_assignments WHERE user_id = :userId`,
    { userId },
  );
  const [viaRegion] = await pool.execute(
    `SELECT s.id AS site_id FROM sites s
     INNER JOIN user_region_assignments ura ON ura.region_id = s.region_id
     WHERE ura.user_id = :userId AND s.lifecycle_state = 'ACTIVE' AND s.region_id IS NOT NULL`,
    { userId },
  );
  return [...new Set([...direct.map((r) => r.site_id), ...viaRegion.map((r) => r.site_id)])];
}

/**
 * Site-based counterpart of [isSiteAssignedToUser]/[assignedSiteIdsForUser], the OTHER
 * direction: every REGIONAL_ADMIN covering this site via direct assignment OR the site's region.
 */
export async function raIdsForSite(siteId) {
  const [direct] = await pool.execute(
    `SELECT u.id FROM users u
     INNER JOIN user_site_assignments usa ON usa.user_id = u.id
     WHERE usa.site_id = :siteId AND u.role = 'REGIONAL_ADMIN' AND u.lifecycle_state = 'ACTIVE'`,
    { siteId },
  );
  const [viaRegion] = await pool.execute(
    `SELECT u.id FROM users u
     INNER JOIN user_region_assignments ura ON ura.user_id = u.id
     INNER JOIN sites s ON s.region_id = ura.region_id
     WHERE s.id = :siteId AND s.lifecycle_state = 'ACTIVE' AND s.region_id IS NOT NULL
       AND u.role = 'REGIONAL_ADMIN' AND u.lifecycle_state = 'ACTIVE'`,
    { siteId },
  );
  return [...new Set([...direct.map((r) => r.id), ...viaRegion.map((r) => r.id)])];
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
 *
 * UNUSED as of the "regional confusion" Tier 1 rework — RA row-level authorization moved to
 * [raIdsForSite]/[isSiteAssignedToUser] (site-based). Left defined, not deleted: `regions` and
 * `user_region_assignments` are explicitly kept live for this tier (region survives as a
 * cosmetic map-grouping label — see TerminalsMap.tsx), and dropping this function ahead of that
 * table's own removal is a separate, later cleanup tier, not this one.
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
 * list view down to their own regions when no specific `?siteId=` filter is given.
 *
 * UNUSED as of the "regional confusion" Tier 1 rework — see [isRegionAssignedToUser]'s doc for
 * why this stays defined rather than deleted in this tier. */
export async function assignedRegionIdsForUser(userId) {
  const [rows] = await pool.execute(
    `SELECT region_id FROM user_region_assignments WHERE user_id = :userId`,
    { userId },
  );
  return rows.map((r) => r.region_id);
}

/** A site's region, or `null` if the site has no region assigned yet (region_id is nullable —
 * see migration 009).
 *
 * UNUSED as of the "regional confusion" max_key_access_duration_minutes migration (015) — its one
 * remaining caller, keyAccessRequests.js's `GET /site-policy/:siteId`, was the last real consumer
 * of a site's region and now reads sites.max_key_access_duration_minutes directly instead. Left
 * defined, not deleted, for the same reason as [isRegionAssignedToUser]/[assignedRegionIdsForUser]
 * above — `regions`/`sites.region_id` are still live columns/tables, just no longer read by any
 * app-layer code path. */
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

/**
 * Restrict audit/report queries by whether the cabinet is still active.
 * ACTIVE — events with no cabinet, or an ACTIVE terminal.
 * DELETED — events whose terminal is not ACTIVE (recycle bin or purged).
 */
export function appendCabinetScopeSql(sql, params, cabinetScope) {
  if (cabinetScope === 'ACTIVE') {
    return {
      sql:
        `${sql} AND (terminal_id IS NULL OR terminal_id IN (` +
        `SELECT id FROM terminals WHERE lifecycle_state = 'ACTIVE'))`,
      params,
    };
  }
  if (cabinetScope === 'DELETED') {
    return {
      sql:
        `${sql} AND terminal_id IS NOT NULL AND terminal_id NOT IN (` +
        `SELECT id FROM terminals WHERE lifecycle_state = 'ACTIVE')`,
      params,
    };
  }
  return { sql, params };
}

export function parseCabinetScope(value, defaultScope = undefined) {
  if (value === 'ACTIVE' || value === 'DELETED') return value;
  return defaultScope;
}

/**
 * Append `site_id IN (...)` for a Regional Admin's assigned sites.
 * Returns `{ sql, params, empty: true }` when the RA has no sites (caller should return []).
 * If `requestedSiteId` is set and not in the assignment set, also empty.
 */
export async function resolveRegionalAdminSiteScope(req, requestedSiteId = null) {
  const role = req?.auth?.role;
  if (role !== 'REGIONAL_ADMIN' && role !== 'TECHNICIAN' && role !== 'VENDOR') {
    return { siteIds: null, empty: false };
  }
  const assigned = await assignedSiteIdsForUser(req.auth.sub);
  if (assigned.length === 0) return { siteIds: [], empty: true };
  if (requestedSiteId) {
    if (!assigned.includes(requestedSiteId)) return { siteIds: [], empty: true };
    return { siteIds: [requestedSiteId], empty: false };
  }
  return { siteIds: assigned, empty: false };
}

/** Apply siteIds IN-clause onto an audit_events-style SQL fragment. */
export function appendSiteIdsSql(sql, params, siteIds, column = 'site_id') {
  if (!siteIds || siteIds.length === 0) return { sql, params };
  const placeholders = siteIds.map((_, i) => `:scopeSite${i}`).join(', ');
  const nextParams = { ...params };
  siteIds.forEach((id, i) => {
    nextParams[`scopeSite${i}`] = id;
  });
  return {
    sql: `${sql} AND ${column} IN (${placeholders})`,
    params: nextParams,
  };
}
