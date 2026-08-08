import { Router } from 'express';
import { z } from 'zod';
import { requireSuperAdmin } from '../middleware/auth.js';
import {
  EDITABLE_ROLES,
  ROLE_CEILINGS,
  enabledKeysForRole,
  invalidateCapabilityCache,
  matrixForResponse,
  refreshCapabilityCache,
} from '../roleCapabilitiesCatalog.js';
import { nowMs, writeAudit } from '../util.js';
import pool from '../db.js';

const router = Router();

function badRequest(res, message) {
  return res.status(400).json({ error: 'BAD_REQUEST', message });
}

/** Authenticated caller (any allowlisted role) — enabled capability keys for the signed-in role. */
router.get('/me', async (req, res) => {
  const role = req.auth?.role || '';
  const capabilities = await enabledKeysForRole(role);
  return res.json({ role, capabilities });
});

/** Super Admin — full catalog + matrix for the permission page. */
router.get('/', requireSuperAdmin, async (_req, res) => {
  return res.json(await matrixForResponse());
});

const putSchema = z.object({
  role: z.enum(['REGIONAL_ADMIN', 'TECHNICIAN', 'VENDOR']),
  capabilities: z.record(z.boolean()),
});

/** Super Admin — replace enabled flags for one editable role (ceiling keys only). */
router.put('/', requireSuperAdmin, async (req, res) => {
  const parsed = putSchema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid role-capabilities payload');

  const { role, capabilities } = parsed.data;
  if (!EDITABLE_ROLES.includes(role)) {
    return res.status(403).json({ error: 'FORBIDDEN', message: 'Role is not editable' });
  }

  const ceiling = ROLE_CEILINGS[role];
  const unknown = Object.keys(capabilities).filter((k) => !ceiling.includes(k));
  if (unknown.length > 0) {
    return badRequest(
      res,
      `Capabilities outside this role's ceiling: ${unknown.join(', ')}`,
    );
  }

  const now = nowMs();
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    for (const key of ceiling) {
      if (!Object.prototype.hasOwnProperty.call(capabilities, key)) continue;
      const enabled = capabilities[key] ? 1 : 0;
      await conn.execute(
        `INSERT INTO role_capabilities (role, capability_key, enabled, updated_at_epoch_ms)
         VALUES (:role, :capabilityKey, :enabled, :now)
         ON DUPLICATE KEY UPDATE enabled = :enabled, updated_at_epoch_ms = :now`,
        { role, capabilityKey: key, enabled, now },
      );
    }
    await writeAudit({
      eventType: 'ROLE_CAPABILITIES_UPDATED',
      actorUserId: req.auth.sub,
      entityType: 'ROLE_CAPABILITIES',
      entityId: role,
      detail: JSON.stringify({ role, capabilities }),
      conn,
    });
    await conn.commit();
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }

  invalidateCapabilityCache();
  await refreshCapabilityCache();
  return res.json(await matrixForResponse());
});

export default router;
