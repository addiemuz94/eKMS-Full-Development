import crypto from 'crypto';
import jwt from 'jsonwebtoken';

export function requireAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const [scheme, token] = header.split(' ');
  if (scheme !== 'Bearer' || !token) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Missing Bearer token' });
  }
  try {
    const payload = jwt.verify(token, process.env.JWT_ACCESS_SECRET);
    req.auth = payload;
    return next();
  } catch {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired access token' });
  }
}

export function requireSuperAdmin(req, res, next) {
  if (!req.auth || req.auth.role !== 'SUPER_ADMIN') {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Super Admin required' });
  }
  return next();
}

/**
 * Exact (method, path) allowlist for TERMINAL_DEVICE-scoped tokens, mounted under
 * `/v1/admin`. `path` is matched against `req.path` INSIDE the `admin` router — i.e.
 * already stripped of the `/v1/admin` prefix — so `/users` here means `/v1/admin/users`.
 *
 * This list is deliberately exhaustive and was derived by reading every call site in
 * terminalApp/src/main/java/com/ekms/terminal/data/TerminalApiClient.kt, not assumed.
 * Do not widen it (e.g. to a whole sub-router) without re-checking that file — a
 * TERMINAL_DEVICE token must never reach site/terminal/key CRUD, permissions, recycle
 * bin, sync-conflict resolution, or any other admin surface beyond exactly this set.
 *
 * Phase 5 addition: the original `key_checkouts` work (Phase 1) described this table as
 * "TERMINAL_DEVICE- and SUPER_ADMIN-only reachable" in its own comments, but never actually
 * added an allowlist entry — confirmed by reading this file before touching it, not assumed.
 * Added exactly what Phase 5's terminal-side checkout sync calls (create on take, close-out on
 * return, office-hours read to compute the deadline) — no GET/list on key-checkouts, the
 * terminal never needs to list them, only create/close the ones it creates itself.
 */
const TERMINAL_DEVICE_ALLOWED_ROUTES = [
  { method: 'GET', pattern: /^\/sites$/ },
  { method: 'GET', pattern: /^\/users$/ },
  { method: 'POST', pattern: /^\/users$/ },
  { method: 'GET', pattern: /^\/terminals\/[^/]+$/ },
  { method: 'GET', pattern: /^\/users\/[^/]+\/credentials$/ },
  { method: 'POST', pattern: /^\/users\/[^/]+\/credentials\/complete$/ },
  { method: 'POST', pattern: /^\/users\/[^/]+\/credentials\/revoke$/ },
  { method: 'GET', pattern: /^\/sites\/[^/]+\/office-hours$/ },
  { method: 'POST', pattern: /^\/key-checkouts$/ },
  { method: 'PATCH', pattern: /^\/key-checkouts\/[^/]+$/ },
  // Background auto-scan-and-save (Key Attachment): reports an opaque fob enrollment reference
  // for a key already assigned a KeySlot — never a raw NFC UID. Mirrors the personnel
  // credentials-complete entry above; no GET/list on keys itself, the terminal only ever reports
  // completion for a key it already knows about from its own synced KeySlot snapshot.
  { method: 'POST', pattern: /^\/keys\/[^/]+\/fob-enrollment\/complete$/ },
];

/**
 * Exact (method, path) allowlist for REGIONAL_ADMIN-scoped real users, mounted under
 * `/v1/admin` — same structure and same "exhaustive, not assumed" discipline as
 * TERMINAL_DEVICE_ALLOWED_ROUTES above. Derived from the confirmed 17-item Admin Menu
 * permission matrix's Regional Admin "Edit" column, by reading the actual route files
 * (terminals.js's cabinet-settings routes, sites.js's office-hours routes, accessGrants.js,
 * vendorPasskeyRequests.js) rather than inventing paths.
 *
 * This allowlist controls *which routes* a Regional Admin token can reach at all — it does
 * NOT by itself scope them to their own assigned sites. Every route below additionally
 * enforces its own site-membership check in-handler (`isSiteAssignedToUser`/
 * `assignedSiteIdsForUser` in util.js, wrapped per-file as `assertMayAccess*`) against
 * `user_site_assignments`; both layers are required together, neither is sufficient alone.
 * Do not widen this list without re-deriving it from the permission matrix.
 *
 * Deliberately EXCLUDES:
 * - `/users` (personnel record CRUD) — the matrix's "Personnel/key access-grant management"
 *   item was interpreted as grants only, not personnel records themselves (personnel
 *   registration stays a Super-Admin-driven wizard step). Flagged for confirmation — widen
 *   this if that interpretation is wrong.
 * - `DELETE /access-grants/:id` — the matrix only calls for list/create/update.
 * - `/key-checkouts` (a View-only matrix item, not Edit, and a separate not-yet-made decision).
 * - Site/terminal CRUD (create/update/delete), MAC address, server address, activation code, and
 *   everything else marked Hidden/Super-Admin-only in the matrix. `GET /sites` and
 *   `GET /sites/:id` ARE included (added in the item-14 site-name follow-up) — but read-only,
 *   list/get only; site create/update/delete stay excluded.
 */
const REGIONAL_ADMIN_ALLOWED_ROUTES = [
  // Terminal cabinet behavioral settings — Key node setting, Key Return Certification, return/
  // retrieval video toggles, Take Warning Time, Key Return (Door-Close) Warning Time all live on
  // this one route (terminal_cabinet_settings, migration 007).
  { method: 'GET', pattern: /^\/terminals\/[^/]+\/cabinet-settings$/ },
  { method: 'PATCH', pattern: /^\/terminals\/[^/]+\/cabinet-settings$/ },
  // Per-site office hours.
  { method: 'GET', pattern: /^\/sites\/[^/]+\/office-hours$/ },
  { method: 'PATCH', pattern: /^\/sites\/[^/]+\/office-hours$/ },
  // Access grants: list/get/create/update only, not delete.
  { method: 'GET', pattern: /^\/access-grants$/ },
  { method: 'GET', pattern: /^\/access-grants\/[^/]+$/ },
  { method: 'POST', pattern: /^\/access-grants$/ },
  { method: 'PATCH', pattern: /^\/access-grants\/[^/]+$/ },
  // Vendor passkey requests: list/get/create/approve/reject. Kept unchanged — still the live
  // route backing terminalApp's deployed Phase 7 Vendor Passkey screen.
  { method: 'GET', pattern: /^\/vendor-passkey-requests$/ },
  { method: 'GET', pattern: /^\/vendor-passkey-requests\/[^/]+$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests\/[^/]+\/approve$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests\/[^/]+\/reject$/ },
  // Key access requests (migration 009) — an ADDITIVE, more general mechanism alongside the
  // vendor-passkey-requests above, not a replacement (see the design-choice note at the top of
  // 009_regions_and_key_access_requests.sql): list/get/create/approve/reject.
  // Route-reachability only — keyAccessRequests.js additionally scopes every one of these to
  // the Regional Admin's own assigned REGIONS (via each request's site's region_id), not
  // assigned Sites, per isRegionAssignedToUser in util.js.
  { method: 'GET', pattern: /^\/key-access-requests$/ },
  { method: 'GET', pattern: /^\/key-access-requests\/[^/]+$/ },
  { method: 'POST', pattern: /^\/key-access-requests$/ },
  { method: 'POST', pattern: /^\/key-access-requests\/[^/]+\/approve$/ },
  { method: 'POST', pattern: /^\/key-access-requests\/[^/]+\/reject$/ },
  { method: 'POST', pattern: /^\/key-access-requests\/[^/]+\/revoke$/ },
  // Sites: read-only (list/get), added for the terminal's item-14 site-name display — NOT
  // create/update/delete. sites.js's own handlers additionally scope both of these to the
  // Regional Admin's own assigned sites; this allowlist only controls reachability.
  { method: 'GET', pattern: /^\/sites$/ },
  { method: 'GET', pattern: /^\/sites\/[^/]+$/ },
  // Terminals: read-only list/get for mobile companion Terminals tab. terminals.js scopes
  // list rows to the Regional Admin's assigned sites.
  { method: 'GET', pattern: /^\/terminals$/ },
  { method: 'GET', pattern: /^\/terminals\/[^/]+$/ },
];

/**
 * Exact (method, path) allowlist for TECHNICIAN/VENDOR-scoped real users, mounted under
 * `/v1/admin` — same structure/discipline as the other two allowlists above. Added for
 * migration 009 (regions + generalized key-access-requests): a Technician/Vendor now files
 * their own key-access request through mobileApp's (not-yet-built, per that pass's own scope)
 * request form, so their token needs to reach exactly this one route family — nothing else
 * under `/v1/admin`. Row-level scoping (a Technician/Vendor may only see/create their OWN
 * requests, never someone else's, and can never approve/reject) is enforced in
 * keyAccessRequests.js itself, not here — same two-layer split as every other role.
 */
const TECHNICIAN_VENDOR_ALLOWED_ROUTES = [
  { method: 'GET', pattern: /^\/key-access-requests\/site-policy\/[^/]+$/ },
  { method: 'GET', pattern: /^\/key-access-requests\/exception-sites$/ },
  { method: 'GET', pattern: /^\/key-access-requests\/exception-sites\/[^/]+\/keys$/ },
  { method: 'GET', pattern: /^\/key-access-requests$/ },
  { method: 'GET', pattern: /^\/key-access-requests\/[^/]+$/ },
  { method: 'POST', pattern: /^\/key-access-requests$/ },
  { method: 'GET', pattern: /^\/keys$/ },
  { method: 'GET', pattern: /^\/access-grants$/ },
  // Sites + terminals read-only for mobile companion Overview/Terminals (scoped to standing
  // user_site_assignments in sites.js / terminals.js).
  { method: 'GET', pattern: /^\/sites$/ },
  { method: 'GET', pattern: /^\/sites\/[^/]+$/ },
  { method: 'GET', pattern: /^\/terminals$/ },
  { method: 'GET', pattern: /^\/terminals\/[^/]+$/ },
];

/**
 * Replaces `requireSuperAdmin` ONLY at the `/v1/admin` router's mount point. Real Super
 * Admin users pass through exactly as before (unconditional, unchanged). A TERMINAL_DEVICE
 * token only passes for the exact routes in TERMINAL_DEVICE_ALLOWED_ROUTES; a REGIONAL_ADMIN
 * token only passes for the exact routes in REGIONAL_ADMIN_ALLOWED_ROUTES; a TECHNICIAN or
 * VENDOR token only passes for the exact routes in TECHNICIAN_VENDOR_ALLOWED_ROUTES. Everything
 * else under `/v1/admin` — and all of `/v1/audit` and `/v1/reports`, which still use the plain
 * `requireSuperAdmin` — remains 403, same least-privilege intent as today.
 */
export function requireSuperAdminOrAllowlistedRole(req, res, next) {
  if (req.auth?.role === 'SUPER_ADMIN') {
    return next();
  }
  if (req.auth?.role === 'TERMINAL_DEVICE') {
    const allowed = TERMINAL_DEVICE_ALLOWED_ROUTES.some(
      (route) => route.method === req.method && route.pattern.test(req.path),
    );
    if (allowed) {
      return next();
    }
  }
  if (req.auth?.role === 'REGIONAL_ADMIN') {
    const allowed = REGIONAL_ADMIN_ALLOWED_ROUTES.some(
      (route) => route.method === req.method && route.pattern.test(req.path),
    );
    if (allowed) {
      return next();
    }
  }
  if (req.auth?.role === 'TECHNICIAN' || req.auth?.role === 'VENDOR') {
    const allowed = TECHNICIAN_VENDOR_ALLOWED_ROUTES.some(
      (route) => route.method === req.method && route.pattern.test(req.path),
    );
    if (allowed) {
      return next();
    }
  }
  return res.status(403).json({ error: 'FORBIDDEN', message: 'Super Admin required' });
}

export function signAccessToken(user) {
  return jwt.sign(
    {
      sub: user.id,
      role: user.role,
      email: user.email,
      displayName: user.display_name || user.displayName,
    },
    process.env.JWT_ACCESS_SECRET,
    { expiresIn: Number(process.env.JWT_ACCESS_TTL_SECONDS || 3600) },
  );
}

export function signRefreshToken(user) {
  return jwt.sign(
    { sub: user.id, typ: 'refresh' },
    process.env.JWT_REFRESH_SECRET,
    { expiresIn: Number(process.env.JWT_REFRESH_TTL_SECONDS || 604800) },
  );
}

export function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export function verifyRefreshToken(token) {
  return jwt.verify(token, process.env.JWT_REFRESH_SECRET);
}

/**
 * TERMINAL_DEVICE-scoped tokens (see requireSuperAdminOrAllowlistedRole above).
 * `sub` is a terminals.id, never a users.id — routes that read `req.auth.sub` expecting a
 * user (e.g. audit `actorUserId`) must check `req.auth.role` first. Same secret/algorithm
 * as user tokens, so `requireAuth`/`verifyRefreshToken` need no changes to accept these —
 * only the claim shape differs.
 */
export function signTerminalAccessToken(terminal) {
  return jwt.sign(
    { sub: terminal.id, role: 'TERMINAL_DEVICE' },
    process.env.JWT_ACCESS_SECRET,
    { expiresIn: Number(process.env.JWT_ACCESS_TTL_SECONDS || 3600) },
  );
}

export function signTerminalRefreshToken(terminal) {
  return jwt.sign(
    { sub: terminal.id, typ: 'refresh', role: 'TERMINAL_DEVICE' },
    process.env.JWT_REFRESH_SECRET,
    { expiresIn: Number(process.env.JWT_REFRESH_TTL_SECONDS || 604800) },
  );
}

/**
 * KEY_ACCESS_SESSION-scoped token, issued by `POST /v1/terminal/passkey-login`
 * (keyAccessRequests.js) once a submitted 4-digit passkey validates against an approved,
 * not-yet-expired `key_access_requests` row. `sub` is the requester's own `users.id` (not a
 * terminal id, unlike the TERMINAL_DEVICE tokens above) — the session represents "this specific
 * person, admitted to exactly these keys, until this specific time," not a device identity.
 * `keyIds`/`siteId` are carried as claims so a future consumer of this token (terminalApp's own
 * take/return flow) can check "is the key this operator is trying to take one of the ids on
 * their own token" without a second database round-trip. No refresh token exists for this
 * session type by design — it expires with the approval window and is not meant to be renewed;
 * a new passkey-login (or a fresh key-access-request) is required after expiry, not a refresh.
 *
 * Deliberately NOT added to any `*_ALLOWED_ROUTES` allowlist yet — nothing in this pass consumes
 * this token against `/v1/admin/*` (terminalApp's UI wiring for passkey login is separate,
 * explicitly deferred follow-up work; see keyAccessRequests.js's `passkeyLogin` doc). A token of
 * this role decodes successfully via `requireAuth` (same secret/algorithm as every other token)
 * but 403s under `requireSuperAdminOrAllowlistedRole` until a future pass defines what it may do.
 */
export function signKeyAccessSessionToken(request, keyIds) {
  const expiresInSeconds = Math.max(
    1,
    Math.floor((Number(request.passkey_expires_at_epoch_ms) - Date.now()) / 1000),
  );
  return jwt.sign(
    {
      sub: request.requester_user_id,
      role: 'KEY_ACCESS_SESSION',
      keyAccessRequestId: request.id,
      siteId: request.site_id,
      keyIds,
    },
    process.env.JWT_ACCESS_SECRET,
    { expiresIn: expiresInSeconds },
  );
}
