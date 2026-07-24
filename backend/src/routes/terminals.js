import { Router } from 'express';
import { z } from 'zod';
import pool from '../db.js';
import {
  badRequest,
  conflict,
  lifecycleFromRow,
  newId,
  notFound,
  nowMs,
  writeAudit,
} from '../util.js';
import { issuePairingCode, revokeTerminalSessions } from './pairing.js';

const router = Router();

// docs/Key Cabinet Communication Protocol.md §7.1: key node addresses range 1-127.
const nodeCountSchema = z.number().int().min(1).max(127);

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

router.get('/', async (req, res) => {
  const state = req.query.state || 'ACTIVE';
  const siteId = req.query.siteId;
  let sql = `SELECT * FROM terminals WHERE lifecycle_state = :state`;
  const params = { state };
  if (siteId) {
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
  return res.json(mapTerminal(rows[0]));
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
  const [existing] = await pool.execute(
    `SELECT * FROM terminals WHERE id = :id AND lifecycle_state = 'ACTIVE' LIMIT 1`,
    { id: req.params.id },
  );
  if (!existing[0]) return notFound(res, 'Terminal not found');

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
});

export default router;
