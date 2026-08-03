import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  assignedSiteIdsForUser,
  badRequest,
  conflict,
  isSiteAssignedToUser,
  lifecycleFromRow,
  newId,
  notFound,
  nowMs,
  writeAudit,
} from '../util.js';
import { issuePairingCode, revokeTerminalSessions } from './pairing.js';
import { cascadeSoftDeleteTerminal } from '../cascadeDelete.js';

const router = Router();

// docs/Key Cabinet Communication Protocol.md §7.1: key node addresses range 1-127.
const nodeCountSchema = z.number().int().min(1).max(127);

/** Same two-layer model as sites.js's office-hours check — see isSiteAssignedToUser's doc.
 * Regional Admin may read/write cabinet-settings only for terminals at their assigned sites. */
async function assertMayAccessCabinetSettings(req, siteId) {
  if (req.auth?.role === 'SUPER_ADMIN') return true;
  if (req.auth?.role === 'REGIONAL_ADMIN') return isSiteAssignedToUser(req.auth.sub, siteId);
  return false;
}

function mapTerminal(row) {
  return {
    id: row.id,
    siteId: row.site_id,
    name: row.name,
    boxAddress: Number(row.box_address),
    serialNumber: row.serial_number,
    configuredSlotCount: Number(row.configured_slot_count),
    cabinetSerialPort: row.cabinet_serial_port,
    cabinetBaudRate: row.cabinet_baud_rate == null ? null : Number(row.cabinet_baud_rate),
    connectionState: row.connection_state,
    vendorDeviceId: row.vendor_device_id,
    nodeRows: row.node_rows == null ? null : Number(row.node_rows),
    nodesPerRow: row.nodes_per_row == null ? null : Number(row.nodes_per_row),
    latitude: row.latitude == null ? null : Number(row.latitude),
    longitude: row.longitude == null ? null : Number(row.longitude),
    paired: Boolean(row.paired),
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

function mapCabinetSettings(row) {
  return {
    terminalId: row.terminal_id,
    takeWarningTimeSeconds: Number(row.take_warning_time_seconds),
    doorCloseWarningTimeSeconds: Number(row.door_close_warning_time_seconds),
    keyReturnCertificationEnabled: Boolean(row.key_return_certification_enabled),
    returnKeyVideoEnabled: Boolean(row.return_key_video_enabled),
    keyRetrievalVideoEnabled: Boolean(row.key_retrieval_video_enabled),
    revision: Number(row.revision),
  };
}

async function insertDefaultCabinetSettings(connOrPool, terminalId, now = nowMs()) {
  await connOrPool.execute(
    `INSERT INTO terminal_cabinet_settings (
       terminal_id, take_warning_time_seconds, door_close_warning_time_seconds,
       key_return_certification_enabled, return_key_video_enabled, key_retrieval_video_enabled,
       revision, updated_at_epoch_ms
     ) VALUES (
       :terminalId, 15, 15, 0, 0, 0, 1, :now
     )
     ON DUPLICATE KEY UPDATE terminal_id = terminal_id`,
    { terminalId, now },
  );
}

async function loadCabinetSettings(terminalId) {
  const [rows] = await pool.execute(
    `SELECT * FROM terminal_cabinet_settings WHERE terminal_id = :terminalId LIMIT 1`,
    { terminalId },
  );
  if (rows[0]) return mapCabinetSettings(rows[0]);
  await insertDefaultCabinetSettings(pool, terminalId);
  const [created] = await pool.execute(
    `SELECT * FROM terminal_cabinet_settings WHERE terminal_id = :terminalId LIMIT 1`,
    { terminalId },
  );
  return mapCabinetSettings(created[0]);
}

export { mapCabinetSettings, loadCabinetSettings, insertDefaultCabinetSettings };

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  let sql = `SELECT * FROM terminals WHERE lifecycle_state = :state`;
  const params = { state };

  // Mobile companion + Regional Admin: only terminals at standing assigned sites.
  const scopedRole =
    req.auth?.role === 'REGIONAL_ADMIN' ||
    req.auth?.role === 'TECHNICIAN' ||
    req.auth?.role === 'VENDOR';
  if (scopedRole) {
    const assignedSiteIds = await assignedSiteIdsForUser(req.auth.sub);
    if (assignedSiteIds.length === 0) return res.json({ items: [] });
    if (siteId && !assignedSiteIds.includes(siteId)) return res.json({ items: [] });
    const scopeIds = siteId ? [siteId] : assignedSiteIds;
    const placeholders = scopeIds.map((_, i) => `:site${i}`).join(', ');
    sql += ` AND site_id IN (${placeholders})`;
    scopeIds.forEach((id, i) => {
      params[`site${i}`] = id;
    });
  } else if (siteId) {
    sql += ` AND site_id = :siteId`;
    params.siteId = siteId;
  }

  sql += ` ORDER BY name ASC`;
  const [rows] = await pool.execute(sql, params);
  res.json({ items: rows.map(mapTerminal) });
});

router.get('/:id', async (req, res) => {
  const [rows] = await pool.execute(`SELECT * FROM terminals WHERE id = :id LIMIT 1`, {
    id: req.params.id,
  });
  if (!rows[0]) return notFound(res, 'Terminal not found');
  const scopedRole =
    req.auth?.role === 'REGIONAL_ADMIN' ||
    req.auth?.role === 'TECHNICIAN' ||
    req.auth?.role === 'VENDOR';
  if (scopedRole && !(await isSiteAssignedToUser(req.auth.sub, rows[0].site_id))) {
    return notFound(res, 'Terminal not found');
  }
  return res.json(mapTerminal(rows[0]));
});

router.get('/:id/cabinet-settings', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT id, site_id FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Terminal not found');
  // Out-of-scope reads as "not found", not "forbidden" — avoids confirming a terminal's
  // existence to a Regional Admin who isn't assigned to its site.
  if (!(await assertMayAccessCabinetSettings(req, existing[0].site_id))) {
    return notFound(res, 'Terminal not found');
  }
  return res.json(await loadCabinetSettings(req.params.id));
});

router.patch('/:id/cabinet-settings', async (req, res) => {
  const schema = z.object({
    takeWarningTimeSeconds: z.number().int().min(1).max(300),
    doorCloseWarningTimeSeconds: z.number().int().min(1).max(300),
    keyReturnCertificationEnabled: z.boolean(),
    returnKeyVideoEnabled: z.boolean(),
    keyRetrievalVideoEnabled: z.boolean(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid cabinet settings update');

  const [existingTerminal] = await pool.execute(
    `SELECT id, site_id FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existingTerminal[0]) return notFound(res, 'Terminal not found');

  if (!(await assertMayAccessCabinetSettings(req, existingTerminal[0].site_id))) {
    return res.status(403).json({ error: 'FORBIDDEN', message: "Not permitted to edit this terminal's cabinet settings" });
  }

  await insertDefaultCabinetSettings(pool, req.params.id);

  const [existing] = await pool.execute(
    `SELECT * FROM terminal_cabinet_settings WHERE terminal_id = :id LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Cabinet settings not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE terminal_cabinet_settings SET
       take_warning_time_seconds = :takeWarningTimeSeconds,
       door_close_warning_time_seconds = :doorCloseWarningTimeSeconds,
       key_return_certification_enabled = :keyReturnCertificationEnabled,
       return_key_video_enabled = :returnKeyVideoEnabled,
       key_retrieval_video_enabled = :keyRetrievalVideoEnabled,
       revision = revision + 1,
       updated_at_epoch_ms = :now
     WHERE terminal_id = :id AND revision = :expectedRevision`,
    {
      id: req.params.id,
      takeWarningTimeSeconds: parsed.data.takeWarningTimeSeconds,
      doorCloseWarningTimeSeconds: parsed.data.doorCloseWarningTimeSeconds,
      keyReturnCertificationEnabled: parsed.data.keyReturnCertificationEnabled ? 1 : 0,
      returnKeyVideoEnabled: parsed.data.returnKeyVideoEnabled ? 1 : 0,
      keyRetrievalVideoEnabled: parsed.data.keyRetrievalVideoEnabled ? 1 : 0,
      expectedRevision: parsed.data.expectedRevision,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  const [rows] = await pool.execute(
    `SELECT * FROM terminal_cabinet_settings WHERE terminal_id = :id LIMIT 1`,
    { id: req.params.id },
  );
  return res.json(mapCabinetSettings(rows[0]));
});

router.post('/', async (req, res) => {
  const schema = z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    boxAddress: z.number().int().positive(),
    serialNumber: z.string().nullable().optional(),
    configuredSlotCount: nodeCountSchema,
    cabinetSerialPort: z.string().nullable().optional(),
    cabinetBaudRate: z.number().int().nullable().optional(),
    vendorDeviceId: z.string().max(255).nullable().optional(),
    nodeRows: z.number().int().positive().nullable().optional(),
    nodesPerRow: z.number().int().positive().nullable().optional(),
    latitude: z.number().min(-90).max(90).nullable().optional(),
    longitude: z.number().min(-180).max(180).nullable().optional(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid terminal payload');

  const [sites] = await pool.execute(
    `SELECT id FROM sites WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: parsed.data.siteId },
  );
  if (!sites[0]) return badRequest(res, 'siteId must reference an active site');

  const id = newId();
  const now = nowMs();
  await pool.execute(
    `INSERT INTO terminals
      (id, site_id, name, box_address, serial_number, configured_slot_count,
       node_rows, nodes_per_row, cabinet_serial_port, cabinet_baud_rate,
       latitude, longitude, connection_state, revision,
       lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
     VALUES
      (:id, :siteId, :name, :boxAddress, :serialNumber, :configuredSlotCount,
       :nodeRows, :nodesPerRow, :cabinetSerialPort, :cabinetBaudRate,
       :latitude, :longitude, 'UNKNOWN', 1,
       'ACTIVE', :now, :now)`,
    {
      id,
      siteId: parsed.data.siteId,
      name: parsed.data.name,
      boxAddress: parsed.data.boxAddress,
      serialNumber: parsed.data.serialNumber ?? null,
      configuredSlotCount: parsed.data.configuredSlotCount,
      nodeRows: parsed.data.nodeRows ?? null,
      nodesPerRow: parsed.data.nodesPerRow ?? null,
      cabinetSerialPort: parsed.data.cabinetSerialPort ?? null,
      cabinetBaudRate: parsed.data.cabinetBaudRate ?? null,
      latitude: parsed.data.latitude ?? null,
      longitude: parsed.data.longitude ?? null,
      now,
    },
  );
  // vendor_device_id has its own column but no ADD-time value from the create schema above
  // was wired into the INSERT — set it in one follow-up UPDATE alongside pairing-code issuance
  // rather than growing the INSERT's column list further for a field that's commonly unknown
  // at registration time and edited in afterward.
  if (parsed.data.vendorDeviceId) {
    await pool.execute(`UPDATE terminals SET vendor_device_id = :vendorDeviceId WHERE id = :id`, {
      id,
      vendorDeviceId: parsed.data.vendorDeviceId,
    });
  }
  await insertDefaultCabinetSettings(pool, id, now);
  await writeAudit({
    eventType: 'TERMINAL_CREATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    terminalId: id,
    entityType: 'TERMINAL',
    entityId: id,
  });

  const pairing = await issuePairingCode(id);
  await writeAudit({
    eventType: 'TERMINAL_PAIRING_CODE_GENERATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    terminalId: id,
    entityType: 'TERMINAL',
    entityId: id,
  });

  const [rows] = await pool.execute(`SELECT * FROM terminals WHERE id = :id`, { id });
  return res.status(201).json({
    terminal: mapTerminal(rows[0]),
    pairingCode: pairing.code,
    pairingCodeExpiresAtEpochMillis: pairing.expiresAtEpochMillis,
  });
});

router.post('/:id/pairing-code', async (req, res) => {
  const [existing] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Terminal not found');

  // Regenerating always revokes the terminal's current session — see the [CONFIRM]
  // recommendation documented on RegeneratePairingCodeResponse in ApiContracts.kt: a lost
  // device, factory reset, or re-pair should never leave the old session usable.
  await revokeTerminalSessions(req.params.id);

  const pairing = await issuePairingCode(req.params.id);
  await writeAudit({
    eventType: 'TERMINAL_PAIRING_CODE_GENERATED',
    actorUserId: req.auth.sub,
    siteId: existing[0].site_id,
    terminalId: req.params.id,
    entityType: 'TERMINAL',
    entityId: req.params.id,
  });

  return res.json({
    terminalId: req.params.id,
    code: pairing.code,
    expiresAtEpochMillis: pairing.expiresAtEpochMillis,
  });
});

router.patch('/:id', async (req, res) => {
  const schema = z.object({
    siteId: z.string().uuid(),
    name: z.string().min(1),
    boxAddress: z.number().int().positive(),
    serialNumber: z.string().nullable().optional(),
    configuredSlotCount: nodeCountSchema,
    cabinetSerialPort: z.string().nullable().optional(),
    cabinetBaudRate: z.number().int().nullable().optional(),
    vendorDeviceId: z.string().max(255).nullable().optional(),
    nodeRows: z.number().int().positive().nullable().optional(),
    nodesPerRow: z.number().int().positive().nullable().optional(),
    latitude: z.number().min(-90).max(90).nullable().optional(),
    longitude: z.number().min(-180).max(180).nullable().optional(),
    expectedRevision: z.number().int().nonnegative(),
  });
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid terminal update');

  const [existing] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Terminal not found');
  if (Number(existing[0].revision) !== parsed.data.expectedRevision) return conflict(res);

  const now = nowMs();
  const [result] = await pool.execute(
    `UPDATE terminals SET
      site_id = :siteId, name = :name, box_address = :boxAddress, serial_number = :serialNumber,
      configured_slot_count = :configuredSlotCount, cabinet_serial_port = :cabinetSerialPort,
      cabinet_baud_rate = :cabinetBaudRate, vendor_device_id = :vendorDeviceId,
      node_rows = :nodeRows, nodes_per_row = :nodesPerRow,
      latitude = :latitude, longitude = :longitude,
      revision = revision + 1, updated_at_epoch_ms = :now
     WHERE id = :id AND revision = :expectedRevision AND lifecycle_state = 'ACTIVE'`,
    {
      id: req.params.id,
      ...parsed.data,
      serialNumber: parsed.data.serialNumber ?? null,
      cabinetSerialPort: parsed.data.cabinetSerialPort ?? null,
      cabinetBaudRate: parsed.data.cabinetBaudRate ?? null,
      vendorDeviceId: parsed.data.vendorDeviceId ?? null,
      nodeRows: parsed.data.nodeRows ?? null,
      nodesPerRow: parsed.data.nodesPerRow ?? null,
      latitude: parsed.data.latitude ?? null,
      longitude: parsed.data.longitude ?? null,
      now,
    },
  );
  if (result.affectedRows === 0) return conflict(res);

  await writeAudit({
    eventType: 'TERMINAL_UPDATED',
    actorUserId: req.auth.sub,
    siteId: parsed.data.siteId,
    terminalId: req.params.id,
    entityType: 'TERMINAL',
    entityId: req.params.id,
  });
  const [rows] = await pool.execute(`SELECT * FROM terminals WHERE id = :id`, { id: req.params.id });
  return res.json(mapTerminal(rows[0]));
});

router.delete('/:id', async (req, res) => {
  const cascade = Boolean(req.body?.cascade);
  const [existing] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Terminal not found');

  if (!cascade) {
    const [[deps]] = await pool.execute(
      `SELECT COUNT(*) AS c FROM key_slots WHERE terminal_id = :id AND lifecycle_state = 'ACTIVE'`,
      { id: req.params.id },
    );
    if (Number(deps.c) > 0) {
      return res.status(409).json({
        error: 'DEPENDENCY_BLOCKED',
        message: 'Terminal has active key slots',
        dependentRecordCount: Number(deps.c),
      });
    }

    const now = nowMs();
    await pool.execute(
      `UPDATE terminals
       SET lifecycle_state = 'RECYCLE_BIN', deleted_at_epoch_ms = :now, deleted_by_user_id = :actor,
           revision = revision + 1, updated_at_epoch_ms = :now
       WHERE id = :id AND lifecycle_state = 'ACTIVE'`,
      { id: req.params.id, now, actor: req.auth.sub },
    );
    await writeAudit({
      eventType: 'RECORD_MOVED_TO_BIN',
      actorUserId: req.auth.sub,
      siteId: existing[0].site_id,
      terminalId: req.params.id,
      entityType: 'TERMINAL',
      entityId: req.params.id,
    });
    const [rows] = await pool.execute(`SELECT * FROM terminals WHERE id = :id`, { id: req.params.id });
    return res.json(mapTerminal(rows[0]));
  }

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const counts = await cascadeSoftDeleteTerminal(conn, {
      terminalId: req.params.id,
      siteId: existing[0].site_id,
      actorUserId: req.auth.sub,
    });
    await conn.commit();
    const [rows] = await pool.execute(`SELECT * FROM terminals WHERE id = :id`, { id: req.params.id });
    return res.json({
      ...mapTerminal(rows[0]),
      cascade: counts,
    });
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
});

export default router;
