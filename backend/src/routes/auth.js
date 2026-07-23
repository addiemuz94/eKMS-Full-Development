import bcrypt from 'bcryptjs';
import { z } from 'zod';
import pool from '../db.js';
import {
  hashToken,
  signAccessToken,
  signRefreshToken,
  verifyRefreshToken,
} from '../middleware/auth.js';
import { lifecycleFromRow, nowMs, writeAudit, badRequest } from '../util.js';

const loginSchema = z.object({
  identifier: z.string().min(1),
  password: z.string().min(1),
  clientType: z.enum(['WEB', 'MOBILE', 'TERMINAL']).default('WEB'),
  deviceId: z.string().optional(),
});

async function loadAssignedSiteIds(userId) {
  const [rows] = await pool.execute(
    `SELECT site_id FROM user_site_assignments usa
     INNER JOIN sites s ON s.id = usa.site_id
     WHERE usa.user_id = :userId AND s.lifecycle_state = 'ACTIVE'`,
    { userId },
  );
  return rows.map((r) => r.site_id);
}

function userProfile(row, assignedSiteIds) {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    assignedSiteIds,
    accountStatus: row.account_status,
    revision: Number(row.revision),
    lifecycle: lifecycleFromRow(row),
  };
}

export async function login(req, res) {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    return badRequest(res, parsed.error.issues.map((i) => i.message).join('; '));
  }

  const { identifier, password, clientType, deviceId } = parsed.data;
  const [rows] = await pool.execute(
    `SELECT * FROM users
     WHERE email = :identifier AND lifecycle_state = 'ACTIVE'
     LIMIT 1`,
    { identifier },
  );

  const user = rows[0];
  if (!user || !user.password_hash) {
    await writeAudit({
      eventType: 'LOGIN_DENIED',
      detail: `Unknown or incomplete account for ${identifier}`,
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid credentials' });
  }

  const ok = await bcrypt.compare(password, user.password_hash);
  if (!ok || user.account_status !== 'ACTIVE') {
    await writeAudit({
      eventType: 'LOGIN_DENIED',
      actorUserId: user.id,
      detail: user.account_status !== 'ACTIVE' ? 'Account disabled' : 'Bad password',
    });
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid credentials' });
  }

  const assignedSiteIds = await loadAssignedSiteIds(user.id);
  const accessToken = signAccessToken(user);
  const refreshToken = signRefreshToken(user);
  const refreshTtl = Number(process.env.JWT_REFRESH_TTL_SECONDS || 604800);
  const expiresAt = nowMs() + refreshTtl * 1000;

  await pool.execute(
    `INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at_epoch_ms, revoked, created_at_epoch_ms)
     VALUES (UUID(), :userId, :tokenHash, :expiresAt, 0, :now)`,
    {
      userId: user.id,
      tokenHash: hashToken(refreshToken),
      expiresAt,
      now: nowMs(),
    },
  );

  await writeAudit({
    eventType: 'LOGIN_SUCCEEDED',
    actorUserId: user.id,
    detail: JSON.stringify({ clientType, deviceId: deviceId || null }),
  });

  return res.json({
    accessToken,
    refreshToken,
    expiresAtEpochMillis: nowMs() + Number(process.env.JWT_ACCESS_TTL_SECONDS || 3600) * 1000,
    profile: userProfile(user, assignedSiteIds),
    role: user.role,
    permittedSiteIds: assignedSiteIds,
  });
}

export async function refresh(req, res) {
  const refreshToken = req.body?.refreshToken;
  if (!refreshToken) {
    return badRequest(res, 'refreshToken is required');
  }

  let payload;
  try {
    payload = verifyRefreshToken(refreshToken);
  } catch {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid refresh token' });
  }

  const tokenHash = hashToken(refreshToken);
  const [tokens] = await pool.execute(
    `SELECT * FROM refresh_tokens
     WHERE token_hash = :tokenHash AND revoked = 0 AND expires_at_epoch_ms > :now
     LIMIT 1`,
    { tokenHash, now: nowMs() },
  );
  if (!tokens[0] || tokens[0].user_id !== payload.sub) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Refresh token revoked or expired' });
  }

  const [users] = await pool.execute(
    `SELECT * FROM users WHERE id = :id AND lifecycle_state = 'ACTIVE' AND account_status = 'ACTIVE' LIMIT 1`,
    { id: payload.sub },
  );
  const user = users[0];
  if (!user) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'User not available' });
  }

  const assignedSiteIds = await loadAssignedSiteIds(user.id);
  const accessToken = signAccessToken(user);

  return res.json({
    accessToken,
    refreshToken,
    expiresAtEpochMillis: nowMs() + Number(process.env.JWT_ACCESS_TTL_SECONDS || 3600) * 1000,
    profile: userProfile(user, assignedSiteIds),
    role: user.role,
    permittedSiteIds: assignedSiteIds,
  });
}
