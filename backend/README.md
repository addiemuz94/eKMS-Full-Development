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

API: `http://localhost:3000`

## Production (kms-cvt.com)

Web + API share one domain (no `api.` subdomain). See [DEPLOY.md](DEPLOY.md).
