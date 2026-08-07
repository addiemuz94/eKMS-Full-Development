# Checkout-Deadline Notifications — Deploy Handover

**Status:** **Deployed on `kms-cvt.com`** (Aug 2026). Keep this file as the ops checklist for re-deploys / FCM credential rotation.

**Audience:** Anyone redeploying or rotating Firebase credentials on the VPS.

**Scope:** Real-time checkout-deadline notifications:
- **Super Admin** — browser SSE bell + toast
- **Regional Admin** — browser SSE bell + toast **for sites in their assigned region(s)**; also FCM push
- **Technician / Vendor** who took the key — FCM push

## What's in this change

| Piece | What it is |
|---|---|
| Backend | Migrations `012` (+ `011` collation fix); `deadlineMonitor.js`; `fcm.js` (firebase-admin v14 modular API); `notifications.js` + `notificationsStream.js` (SA + RA tickets; `broadcastCheckoutDeadline`) |
| Web | `NotificationsContext` / `NotificationBell` / `Toast` — SA **and** RA |
| Mobile | Firebase SDK (`EkmsFirebaseMessagingService`, `PushTokenSync`) |

## Redeploy checklist (Day-2)

```bash
cd ~/eKMS && git pull
cd backend
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build api
docker compose -f docker-compose.prod.yml --env-file .env.production exec -T api node src/migratePhase2.js
cd ../web && npm install && npm run build
# copy web/dist into backend_web_dist volume — see backend/DEPLOY.md Part F
```

## Firebase service-account JSON

- Host path (example): `/root/eKMS/secrets/fcm-service-account.json` (mode `600`, **gitignored**)
- Env (`.env.production`):
  - `FCM_SERVICE_ACCOUNT_PATH=/run/secrets/fcm-service-account.json`
  - `FCM_SERVICE_ACCOUNT_HOST_PATH=/root/eKMS/secrets/fcm-service-account.json`
- `docker-compose.prod.yml` bind-mounts the host file into the `api` container.
- Missing file → `fcm.js` logs and no-ops (API stays up; SSE still works).

**Rotate** the key in Firebase Console if it was ever pasted into chat/logs, then replace the JSON and recreate `ekms-api`.

## Smoke tests

1. `curl -s https://kms-cvt.com/health` → `{"ok":true,...}`
2. Sign in as Super Admin → DevTools Network: `GET /v1/notifications/stream?ticket=...` stays open; `: ping` every ~30s
3. Sign in as Regional Admin → same stream; bell visible in top bar
4. Force overdue checkout (see earlier SQL snippet in git history / ops notes) → SA toast + covering RA toast within ~60s; FCM to device tokens if registered

## Notes

- Regionless sites (`sites.region_id IS NULL`) notify **zero** Regional Admins (SSE and FCM) — assign the site to a region first.
- Caddy SSE idle timeout through Cloudflare was previously unverified; if the stream drops, add an explicit reverse_proxy timeout in `Caddyfile`.
