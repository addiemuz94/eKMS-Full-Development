# eKMS Web Portal (React)

Super Admin portal replacing the Kotlin/Wasm `webApp` module.

## Local development

```bash
# API must be reachable (Docker maps eKMS API to host :3001)
cd backend && docker compose -f docker-compose.prod.yml --env-file .env.production ps

cd web
npm install
npm run dev
```

Vite proxies `/v1` and `/health` to `http://127.0.0.1:3001`.

Default login (after seed/reset): `superadmin@ekms.local` / `ChangeMeNow!`

## Production build + deploy

```bash
cd web
npm run build
# dist/ contents → Docker volume backend_web_dist (see backend/DEPLOY.md)
```

On `kms-cvt.com`, the portal is same-origin and calls `/v1/...` directly.
