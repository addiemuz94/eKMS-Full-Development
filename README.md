# eKMS — Key Management System

Production-oriented eKMS build. The supplied `ekmshardwaretester-main` project is reference material only.

## Modules

| Module | Purpose |
|---|---|
| `shared` | Cross-platform domain models, policies, sync conflict DTOs, API contracts, cabinet protocol (pure Kotlin). |
| `terminalApp` | Android app for the F7G18P terminal (cabinet serial, NFC, fingerprint/camera). |
| `mobileApp` | Android Super Admin companion (thin UI today). |
| `web/` | **Live** Super Admin portal — React + TypeScript + Vite (replaces Kotlin/Wasm `webApp`). |
| `backend/` | Node/Express + MySQL API and Docker/Caddy deploy stack for `kms-cvt.com`. |
| `webApp/` | Frozen Kotlin/Wasm portal (not in the Gradle build; kept for reference). |
| `docs/` | Backend / workflow handover documents. |

## Confirmed policies

- Website and Terminal both provide Super Admin user/key/site editing.
- Website/backend is the source of truth; an offline Terminal queues changes.
- Conflicting offline edits require Super Admin review (never silent overwrite).
- Delete moves data to a Super Admin-only Recycle Bin for 60 days.
- Historic audit events are retained after permanent purge.
- Vendor passkeys are reusable until approved expiry, limited to approved terminal/site/keys.

## Open in Android Studio (Terminal / Mobile / shared)

1. Install Android Studio with the **Kotlin Multiplatform** plugin.
2. Open the **repo root** as a Gradle project (not a module subfolder).
3. Set Gradle JDK to **JDK 17** and use **Gradle 8.13** (not 9.x).
4. Sync, then run `terminalApp` or `mobileApp`.

Toolchain: JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, `compileSdk = 36`.

## Web portal (React)

```bash
cd web
npm install
npm run dev          # proxies /v1 to http://127.0.0.1:3001
npm run build        # output in web/dist — see backend/DEPLOY.md Part F
```

Details: [web/README.md](web/README.md)

## Backend / production

See [backend/README.md](backend/README.md) and [backend/DEPLOY.md](backend/DEPLOY.md).

Live site: **https://kms-cvt.com**

## Docs

- [docs/BACKEND_DEVELOPER_HANDOVER.md](docs/BACKEND_DEVELOPER_HANDOVER.md)
- [docs/WEB_PORTAL_WORKFLOW_HANDOVER.md](docs/WEB_PORTAL_WORKFLOW_HANDOVER.md)
- [docs/API_HANDOVER_SUPER_ADMIN V4.md](docs/API_HANDOVER_SUPER_ADMIN%20V4.md)
