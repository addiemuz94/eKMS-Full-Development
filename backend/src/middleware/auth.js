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
 */
const TERMINAL_DEVICE_ALLOWED_ROUTES = [
  { method: 'GET', pattern: /^\/sites$/ },
  { method: 'GET', pattern: /^\/users$/ },
  { method: 'POST', pattern: /^\/users$/ },
  { method: 'GET', pattern: /^\/terminals\/[^/]+$/ },
  { method: 'GET', pattern: /^\/users\/[^/]+\/credentials$/ },
  { method: 'POST', pattern: /^\/users\/[^/]+\/credentials\/complete$/ },
  { method: 'POST', pattern: /^\/users\/[^/]+\/credentials\/revoke$/ },
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
 * - All terminal/site CRUD, MAC address, server address, activation code, and everything else
 *   marked Hidden/Super-Admin-only in the matrix.
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
  // Vendor passkey requests: list/get/create/approve/reject.
  { method: 'GET', pattern: /^\/vendor-passkey-requests$/ },
  { method: 'GET', pattern: /^\/vendor-passkey-requests\/[^/]+$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests\/[^/]+\/approve$/ },
  { method: 'POST', pattern: /^\/vendor-passkey-requests\/[^/]+\/reject$/ },
];

/**
 * Replaces `requireSuperAdmin` ONLY at the `/v1/admin` router's mount point. Real Super
 * Admin users pass through exactly as before (unconditional, unchanged). A TERMINAL_DEVICE
 * token only passes for the exact routes in TERMINAL_DEVICE_ALLOWED_ROUTES; a REGIONAL_ADMIN
 * token only passes for the exact routes in REGIONAL_ADMIN_ALLOWED_ROUTES. Everything else
 * under `/v1/admin` — and all of `/v1/audit` and `/v1/reports`, which still use the plain
 * `requireSuperAdmin` — remains 403 for both, same least-privilege intent as today.
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
