import cors from 'cors';
import dotenv from 'dotenv';
import express from 'express';
import { requireAuth, requireSuperAdmin, requireSuperAdminOrAllowlistedRole } from './middleware/auth.js';
import { idempotency } from './middleware/idempotency.js';
import { login, refresh } from './routes/auth.js';
import { pairWithCode } from './routes/pairing.js';
import sitesRouter from './routes/sites.js';
import terminalsRouter from './routes/terminals.js';
import usersRouter from './routes/users.js';
import keysRouter from './routes/keys.js';
import keySlotsRouter from './routes/keySlots.js';
import accessGrantsRouter from './routes/accessGrants.js';
import recycleBinRouter from './routes/recycleBin.js';
import flushRouter from './routes/flush.js';
import credentialsRouter from './routes/credentials.js';
import keyCheckoutsRouter from './routes/keyCheckouts.js';
import regionsRouter from './routes/regions.js';
import keyAccessRequestsRouter, { passkeyLogin } from './routes/keyAccessRequests.js';
import mobilePushTokensRouter from './routes/mobilePushTokens.js';
import auditRouter from './routes/audit.js';
import { terminalSyncRouter, syncConflictsRouter } from './routes/sync.js';
import {
  eventDefinitionsRouter,
  schedulesRouter,
  namedGroupRouter,
  multiAuthRulesRouter,
  reportsRouter,
} from './routes/phase4.js';

dotenv.config();

const app = express();
const port = Number(process.env.PORT || 3000);

// Deployed behind Caddy (see backend/DEPLOY.md) — without this, req.ip is the proxy's own
// address for every request, which would make pairing.js's per-IP rate limiter either
// useless (never triggers) or, worse, a global lockout (every request looks like the same
// "IP"). Trust the immediate proxy's X-Forwarded-For.
app.set('trust proxy', true);

const corsOrigins = (process.env.CORS_ORIGINS || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

const isLocalDevOrigin = (origin) =>
  /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(origin || '');

app.use(
  cors({
    origin: (origin, callback) => {
      // Non-browser / same-origin style requests may omit Origin.
      if (!origin) {
        callback(null, true);
        return;
      }
      if (corsOrigins.length === 0 || corsOrigins.includes(origin) || isLocalDevOrigin(origin)) {
        callback(null, true);
        return;
      }
      callback(new Error(`CORS blocked for origin: ${origin}`));
    },
    credentials: true,
    allowedHeaders: ['Content-Type', 'Authorization', 'Idempotency-Key'],
  }),
);
app.use(express.json({ limit: '12mb' }));

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'ekms-backend' });
});

app.post('/v1/auth/login', login);
app.post('/v1/auth/refresh', refresh);
// Unauthenticated by necessity — a fresh, never-paired terminal has no token yet. See
// TerminalPairWithCodeRequest's doc in shared/.../api/ApiContracts.kt for the full flow,
// and pairing.js for the rate-limiting/lockout that makes this safe to expose.
app.post('/v1/terminal/pair-with-code', pairWithCode);
// Unauthenticated by necessity, same reasoning as pair-with-code above — a terminal-side
// operator entering a passkey has no token yet. See keyAccessRequests.js's `passkeyLogin` doc
// and TerminalPasskeyLoginRequest in shared/.../api/ApiContracts.kt for the full contract.
// Backend route only this pass — terminalApp's TerminalPasskeyLoginScreen is not wired to it yet.
app.post('/v1/terminal/passkey-login', passkeyLogin);

const admin = express.Router();
admin.use(requireAuth, requireSuperAdminOrAllowlistedRole, idempotency);
admin.use('/users/:userId/credentials', credentialsRouter);
admin.use('/sites', sitesRouter);
admin.use('/terminals', terminalsRouter);
admin.use('/users', usersRouter);
admin.use('/keys', keysRouter);
admin.use('/key-slots', keySlotsRouter);
admin.use('/access-grants', accessGrantsRouter);
admin.use('/key-checkouts', keyCheckoutsRouter);
// vendor-passkey-requests unmounted — replaced by Vendor staged key-access-requests (PIC → RA).
// Route file kept for historical rows / optional one-off migration; do not remount without product OK.
admin.use('/regions', regionsRouter);
admin.use('/key-access-requests', keyAccessRequestsRouter);
admin.use('/mobile-push-tokens', mobilePushTokensRouter);
admin.use('/recycle-bin', recycleBinRouter);
admin.use('/flush', flushRouter);
admin.use('/sync-conflicts', syncConflictsRouter);
admin.use('/event-definitions', eventDefinitionsRouter);
admin.use('/schedules', schedulesRouter);
admin.use('/personnel-groups', namedGroupRouter('personnel_groups', 'PERSONNEL_GROUP'));
admin.use('/key-groups', namedGroupRouter('key_groups', 'KEY_GROUP'));
admin.use('/multi-authentication-rules', multiAuthRulesRouter);
// Appointment Authorization removed — not required by the client.

app.use('/v1/admin', admin);

const audit = express.Router();
audit.use(requireAuth, requireSuperAdmin);
audit.use('/events', auditRouter);
app.use('/v1/audit', audit);

const reports = express.Router();
reports.use(requireAuth, requireSuperAdmin, idempotency);
reports.use('/', reportsRouter);
app.use('/v1/reports', reports);

const terminalSync = express.Router();
terminalSync.use(requireAuth, idempotency);
terminalSync.use('/', terminalSyncRouter);
app.use('/v1/terminal/sync', terminalSync);

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: 'INTERNAL', message: 'Unexpected server error' });
});

app.listen(port, () => {
  console.log(`eKMS backend listening on http://localhost:${port}`);
});
