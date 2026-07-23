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
