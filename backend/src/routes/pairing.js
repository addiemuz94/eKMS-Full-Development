import crypto from 'crypto';
import { z } from 'zod';
import pool from '../db.js';
import { hashToken, signTerminalAccessToken, signTerminalRefreshToken } from '../middleware/auth.js';
import { badRequest, newId, nowMs, writeAudit } from '../util.js';

const PAIRING_CODE_TTL_MS = 30 * 60 * 1000; // 30 minutes
const RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const RATE_LIMIT_MAX_FAILURES = 10;

function generateSixDigitCode() {
  // crypto.randomInt is uniform over the range (no modulo bias), unlike Math.random().
  return String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
}

function hashPairingCode(code) {
  return hashToken(code);
}

export function mapTerminal(row) {
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
    lifecycle: {
      state: row.lifecycle_state,
      createdAtEpochMillis: Number(row.created_at_epoch_ms),
      updatedAtEpochMillis: Number(row.updated_at_epoch_ms),
      deletedAtEpochMillis: row.deleted_at_epoch_ms == null ? null : Number(row.deleted_at_epoch_ms),
      deletedByUserId: row.deleted_by_user_id || null,
    },
  };
}

/**
 * Generates a fresh pairing code for `terminalId`, overwriting any existing one (a terminal
 * has at most one active code at a time). Used by both `POST /v1/admin/terminals` (create)
 * and `POST /v1/admin/terminals/{id}/pairing-code` (regenerate) in terminals.js. Returns the
 * PLAINTEXT code — callers must include it in their response and never store it themselves;
 * only the hash persists.
 */
export async function issuePairingCode(terminalId, { conn = pool } = {}) {
  const code = generateSixDigitCode();
  const expiresAt = nowMs() + PAIRING_CODE_TTL_MS;
  await conn.execute(
    `UPDATE terminals
     SET pairing_code_hash = :hash,
         pairing_code_expires_at_epoch_ms = :expiresAt,
         pairing_code_consumed_at_epoch_ms = NULL
     WHERE id = :id`,
    { id: terminalId, hash: hashPairingCode(code), expiresAt },
  );
  return { code, expiresAtEpochMillis: expiresAt };
}

/**
 * Revokes every TERMINAL_DEVICE refresh token issued to `terminalId`. Called when a Super
 * Admin regenerates a pairing code (see the [CONFIRM] recommendation in ApiContracts.kt's
 * RegeneratePairingCodeResponse doc) so a lost/reset device's old session cannot keep
 * syncing after a new code is issued for a replacement device.
 */
export async function revokeTerminalSessions(terminalId, { conn = pool } = {}) {
  await conn.execute(
    `UPDATE terminal_refresh_tokens SET revoked = 1 WHERE terminal_id = :terminalId AND revoked = 0`,
    { terminalId },
  );
}

async function recordPairingAttempt(ipAddress, succeeded) {
  await pool.execute(
    `INSERT INTO pairing_attempts (id, ip_address, succeeded, attempted_at_epoch_ms)
     VALUES (:id, :ipAddress, :succeeded, :now)`,
    { id: newId(), ipAddress, succeeded: succeeded ? 1 : 0, now: nowMs() },
  );
}

async function isRateLimited(ipAddress) {
  const [rows] = await pool.execute(
    `SELECT COUNT(*) AS c FROM pairing_attempts
     WHERE ip_address = :ipAddress AND succeeded = 0 AND attempted_at_epoch_ms > :windowStart`,
    { ipAddress, windowStart: nowMs() - RATE_LIMIT_WINDOW_MS },
  );
  return Number(rows[0].c) >= RATE_LIMIT_MAX_FAILURES;
}

const pairWithCodeSchema = z.object({
  code: z.string().regex(/^\d{6}$/, 'code must be exactly 6 digits'),
});

/**
 * POST /v1/terminal/pair-with-code — unauthenticated by necessity (a fresh terminal has no
 * token yet). See TerminalPairWithCodeRequest's doc in ApiContracts.kt for the full flow.
 */
export async function pairWithCode(req, res) {
  const ipAddress = req.ip || 'unknown';

  if (await isRateLimited(ipAddress)) {
    return res.status(429).json({
      error: 'RATE_LIMITED',
      message: 'Too many failed pairing attempts. Try again later.',
    });
  }

  const parsed = pairWithCodeSchema.safeParse(req.body);
  if (!parsed.success) {
    await recordPairingAttempt(ipAddress, false);
    return badRequest(res, 'code must be exactly 6 digits');
  }

  const hash = hashPairingCode(parsed.data.code);
  const now = nowMs();
  const [rows] = await pool.execute(
    `SELECT * FROM terminals
     WHERE pairing_code_hash = :hash
       AND pairing_code_consumed_at_epoch_ms IS NULL
       AND pairing_code_expires_at_epoch_ms > :now
       AND lifecycle_state = 'ACTIVE'
     LIMIT 1`,
    { hash, now },
  );
  const terminal = rows[0];

  if (!terminal) {
    await recordPairingAttempt(ipAddress, false);
    await writeAudit({
      eventType: 'TERMINAL_PAIRING_FAILED',
      detail: 'Invalid, expired, or already-consumed pairing code',
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired code' });
  }

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [result] = await conn.execute(
      `UPDATE terminals
       SET pairing_code_consumed_at_epoch_ms = :now, paired = 1, paired_at_epoch_ms = :now
       WHERE id = :id AND pairing_code_consumed_at_epoch_ms IS NULL`,
      { id: terminal.id, now },
    );
    if (result.affectedRows === 0) {
      // Consumed by a concurrent request between the SELECT and here — treat as failure,
      // never let two callers both succeed with the same code.
      await conn.rollback();
      await recordPairingAttempt(ipAddress, false);
      return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired code' });
    }

    const accessToken = signTerminalAccessToken(terminal);
    const refreshToken = signTerminalRefreshToken(terminal);
    const refreshTtl = Number(process.env.JWT_REFRESH_TTL_SECONDS || 604800);
    await conn.execute(
      `INSERT INTO terminal_refresh_tokens (id, terminal_id, token_hash, expires_at_epoch_ms, revoked, created_at_epoch_ms)
       VALUES (:id, :terminalId, :tokenHash, :expiresAt, 0, :now)`,
      {
        id: newId(),
        terminalId: terminal.id,
        tokenHash: hashToken(refreshToken),
        expiresAt: now + refreshTtl * 1000,
        now,
      },
    );
    await conn.commit();

    await recordPairingAttempt(ipAddress, true);
    await writeAudit({
      eventType: 'TERMINAL_PAIRED',
      terminalId: terminal.id,
      siteId: terminal.site_id,
      entityType: 'TERMINAL',
      entityId: terminal.id,
    });

    const [refreshed] = await pool.execute(`SELECT * FROM terminals WHERE id = :id`, { id: terminal.id });
    return res.json({
      accessToken,
      refreshToken,
      expiresAtEpochMillis: now + Number(process.env.JWT_ACCESS_TTL_SECONDS || 3600) * 1000,
      terminal: mapTerminal(refreshed[0]),
    });
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
}
