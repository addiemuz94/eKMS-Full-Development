import { Router } from 'express';
import { requireAuth } from '../middleware/auth.js';
import {
  consumeStreamTicket,
  mintStreamTicket,
  registerAdminConnection,
  unregisterAdminConnection,
} from '../notifications.js';
import { isCapabilityEnabled } from '../roleCapabilitiesCatalog.js';
import { assignedSiteIdsForUser } from '../util.js';

const router = Router();

/** SA always; RA only when api.notifications capability is enabled (narrow-only matrix). */
async function requireSaOrRaNotifications(req, res, next) {
  const role = req.auth?.role;
  if (role === 'SUPER_ADMIN') return next();
  if (role === 'REGIONAL_ADMIN') {
    if (!(await isCapabilityEnabled('REGIONAL_ADMIN', 'api.notifications'))) {
      return res.status(403).json({
        error: 'FORBIDDEN',
        message: 'This capability is disabled for Regional Admin',
      });
    }
    return next();
  }
  return res.status(403).json({ error: 'FORBIDDEN', message: 'Super Admin or Regional Admin required' });
}

// Deliberately NOT mounted behind a blanket requireAuth (unlike every other admin router) —
// the two routes below need genuinely different auth handling, so each states its own:
// POST /stream/ticket is a normal Bearer-authenticated request; GET /stream cannot be, since
// the browser's EventSource has no way to attach an Authorization header at all. See
// notifications.js's own doc for the full reasoning. This is the same class of deliberate,
// explicitly-reasoned exception to "every route sits behind requireAuth" that pair-with-code
// and passkey-login already are, not an oversight.
router.post('/stream/ticket', requireAuth, requireSaOrRaNotifications, async (req, res) => {
  const role = req.auth.role;
  const siteIds =
    role === 'REGIONAL_ADMIN' ? await assignedSiteIdsForUser(req.auth.sub) : null;
  const ticket = mintStreamTicket(req.auth.sub, { role, siteIds });
  res.json({ ticket });
});

// Ticket-authenticated, not JWT-authenticated — the ticket itself was only ever mintable by an
// already-`requireAuth`+`requireSuperAdminOrRegionalAdmin`-checked caller, is single-use, and
// expires in 30s, so consuming it here is the actual auth check for this connection.
router.get('/stream', (req, res) => {
  const ticket = req.query.ticket;
  const identity = typeof ticket === 'string' ? consumeStreamTicket(ticket) : null;
  if (!identity) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired stream ticket' });
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(': connected\n\n');
  registerAdminConnection(identity.userId, res, {
    role: identity.role,
    siteIds: identity.siteIds,
  });

  // Periodic comment line, not a real event — keeps the connection demonstrably active against
  // any idle-connection timeout (Node's own or Caddy's reverse_proxy defaults), independent of
  // whichever specific timeout mechanism would otherwise apply; standard practice for SSE.
  const heartbeat = setInterval(() => {
    try {
      res.write(': ping\n\n');
    } catch {
      // Write failed — the 'close' handler below still fires and cleans up.
    }
  }, 30_000);

  req.on('close', () => {
    clearInterval(heartbeat);
    unregisterAdminConnection(identity.userId, res);
  });
});

export default router;
