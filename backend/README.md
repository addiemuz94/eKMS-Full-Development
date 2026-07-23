# eKMS backend (Phase 1)

Node.js + Express + MySQL API for Super Admin auth and core CRUD.

## Prerequisites

- Node.js 20+
- Docker (for local MySQL)

## Setup

```bash
cd backend
cp .env.example .env
npm install
npm run db:up
# wait until MySQL is healthy, then:
npm run seed
npm run dev
```

API base: `http://localhost:3000`

Default Super Admin (from `.env`):

- email: `superadmin@ekms.local`
- password: `ChangeMeNow!`

## Phase 1 endpoints

- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- CRUD `/v1/admin/sites|terminals|users|keys|key-slots|access-grants`

Mutating admin calls require:

- `Authorization: Bearer <accessToken>`
- `Idempotency-Key: <unique-string>`
