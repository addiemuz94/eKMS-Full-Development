import { Router } from 'express';
import { requireAuth, requireSuperAdmin } from '../middleware/auth.js';
import {
  consumeStreamTicket,
  mintStreamTicket,
  registerSuperAdminConnection,
  unregisterSuperAdminConnection,
} from '../notifications.js';

const router = Router();

// Deliberately NOT mounted behind a blanket requireAuth (unlike every other admin router) —
// the two routes below need genuinely different auth handling, so each states its own:
// POST /stream/ticket is a normal Bearer-authenticated request; GET /stream cannot be, since
// the browser's EventSource has no way to attach an Authorization header at all. See
// notifications.js's own doc for the full reasoning. This is the same class of deliberate,
// explicitly-reasoned exception to "every route sits behind requireAuth" that pair-with-code
// and passkey-login already are, not an oversight.
router.post('/stream/ticket', requireAuth, requireSuperAdmin, (req, res) => {
  const ticket = mintStreamTicket(req.auth.sub);
  res.json({ ticket });
});

// Ticket-authenticated, not JWT-authenticated — the ticket itself was only ever mintable by an
// already-`requireAuth`+`requireSuperAdmin`-checked caller, is single-use, and expires in 30s,
// so consuming it here is the actual auth check for this connection.
router.get('/stream', (req, res) => {
  const ticket = req.query.ticket;
  const userId = typeof ticket === 'string' ? consumeStreamTicket(ticket) : null;
  if (!userId) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Invalid or expired stream ticket' });
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(': connected\n\n');
  registerSuperAdminConnection(userId, res);

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
    unregisterSuperAdminConnection(userId, res);
  });
});

export default router;
