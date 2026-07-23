import cors from 'cors';
import dotenv from 'dotenv';
import express from 'express';
import { requireAuth, requireSuperAdmin } from './middleware/auth.js';
import { idempotency } from './middleware/idempotency.js';
import { login, refresh } from './routes/auth.js';
import sitesRouter from './routes/sites.js';
import terminalsRouter from './routes/terminals.js';
import usersRouter from './routes/users.js';
import keysRouter from './routes/keys.js';
import keySlotsRouter from './routes/keySlots.js';
import accessGrantsRouter from './routes/accessGrants.js';

dotenv.config();

const app = express();
const port = Number(process.env.PORT || 3000);

app.use(
  cors({
    origin: true,
    credentials: true,
    allowedHeaders: ['Content-Type', 'Authorization', 'Idempotency-Key'],
  }),
);
app.use(express.json({ limit: '1mb' }));

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'ekms-backend' });
});

app.post('/v1/auth/login', login);
app.post('/v1/auth/refresh', refresh);

const admin = express.Router();
admin.use(requireAuth, requireSuperAdmin, idempotency);
admin.use('/sites', sitesRouter);
admin.use('/terminals', terminalsRouter);
admin.use('/users', usersRouter);
admin.use('/keys', keysRouter);
admin.use('/key-slots', keySlotsRouter);
admin.use('/access-grants', accessGrantsRouter);

app.use('/v1/admin', admin);

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: 'INTERNAL', message: 'Unexpected server error' });
});

app.listen(port, () => {
  console.log(`eKMS backend listening on http://localhost:${port}`);
});
