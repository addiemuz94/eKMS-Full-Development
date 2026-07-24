import cors from 'cors';
import dotenv from 'dotenv';
import express from 'express';
import { agentDebugLog } from './agentDebugLog.js';
import { requireAuth, requireSuperAdmin } from './middleware/auth.js';
import { idempotency } from './middleware/idempotency.js';
import { login, refresh } from './routes/auth.js';
import sitesRouter from './routes/sites.js';
import terminalsRouter from './routes/terminals.js';
import usersRouter from './routes/users.js';
import keysRouter from './routes/keys.js';
import keySlotsRouter from './routes/keySlots.js';
import accessGrantsRouter from './routes/accessGrants.js';
import recycleBinRouter from './routes/recycleBin.js';
import credentialsRouter from './routes/credentials.js';
import auditRouter from './routes/audit.js';
import { terminalSyncRouter, syncConflictsRouter } from './routes/sync.js';
import {
  eventDefinitionsRouter,
  schedulesRouter,
  namedGroupRouter,
  multiAuthRulesRouter,
  appointmentReasonsRouter,
  appointmentsRouter,
  appointmentPermissionsRouter,
  reportsRouter,
} from './routes/phase4.js';

dotenv.config();

const app = express();
const port = Number(process.env.PORT || 3000);

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
app.use(express.json({ limit: '1mb' }));

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'ekms-backend' });
});

// #region agent log
app.post('/v1/debug/agent-log', (req, res) => {
  const body = req.body && typeof req.body === 'object' ? req.body : {};
  agentDebugLog({
    hypothesisId: body.hypothesisId || 'client',
    location: body.location || 'client',
    message: body.message || 'client-log',
    data: body.data && typeof body.data === 'object' ? body.data : {},
    runId: body.runId || 'pre-fix',
  });
  res.status(204).end();
});
// #endregion

app.post('/v1/auth/login', login);
app.post('/v1/auth/refresh', refresh);

const admin = express.Router();
admin.use(requireAuth, requireSuperAdmin, idempotency);
admin.use('/users/:userId/credentials', credentialsRouter);
admin.use('/sites', sitesRouter);
admin.use('/terminals', terminalsRouter);
admin.use('/users', usersRouter);
admin.use('/keys', keysRouter);
admin.use('/key-slots', keySlotsRouter);
admin.use('/access-grants', accessGrantsRouter);
admin.use('/recycle-bin', recycleBinRouter);
admin.use('/sync-conflicts', syncConflictsRouter);
admin.use('/event-definitions', eventDefinitionsRouter);
admin.use('/schedules', schedulesRouter);
admin.use('/personnel-groups', namedGroupRouter('personnel_groups', 'PERSONNEL_GROUP'));
admin.use('/key-groups', namedGroupRouter('key_groups', 'KEY_GROUP'));
admin.use('/multi-authentication-rules', multiAuthRulesRouter);
admin.use('/appointment-reasons', appointmentReasonsRouter);
admin.use('/appointments', appointmentsRouter);
admin.use('/appointment-permissions', appointmentPermissionsRouter);

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
