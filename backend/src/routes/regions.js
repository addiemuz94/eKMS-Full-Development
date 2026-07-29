import { z } from 'zod';
import { softDeleteRouter } from './phase4.js';

/**
 * Regions (migration 009) — a geographic grouping ABOVE Site, used only to route a Technician/
 * Vendor's key-access request to the right Regional Admin (see key_access_requests). Regions
 * themselves are Super-Admin-managed, same tier as Sites — not exposed to REGIONAL_ADMIN tokens
 * at all in this pass (not present in REGIONAL_ADMIN_ALLOWED_ROUTES in middleware/auth.js), since
 * a Regional Admin's job is to be assigned INTO a region (via user_region_assignments, set
 * through users.js's assignedRegionIds field, mirroring assignedSiteIds), not to create/edit
 * regions. Built on the same generic `softDeleteRouter` factory eventDefinitionsRouter/
 * schedulesRouter/etc. already use in phase4.js, rather than a bespoke CRUD file — regions has no
 * siteId column so it doesn't fit `namedGroupRouter`'s assumed (siteId, name, code) shape, but it
 * fits this lower-level factory directly.
 */
const regionsRouter = softDeleteRouter({
  table: 'regions',
  entityType: 'REGION',
  mapRow: (row) => ({
    id: row.id,
    name: row.name,
    displayOrder: Number(row.display_order),
    maxKeyAccessDurationMinutes: Number(row.max_key_access_duration_minutes),
    revision: Number(row.revision),
  }),
  createSchema: z.object({
    name: z.string().min(1),
    displayOrder: z.number().int().default(0),
    maxKeyAccessDurationMinutes: z.number().int().positive().default(1440),
  }),
  updateSchema: z.object({
    name: z.string().min(1),
    displayOrder: z.number().int().default(0),
    maxKeyAccessDurationMinutes: z.number().int().positive().default(1440),
    expectedRevision: z.number().int().nonnegative(),
  }),
  insertSql: `INSERT INTO regions
    (id, name, display_order, max_key_access_duration_minutes, revision, lifecycle_state,
     created_at_epoch_ms, updated_at_epoch_ms)
   VALUES (:id, :name, :displayOrder, :maxKeyAccessDurationMinutes, 1, 'ACTIVE', :now, :now)`,
  updateSql: `UPDATE regions
   SET name = :name, display_order = :displayOrder,
       max_key_access_duration_minutes = :maxKeyAccessDurationMinutes,
       revision = revision + 1, updated_at_epoch_ms = :now
   WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
});

export default regionsRouter;
