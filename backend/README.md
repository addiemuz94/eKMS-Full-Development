# eKMS backend

Node.js + Express + MySQL API. Local Phase 1–4 development and production deploy for **kms-cvt.com**.

## Local setup

```bash
cd backend
cp .env.example .env
npm install
npm run db:up
npm run seed
npm run dev
```

API: `http://localhost:3000` by default.

On hosts where **port 3000 is already used** (e.g. Fortress Control), use **Option A**:

1. Set `PORT=3001` in `backend/.env` (for a host Node process), **or** publish Docker API as `3001:3000` (see `docker-compose.prod.yml`).
2. Point the Wasm portal at it — `LOCAL_API_BASE_URL` in `webApp` is `http://localhost:3001`.
3. Sign in with `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD` from your `.env` (example defaults: `superadmin@ekms.local` / `ChangeMeNow!`).
4. If login still fails after changing env secrets, run `npm run seed:reset-password` (or the same script inside the API container).

## Production (kms-cvt.com)

Web + API share one domain (no `api.` subdomain). See [DEPLOY.md](DEPLOY.md).
