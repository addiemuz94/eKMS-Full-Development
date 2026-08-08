/**
 * Curated role-capability catalog for the Super Admin permission matrix.
 * Capabilities only narrow each role's hardcoded allowlist ceiling — never widen it.
 */
import pool from './db.js';

export const EDITABLE_ROLES = Object.freeze(['REGIONAL_ADMIN', 'TECHNICIAN', 'VENDOR']);

/** Full catalog shown on the matrix page (including SA-only locked rows). */
export const CAPABILITY_CATALOG = Object.freeze([
  {
    key: 'portal.cabinet_management',
    label: 'Cabinet Management',
    description: 'Browse locations and cabinets in the portal',
    group: 'Portal',
  },
  {
    key: 'portal.activity_report',
    label: 'Activity Report / audit',
    description: 'Activity Report hub and audit event lists',
    group: 'Portal',
  },
  {
    key: 'portal.logs',
    label: 'Operational logs',
    description: 'Pickup & Return, Operation, System, and Equipment logs',
    group: 'Portal',
  },
  {
    key: 'cabinet.timers',
    label: 'Timers & video',
    description: 'Cabinet Management → Timers & video tab',
    group: 'Cabinet tabs',
  },
  {
    key: 'cabinet.keys',
    label: 'Keys',
    description: 'Cabinet Management → Keys tab',
    group: 'Cabinet tabs',
  },
  {
    key: 'cabinet.key_permission',
    label: 'Key Permission',
    description: 'Cabinet Management → Key Permission tab',
    group: 'Cabinet tabs',
  },
  {
    key: 'cabinet.key_access',
    label: 'Key Access',
    description: 'Key Access requests (approve/reject for RA; apply/own for Tech/Vendor)',
    group: 'Cabinet tabs',
  },
  {
    key: 'api.cabinet_settings',
    label: 'Cabinet settings API',
    description: 'Read/update take/return timers and video toggles',
    group: 'API',
  },
  {
    key: 'api.office_hours',
    label: 'Office hours API',
    description: 'Read/update site office hours',
    group: 'API',
  },
  {
    key: 'api.access_grants',
    label: 'Access grants API',
    description: 'List/create/update key permissions',
    group: 'API',
  },
  {
    key: 'api.key_access_requests',
    label: 'Key-access requests API',
    description: 'Key Access / passkey request endpoints for that role',
    group: 'API',
  },
  {
    key: 'api.notifications',
    label: 'Notifications',
    description: 'Checkout-deadline SSE (RA) or FCM token registration',
    group: 'API',
  },
  // Locked SA-only rows (shown greyed for non-SA — never toggleable).
  {
    key: 'portal.registration',
    label: 'Registration',
    description: 'Location / cabinet registration wizard',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
  {
    key: 'portal.user_management',
    label: 'User Management',
    description: 'Create and manage personnel accounts',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
  {
    key: 'portal.deleted_items',
    label: 'Deleted items',
    description: 'Recycle Bin restore / purge',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
  {
    key: 'portal.erase_data',
    label: 'Erase data',
    description: 'Permanent flush by scope',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
  {
    key: 'cabinet.identity',
    label: 'Cabinet / Location identity',
    description: 'Cabinet Management → Cabinet and Location tabs',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
  {
    key: 'cabinet.assign_user',
    label: 'Assign User',
    description: 'Cabinet Management → Assign User tab',
    group: 'Super Admin only',
    superAdminOnly: true,
  },
]);

/** Keys each editable role may toggle (must stay inside auth.js allowlist ceiling). */
export const ROLE_CEILINGS = Object.freeze({
  REGIONAL_ADMIN: Object.freeze([
    'portal.cabinet_management',
    'portal.activity_report',
    'portal.logs',
    'cabinet.timers',
    'cabinet.keys',
    'cabinet.key_permission',
    'cabinet.key_access',
    'api.cabinet_settings',
    'api.office_hours',
    'api.access_grants',
    'api.key_access_requests',
    'api.notifications',
  ]),
  TECHNICIAN: Object.freeze([
    'portal.cabinet_management',
    'cabinet.key_access',
    'api.key_access_requests',
    'api.notifications',
  ]),
  VENDOR: Object.freeze([
    'portal.cabinet_management',
    'cabinet.key_access',
    'api.key_access_requests',
    'api.notifications',
  ]),
});

/**
 * Map (method, path-inside-/v1/admin) → capability required after allowlist match.
 * First matching pattern wins.
 */
const ADMIN_ROUTE_CAPABILITIES = [
  { method: 'GET', pattern: /^\/role-capabilities\/me$/, capability: null }, // always allowed if on allowlist
  { method: null, pattern: /^\/terminals\/[^/]+\/cabinet-settings$/, capability: 'api.cabinet_settings' },
  { method: null, pattern: /^\/sites\/[^/]+\/office-hours$/, capability: 'api.office_hours' },
  { method: null, pattern: /^\/access-grants(\/|$)/, capability: 'api.access_grants' },
  { method: null, pattern: /^\/vendor-passkey-requests(\/|$)/, capability: 'api.key_access_requests' },
  { method: null, pattern: /^\/key-access-requests(\/|$)/, capability: 'api.key_access_requests' },
  { method: 'POST', pattern: /^\/mobile-push-tokens$/, capability: 'api.notifications' },
  { method: null, pattern: /^\/keys(\/|$)/, capability: 'cabinet.keys' },
  { method: null, pattern: /^\/key-slots(\/|$)/, capability: 'cabinet.keys' },
  { method: null, pattern: /^\/users(\/|$)/, capability: 'portal.cabinet_management' },
  { method: null, pattern: /^\/sites(\/|$)/, capability: 'portal.cabinet_management' },
  { method: null, pattern: /^\/terminals(\/|$)/, capability: 'portal.cabinet_management' },
];

/** Tech/Vendor keys/access-grants reads support Key Access apply — use key_access_requests. */
const TECH_VENDOR_ROUTE_OVERRIDES = [
  { method: null, pattern: /^\/keys(\/|$)/, capability: 'api.key_access_requests' },
  { method: null, pattern: /^\/access-grants(\/|$)/, capability: 'api.key_access_requests' },
];

let cacheByRole = null;

export function invalidateCapabilityCache() {
  cacheByRole = null;
}

export async function refreshCapabilityCache() {
  const [rows] = await pool.execute(
    `SELECT role, capability_key, enabled FROM role_capabilities`,
  );
  const next = new Map();
  for (const row of rows) {
    if (!next.has(row.role)) next.set(row.role, new Map());
    next.get(row.role).set(row.capability_key, Number(row.enabled) === 1);
  }
  cacheByRole = next;
  return cacheByRole;
}

async function ensureCache() {
  if (!cacheByRole) await refreshCapabilityCache();
  return cacheByRole;
}

/**
 * @returns {Promise<boolean>}
 */
export async function isCapabilityEnabled(role, capabilityKey) {
  if (!role || role === 'SUPER_ADMIN') return true;
  if (!capabilityKey) return true;
  const ceiling = ROLE_CEILINGS[role];
  if (!ceiling) return true; // TERMINAL_DEVICE / GOD_ADMIN — no capability layer
  if (!ceiling.includes(capabilityKey)) {
    // Outside ceiling = not grantable; treat as disabled for this check
    return false;
  }
  const cache = await ensureCache();
  const roleMap = cache.get(role);
  if (!roleMap || !roleMap.has(capabilityKey)) {
    // Missing seed row: fail open to historical default (enabled)
    return true;
  }
  return roleMap.get(capabilityKey) === true;
}

export function capabilityForAdminRoute(role, method, path) {
  const overrides =
    role === 'TECHNICIAN' || role === 'VENDOR' ? TECH_VENDOR_ROUTE_OVERRIDES : [];
  for (const entry of [...overrides, ...ADMIN_ROUTE_CAPABILITIES]) {
    if (entry.method && entry.method !== method) continue;
    if (entry.pattern.test(path)) return entry.capability;
  }
  return null;
}

/** Capability for /v1/audit and /v1/reports mounts. */
export function capabilityForAuditOrReports(pathPrefix, reqPath) {
  // pathPrefix is 'audit' or 'reports'; reqPath is inside that router
  if (pathPrefix === 'audit') return 'portal.activity_report';
  if (pathPrefix === 'reports') {
    if (/^\/activity-|^\/exports$/.test(reqPath) || reqPath === '/activity-summary' || reqPath === '/activity-logs') {
      return 'portal.activity_report';
    }
    return 'portal.logs';
  }
  return null;
}

export async function matrixForResponse() {
  const cache = await ensureCache();
  const byRole = {};
  for (const role of EDITABLE_ROLES) {
    const ceiling = ROLE_CEILINGS[role];
    const roleMap = cache.get(role) || new Map();
    const caps = {};
    for (const key of ceiling) {
      caps[key] = roleMap.has(key) ? roleMap.get(key) === true : true;
    }
    byRole[role] = caps;
  }
  return {
    catalog: CAPABILITY_CATALOG,
    editableRoles: EDITABLE_ROLES,
    ceilings: ROLE_CEILINGS,
    matrix: byRole,
  };
}

export async function enabledKeysForRole(role) {
  if (role === 'SUPER_ADMIN') {
    return CAPABILITY_CATALOG.map((c) => c.key);
  }
  const ceiling = ROLE_CEILINGS[role];
  if (!ceiling) return [];
  const enabled = [];
  for (const key of ceiling) {
    if (await isCapabilityEnabled(role, key)) enabled.push(key);
  }
  return enabled;
}
