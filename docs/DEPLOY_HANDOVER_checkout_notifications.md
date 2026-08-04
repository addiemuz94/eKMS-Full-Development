# Checkout-Deadline Notifications — Deploy Handover

**Status:** Code-complete, cross-checked, **not yet deployed** — 4 August 2026

**Audience:** The dev team performing the VPS deployment (separate from whoever wrote this feature)

**Scope:** Real-time checkout-deadline notifications — a Super Admin gets a live browser popup +
persistent bell/panel via SSE, the assigned technician/vendor and covering Regional Admins get a
real push notification via Firebase Cloud Messaging (FCM), the moment a key checkout is 15
minutes from due or goes overdue.

## What's in this change

Three independently-built pieces, already cross-checked against each other's actual code (event
names, payload field names) and confirmed matching — see `CLAUDE_BACKEND.md` / `CLAUDE_MOBILE.md`
/ `CLAUDE_WEB.md` for the full per-piece detail. This document is only the deploy sequence.

| Piece | What it is |
|---|---|
| Backend | Migration `012_key_checkout_notifications.sql`; `deadlineMonitor.js` (60s tick, fires `CHECKOUT_WARNING_15MIN` / `CHECKOUT_OVERDUE` exactly once per checkout); `fcm.js` on `firebase-admin` HTTP v1; `notifications.js` + `routes/notificationsStream.js` (SSE ticket mint + stream) |
| Web | `notifications/NotificationsContext.tsx` (SSE client, reconnect/backoff), `components/ui/Toast.tsx`, `notifications/NotificationBell.tsx` |
| Mobile | Real Firebase SDK wiring (`google-services.json`, `EkmsFirebaseMessagingService.kt`, `PushNotifications.kt`), replacing the old reflection stub |

Nothing here has been deployed to `kms-cvt.com` yet. Follow the steps below in order.

---

## 1. Pull the code

```bash
cd ~/eKMS   # or wherever this clone lives on the VPS
git pull
```

Confirm you now have `backend/src/deadlineMonitor.js`, `backend/src/notifications.js`,
`backend/src/routes/notificationsStream.js`, and `backend/sql/012_key_checkout_notifications.sql`
present.

## 2. Run migration 012

This project has no separate migration-tracking table — `migratePhase2.js` just re-runs every
migration file 002→012 in order on every invocation, and each file is written to be safe to
re-apply (duplicate-column/key/entry errors are swallowed — see `IGNORE_ERRNOS` in
`migratePhase2.js`). Migration 012 itself is `CREATE TABLE IF NOT EXISTS key_checkout_notifications
(...)` — purely additive, nothing destructive. Confirmed as of this handover: 012 is both the
highest-numbered file in `backend/sql/` and the last one registered in `migratePhase2.js`'s call
list, so it is genuinely the next (and only new) migration to apply — nothing has been added after
it. (This was checked against the migration registration file only; nobody here has a live
connection to the production schema to double-check its actual current state — if in doubt, it is
safe to just run the command below, since re-running earlier migrations is a no-op.)

Using this project's existing Day-2 process (`backend/DEPLOY.md` → Day-2 operations → "Update
after `git pull`"):

```bash
cd ~/eKMS/backend
docker compose -f docker-compose.prod.yml --env-file .env.production exec api node src/migratePhase2.js
```

## 3. Firebase service-account JSON

The backend's real FCM push (`fcm.js`, `firebase-admin` HTTP v1) needs a Firebase service-account
JSON file **mounted into the container**, not baked into the image or committed to git.

- **Env var:** `FCM_SERVICE_ACCOUNT_PATH` — already present in `backend/.env.production.example`
  (line 25), set to `/run/secrets/fcm-service-account.json`. Set the same value in the real
  `.env.production` on the VPS.
- **Volume mount:** `backend/docker-compose.prod.yml` already has the mount commented out (lines
  63–64):
  ```yaml
  # volumes:
  #   - ${FCM_SERVICE_ACCOUNT_HOST_PATH}:/run/secrets/fcm-service-account.json:ro
  ```
  Uncomment those two lines once the credential file exists on the VPS, and set
  `FCM_SERVICE_ACCOUNT_HOST_PATH` in `.env.production` to wherever you place the file on the host
  (e.g. `/root/eKMS/secrets/fcm-service-account.json`) — keep it outside the git working tree.
- **If the file is missing or the path is unset:** `fcm.js` logs and no-ops gracefully — the API
  will not crash, it just won't send real pushes (SSE-to-web still works independently, since that
  path doesn't touch FCM at all).

## 4. Where the credential file itself comes from

**This document does not provide the Firebase service-account JSON.** The user is handing that
file to your team directly, outside of this repo and outside of this chat. What you need from
them: a Firebase service-account JSON key with Firebase Cloud Messaging send permission (a
service account with the Firebase Admin SDK / "Cloud Messaging" role in the same Firebase project
mobile's `google-services.json` points at — project `kms-cvt`, project number `906964946330`).
Place it at the host path referenced in step 3 and restart the `api` container after.

## 5. Verify: SSE through Caddy survives a long-lived connection

**This is flagged as unverified, not as a known-working setup.** `backend/src/index.js` sets
`server.requestTimeout = 0` on the Node HTTP server itself (Node's own slow-loris mitigation,
disabled deliberately for the SSE route), but `backend/Caddyfile`'s `reverse_proxy api:3000` block
for `/v1/*` has **no explicit timeout override** — it inherits whatever Caddy's own default
reverse-proxy behavior is. Nobody has confirmed live whether an open SSE connection through the
actual Caddy proxy in front of production survives more than a few minutes, or gets silently cut.

**Post-deploy, actually check this:**
- Open the portal, sign in as Super Admin, open browser DevTools → Network tab, find the
  `/v1/notifications/stream` request, and leave the tab open for at least 10–15 minutes. Confirm
  the request stays in a pending/open state (not aborted) and that the periodic `: ping` heartbeat
  comments (sent every 30s by `notificationsStream.js`) keep arriving.
- If the connection does drop before a real event fires, the fix is a Caddy-side directive (e.g.
  an explicit `transport http { read_timeout ... }` or disabling response buffering) — that's a
  `Caddyfile` change, out of scope for this handover, but flag it back if you observe a drop.

## 6. Backend container restart/rebuild

Standard existing process — `backend/DEPLOY.md` Day-2 operations, "Update after `git pull`":

```bash
cd ~/eKMS/backend
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
```

(Migration in step 2 and this rebuild can be done in either order relative to each other, but the
container needs a restart to pick up the new route files / `fcm.js` regardless of exact order —
`up -d --build` covers that.)

## 7. Rebuild and redeploy `web/`

Existing process, `backend/DEPLOY.md` Part F:

```bash
cd ~/eKMS/web
npm install
npm run build
```

Then copy `web/dist/` into the `web_dist` Docker volume exactly as documented in Part F (the
`docker run --rm -v ... alpine sh -c 'rm -rf /srv/* && cp -a /in/. /srv/ ...'` step). Hard-refresh
the browser afterward (Ctrl+Shift+R) so the old bundle isn't served from cache.

## 8. mobileApp — new release APK, and a distribution decision this repo doesn't make for you

The mobile changes (real Firebase SDK wiring) require a **new build** of `mobileApp` — the
existing debug APK built during development is not what should go to real devices.

**Checked, confirmed absent:** this repo has no release-signing configuration (no `signingConfig`
block in `mobileApp/build.gradle.kts` or `terminalApp/build.gradle.kts`), no keystore/`.jks` file
anywhere in the tree, no `fastlane` setup, and no `.github` CI workflows at all. **There is no
existing automated release-build or distribution pipeline for either Android app in this repo.**
Your team needs to decide and set up:
- A release signing key (new, or reuse one your organization already manages elsewhere — this
  repo has no opinion or prior art on this).
- How the signed APK gets built (`./gradlew :mobileApp:assembleRelease` or `bundleRelease`, once a
  signing config exists) and how it reaches real test/production devices (direct APK install,
  internal Play Console track, MDM push, etc.) — none of this is automated today.

## 9. Post-deploy smoke test checklist

1. **API up:** `curl -s https://kms-cvt.com/health` → `{"ok":true,"service":"ekms-backend"}`.
2. **SSE endpoint reachable:** sign in to the portal as Super Admin, confirm (via DevTools Network
   tab) a `GET /v1/notifications/stream?ticket=...` request opens and stays connected — see step 5
   for the longer-duration check.
3. **Force a real test notification** — the simplest way, using `deadlineMonitor.js`'s actual
   logic: it ticks every 60 seconds (`TICK_INTERVAL_MILLIS`) and fires `CHECKOUT_OVERDUE` for any
   `OPEN` checkout whose `due_at_epoch_ms <= now` (see `checkOverdue()`), as long as
   `key_checkout_notifications` has no existing `(checkout_id, 'OVERDUE')` row for it yet (the
   de-dup guard from migration 012). Pick or create a real `OPEN` key checkout, then:
   ```sql
   UPDATE key_checkouts
   SET due_at_epoch_ms = (UNIX_TIMESTAMP() * 1000) - 60000
   WHERE id = '<checkout id>';
   ```
   (one minute in the past, so it's immediately overdue). Wait up to 60 seconds for the next tick.
   For the 15-minute warning instead, set `due_at_epoch_ms` to roughly 10–14 minutes in the future
   (must land inside the warning window but still be in the future — see `checkWarnings()`'s
   `now < due_at_epoch_ms <= now + 15min` condition).
4. **Confirm web delivery:** the Super Admin browser session from step 2 should show a toast popup
   and an incremented bell badge within a tick of the SQL update above, with no page refresh.
5. **Confirm mobile delivery:** with a real device running the new release APK (step 8), signed in
   as the technician/vendor who owns that checkout (or a Regional Admin covering its site), confirm
   a real Android notification arrives, and tapping it opens the app to the Alerts tab. This also
   indirectly confirms steps 3/4 (the Firebase service-account file) are correctly wired, since
   `sendPushToUser` and the SSE broadcast are triggered by the same `deadlineMonitor.js` tick.

If any of 3–5 don't fire, check `docker compose ... logs -f api` for `[deadlineMonitor]` or FCM-
related errors first.
