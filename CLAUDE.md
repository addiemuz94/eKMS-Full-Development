# CLAUDE.md

Guidance for agents working in this repository. **Read Directory + Production + Terminal API before changing sync/backend assumptions.**

## Directory

| Jump to | What it covers |
|---|---|
| [Project](#project) | What eKMS is; live URLs; agent scope |
| [Production is already live](#production-is-already-live--do-not-reinvent-the-backend) | VPS / `kms-cvt.com` — backend already running |
| [Terminal → live API](#terminal--live-api-how-terminalapp-reaches-the-portal-backend) | How terminalApp configures and calls `/v1` so data shows on the web portal — **manual steps below are being superseded by pairing-code registration, see next row** |
| [Web Portal — Pending UI Work (Registration Workflow)](#web-portal--pending-ui-work-registration-workflow) | **Read before building Key Cabinet / Personnel registration pages in `web/`** — exact fields, endpoints, and the pairing-code display requirement |
| [Toolchain](#toolchain-do-not-drift-from-this-baseline) | JDK / Gradle / Kotlin pins |
| [Common commands](#common-commands) | Gradle + `web/` npm |
| [Module architecture](#module-architecture) | shared / terminal / mobile / web / backend |
| [Architectural boundaries](#non-negotiable-architectural-boundaries) | Hardware, no raw UIDs, revision-safe PATCH, soft-delete, … |
| [Where things live](#where-things-live) | Important paths |
| [Working in docs](#working-in-docs) | Handover doc rules |
| [Terminal App UX Baseline](#terminal-app-ux-baseline-production--baseline--defined-enhancements) | Manual + documented enhancements |
| [NFC UID Resolution Rule](#nfc-uid-resolution-rule-permanent) | Personnel vs key card lookup |
| [Web/Mobile UX Consistency](#webmobile-app-ux-consistency) | Portal/mobile vs terminal |
| [Project Status](#project-status) | **Start at “status truth”** if Completed bullets disagree |
| [Known issues](#known-issues--not-yet-resolved) | Open work only |
| [Next steps](#next-steps-in-order) | Priority order |
| [Reference](#reference) | Vendor protocol / manual |

## Project

eKMS is a Kotlin Multiplatform key-management system: an Android Terminal (physical key cabinet controller), an Android mobile Super Admin companion app, and a Super Admin web portal, all sharing domain models/policies/API contracts through a `shared` module. A real backend now exists (`backend/`, Express.js + MySQL, REST API at `/v1`) — terminalApp has real offline-first sync wiring against it (`TerminalApiClient`/`TerminalSyncCoordinator`/`TerminalSyncOutbox`), and the live Super Admin portal is the React app in `web/` (real-backend-connected for list/create/update/delete across the main admin areas — see Project Status). Some older areas (e.g. appointment-permissions client stubs) and full vendor-manual UX audit remain incomplete. `mobileApp` remains 100% local in-memory demo data with zero network code.

### Production is already live — do not reinvent the backend

**The API and Super Admin portal are already deployed on a VPS and reachable on the public internet.** Agents must not assume “there is no backend,” invent a new deploy, or treat local-only Node as a requirement to use the API.

| URL | What |
|---|---|
| `https://kms-cvt.com/` | Live React portal (`web/` build served from the VPS) |
| `https://kms-cvt.com/v1/...` | Live Express API |
| `https://kms-cvt.com/health` | Health check |
| Terminal Admin Menu → server address | `https://kms-cvt.com` (no trailing slash; **not** an `api.` subdomain) |

Deploy/ops details live in `backend/DEPLOY.md` (Docker Compose prod, Caddy, Cloudflare DNS for `kms-cvt.com`). **Default agent scope:** work on `shared/`, `terminalApp/`, `web/`, and docs. Touch `backend/` only when the user explicitly asks for an API/schema/deploy change — and even then prefer updating the existing VPS deploy over standing up a parallel stack. A laptop without Node/Docker can still verify against production (browser → portal, terminal → `https://kms-cvt.com`); “no local Node” only means you cannot run `npm run build` / local Vite in that environment, **not** that the backend is unavailable.

### Terminal → live API (how terminalApp reaches the portal backend)

> **Being superseded.** terminalApp now has a pairing-code flow (a fresh terminal's first
> screen is a 6-digit code entry that calls `POST /v1/terminal/pair-with-code`, then reuses
> this same bootstrap pipeline automatically) that replaces steps 2-4 below. That code is
> written and committed, but the backend pairing endpoint is **not yet deployed to the VPS**
> and the flow has **not been run against a live terminal**. Until both of those happen, the
> manual steps below remain the only working path — do not tell a user "just use the pairing
> code" until this note is updated to say the flow is live. See "Web Portal — Pending UI Work
> (Registration Workflow)" below for what `web/` needs to build so a Super Admin can actually
> generate a code to type in.

The terminal does **not** talk to the React UI. It talks to the **same backend** the portal uses (`https://kms-cvt.com/v1/...`). When the terminal creates personnel or completes card enrollment, the portal’s Personnel page sees it after refresh because both share MySQL on the VPS.

**Wire-up on the device (Admin Menu):**

1. Sign in locally if needed (first Super Admin bootstrap), open **Admin Menu**.
2. **Set server address** → `https://kms-cvt.com` (no trailing slash).
3. **Key Cabinet ID** → UUID of this cabinet’s terminal row from portal **Terminal Settings** (same id used in sync paths).
4. Sign out, then sign in with a **portal** Super Admin email/password so `TerminalApiClient` stores JWT access/refresh tokens.
5. Use **Bootstrap** / **Download** / **Push** (Admin Menu) for snapshot sync; Personnel Management / Card enrollment call admin credential APIs directly when server-linked.

**Client code:** `terminalApp/.../data/TerminalApiClient.kt` builds requests as `{baseUrl}{ApiPaths.*}`. Canonical path strings live in `shared/.../api/ApiContracts.kt` (`object ApiPaths`). Auth header: `Authorization: Bearer <accessToken>` on every route except login/refresh. Login sends `clientType: TERMINAL`.

**Endpoints terminalApp actually calls today** (base = `https://kms-cvt.com`):

| Purpose | Method | Path | Auth |
|---|---|---|---|
| Sign in (store tokens) | `POST` | `/v1/auth/login` | No |
| Refresh tokens | `POST` | `/v1/auth/refresh` | No (body refresh token) |
| First-link / bootstrap snapshot | `POST` | `/v1/terminal/sync/bootstrap` | Yes |
| Push offline outbox | `POST` | `/v1/terminal/sync/push` | Yes |
| Read sync ack / revisions | `GET` (via client) | `/v1/terminal/sync/read` | Yes |
| Download server snapshot | `GET` (via client) | `/v1/terminal/sync/download` | Yes |
| List units (sites) for Add Personnel | `GET` | `/v1/admin/sites` | Yes |
| Resolve this cabinet’s unit | `GET` | `/v1/admin/terminals/{terminalId}` | Yes |
| List / create personnel | `GET` / `POST` | `/v1/admin/users` | Yes |
| List credential enrollment status | `GET` | `/v1/admin/users/{userId}/credentials` | Yes |
| Complete card enrollment (opaque ref only — never raw UID) | `POST` | `/v1/admin/users/{userId}/credentials/complete` | Yes |
| Revoke card enrollment | `POST` | `/v1/admin/users/{userId}/credentials/revoke` | Yes |

**What shows on the web portal after terminal actions:**

| Terminal action | Portal place to check |
|---|---|
| Add personnel (server-linked) | **Personnel Management** — new user row |
| Card enroll / revoke | **Personnel Management** — card enrollment column / status |
| Bootstrap / Download / Push | **Data Synchronization** (+ underlying users/keys/slots/grants lists) |

Full admin/report path catalog (portal-only and unused by terminal yet) is still in `ApiPaths` and `docs/API_HANDOVER_SUPER_ADMIN V4.md`. Do not invent a second base URL or `api.kms-cvt.com`.

**Web portal note:** the Super Admin web portal is being migrated from the Kotlin/Wasm Compose `webApp` module to a React+Vite app at `web/`. `webApp` is now excluded from the Gradle build (`settings.gradle.kts` has `include(":webApp")` commented out — "Kotlin/Wasm webApp is frozen") and should be treated as a reference/legacy implementation, not a build target. New Super Admin portal work happens in `web/`.

The `ekmshardwaretester-main` project mentioned in README.md is reference material only and is not part of this production build.

### Web Portal — Pending UI Work (Registration Workflow)

**Scope note:** `web/` UI is owned by a separate developer. The session that added the fields/
endpoints below did backend/`shared`/terminalApp only, deliberately built **no** `web/` pages
or components — this section is that handoff. Everything referenced here already exists in
`shared/.../api/ApiContracts.kt` (the canonical contract — read the actual DTOs there, this
section is a summary, not a substitute) and in the backend routes; it needs a redeploy to the
VPS before it's callable at `https://kms-cvt.com` (see `backend/DEPLOY.md`) — check with
whoever owns deploys before assuming it's live.

**1. Key Cabinet (Terminal) registration form** — `POST /v1/admin/terminals`, body is
`TerminalUpsertRequest`:

| Field | Type | Notes |
|---|---|---|
| `siteId` | string (UUID) | required — pick from the Units list |
| `name` | string | required |
| `boxAddress` | int | required, positive |
| `serialNumber` | string? | optional |
| `configuredSlotCount` | int | required, **server-validated 1–127** (docs/Key Cabinet Communication Protocol.md §7.1) — validate client-side too so the error isn't a surprise 400 |
| `cabinetSerialPort` | string? | optional |
| `cabinetBaudRate` | int? | optional |
| `vendorDeviceId` | string? | optional — vendor-assigned physical device ID, **distinct from the backend `id`** (a UUID minted on create). Commonly unknown at registration time; fine to leave blank and fill in later via edit. |
| `nodeRows` | int? | optional — **structured**, not free text. Pair with `nodesPerRow` to describe the physical cabinet layout (e.g. 4 rows × 6 nodes). |
| `nodesPerRow` | int? | optional, see above |
| `latitude` | double? | optional, must be -90..90 if present |
| `longitude` | double? | optional, must be -180..180 if present |

**Response is `TerminalRegistrationResponse`, not a bare `TerminalDto`** — this is a breaking
change from the old create response shape:
```
{ terminal: TerminalDto, pairingCode: string, pairingCodeExpiresAtEpochMillis: number }
```
`pairingCode` is a **plaintext 6-digit code, shown exactly once** — the backend only ever
stores its hash and cannot show it again. **The registration form's success state must display
this code prominently** (large, easy to read off a screen, with an explicit "expires at
`pairingCodeExpiresAtEpochMillis`" — 30 minutes from generation) so the Super Admin can type it
into the terminal's pairing screen. If the admin navigates away without noting it down, the
only recovery is regenerate (next).

**Regenerate code** — `POST /v1/admin/terminals/{id}/pairing-code` (no body), response is
`RegeneratePairingCodeResponse`: `{ terminalId, code, expiresAtEpochMillis }`. Same "shown once"
requirement — same prominent display treatment as registration. **Put a visible warning on this
action**: regenerating immediately revokes that terminal's current TERMINAL_DEVICE session (see
`backend/src/routes/pairing.js`'s `revokeTerminalSessions` — this was a deliberate, user-
confirmed design choice: a lost/reset device's old session must not keep syncing once a new
code is issued for a replacement device). This has no effect on a terminal still running the
legacy manual-login flow above (that terminal's tokens are ordinary Super Admin user tokens,
unaffected). Surface `terminal.paired` (on `TerminalDto`) somewhere in the Terminals list/detail
view so an admin can tell at a glance whether a cabinet has completed pairing at all.

**2. Personnel registration form** — `POST /v1/admin/users`, body is `CreateAdminUserRequest`.
Only what's new/relevant here (the rest of this form should already exist per Project Status):
add a `staffId` text field, optional (`staffId: String? = null`), described in `UserDto`/
`CreateAdminUserRequest`/`UpdateAdminUserRequest` as "external/employee identifier, distinct
from `id`". The role picker's "Vendor" option (`UserRole.VENDOR`) **already exists** — nothing
new needed there, it was not added as part of this work.

**3. What did NOT change:** Sites/Units registration, Keys, Key Slots, Access Grants, and every
other existing `web/` form are untouched by this work — do not extend this section's scope
beyond Terminal registration + `staffId` on Personnel.

## Toolchain (do not drift from this baseline)

- JDK 17, Gradle **8.13**, Android Gradle Plugin 8.11.1, Kotlin 2.2.20, `compileSdk = 36`, `minSdk = 26`.
- Do not upgrade to Gradle 9.x without migrating the whole KMP build to a newer AGP model — see `docs/BUILD_SETUP.md`.
- Kotlin and Java bytecode targets are explicitly pinned to JVM 17 on Android modules; a mismatch here is a known historical failure mode (Kotlin defaulting to 21 vs. Java defaulting to 11) and build scripts guard against it.
- **Git LFS is required** to correctly check out `terminalApp/src/main/assets/models/` (face-enrollment ML models — see "Face enrollment" in Project Status and the Known Issues note below). Run `git lfs install` once per machine after cloning; without it, `git pull`/`git clone` will leave those files as small LFS pointer-text stubs instead of real model binaries, and `OpenCvFaceEngine`/`MediaPipeFaceLandmarkerEngine` will fail to load at runtime. `.gitattributes` (repo root) defines the tracked patterns (`*.onnx`, `*.task`, `*.tflite`, `*.pb`, `*.bin` under that one directory) — extend it there, not elsewhere, if a new model format is added.

## Common commands

Run from the repo root (`gradlew.bat` on Windows, `./gradlew` on POSIX shells).

```
gradlew.bat build                                  # build all INCLUDED modules (shared, terminalApp, mobileApp — webApp is commented out of settings.gradle.kts and not built)
gradlew.bat :shared:allTests                        # run shared commonTest (KMP test target); wasm test leg can fail on yarn.lock drift in this environment, see below
gradlew.bat :shared:test --tests "*RecycleBinPolicyTest*"   # run a single test class
gradlew.bat :terminalApp:assembleDebug               # build Android Terminal app
gradlew.bat :terminalApp:build                        # compile + lint + assemble (debug & release) for terminalApp
gradlew.bat :mobileApp:assembleDebug                  # build Android mobile companion app
```

The Super Admin web portal now builds separately from Gradle, as a plain npm project:
```
cd web && npm install && npm run dev     # local dev server (Vite proxies /v1 to http://127.0.0.1:3001)
cd web && npm run build                  # production bundle -> dist/ (see backend/DEPLOY.md for deploy)
```
The old `gradlew.bat :webApp:wasmJsBrowserDevelopmentRun` / `:webApp:wasmJsBrowserProductionWebpack` commands no longer work — `webApp` is excluded from `settings.gradle.kts` (see Project note above) and has no Gradle tasks at all until re-included.

`shared` is the only Gradle module with tests today (`shared/src/commonTest`), run via the Kotlin/JVM+Wasm multiplatform test tasks. `:shared:testDebugUnitTest`/`:shared:testReleaseUnitTest` (JVM) are reliable; the wasm leg of `:shared:allTests` depends on `kotlinWasmStoreYarnLock`, which has failed with "Lock file was changed" in this dev environment independent of any code change — treat that specific failure as a known environment/tooling gap, not a regression, unless you've just touched wasm npm dependencies yourself. There is no lint/format command configured beyond the Gradle/Kotlin compiler (and now also `web/.oxlintrc.json` for the React portal).

Open the project in Android Studio at the repo root (not a module subfolder) with the Kotlin Multiplatform plugin; select JDK 17 and Gradle 8.13 explicitly, since the IDE default may pick something else.

## Module architecture

| Module | Target | Role |
|---|---|---|
| `shared` | Android + Wasm (commonMain/commonTest) | Cross-platform domain models, access policies, soft-delete/Recycle Bin rules, sync-conflict DTOs, and the canonical API path/DTO contracts. This is the single source of truth other modules and the backend must agree with. |
| `terminalApp` | Android only | The physical F7G18P key-cabinet terminal app. Owns all hardware I/O: cabinet serial protocol, NFC UID reads, fingerprint/camera. Real backend sync client (`TerminalApiClient`/`TerminalSyncCoordinator`/`TerminalSyncOutbox`/`TerminalServerCache`). |
| `mobileApp` | Android only | Super Admin companion app (thin UI layer today, no hardware access, no network code — still 100% local demo data). |
| `webApp` | Kotlin/Wasm + Compose | **Frozen/legacy.** Excluded from `settings.gradle.kts` (`include(":webApp")` commented out). Was the Super Admin web portal following the supplier's Web manual workflow sections; superseded by `web/`. Kept in the tree as reference, not currently buildable as part of the Gradle build. |
| `web` | React + Vite (TypeScript) | The current Super Admin web portal, replacing `webApp`. Calls the real backend directly over `/v1` (see `web/src/api/client.ts`). Not part of the Gradle build; builds via `npm`/Vite — see `web/README.md`. |
| `backend` | Node.js (Express + MySQL) | **Already running in production** on the VPS behind `https://kms-cvt.com/v1` (see “Production is already live” above and `backend/DEPLOY.md`). Source of truth for routes is this repo’s `backend/`; day-to-day agent work usually does **not** need to start or redeploy it unless the user asks. REST at `/v1`: `auth`, `admin` (sites/terminals/users/keys/key-slots/access-grants/recycle-bin/sync-conflicts/event-definitions/schedules/personnel-groups/key-groups/multi-authentication-rules/appointment-reasons/appointments/appointment-permissions), `audit`, `reports`, `terminal/sync`. |
| `docs` | — | Backend/API handover documents; treat `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` and the `API_HANDOVER_SUPER_ADMIN` series as the living spec for the backend/portal contract. `docs/Backend_Integration_Handover.md` predates the real backend's existence and is now stale in places (still says "there is no backend today") — read it for the schema-fragmentation and NFC-UID background, which are still accurate, but don't trust its "not implemented" claims about the backend itself without checking `backend/` first. |

### Non-negotiable architectural boundaries

These rules are enforced by convention across the codebase (see comments in `ApiContracts.kt` and `AdminModels.kt`) — preserve them in any change:

1. **Only the Android Terminal touches cabinet hardware.** Website and Mobile must never open a serial port, send a cabinet command/frame, or perform reader/NFC/biometric capture. The split-nibble/CRC8 frame protocol and full node command set (`KeyCabinetLink`, plus `SplitNibbleCodec`/`KeyCabinetCrc8`/`KeyCabinetFrame`) live in `shared/.../protocol/` as pure Kotlin with no serial dependency, so they're unit-testable without hardware — but only `terminalApp/src/main/java/com/ekms/terminal/hardware/` (`AndroidSerialTransport`, `CabinetHardwareController`) may actually open `/dev/ttyS1`/`/dev/ttyS2` and drive them.
2. **No raw credential material ever leaves the Terminal.** NFC UIDs, fingerprint/face templates, and Digital Key secrets are never represented in shared DTOs or sent to Website/Mobile — only an opaque `fobEnrollmentReference`/enrollment state. See `ManagedKey.fobEnrollmentReference` and `FobEnrollmentResponse` in `shared`.
3. **Every physical key-node address is canonical.** Node address `0` is always the door; key nodes are addresses within `1..configuredSlotCount`. Never apply a hidden UI +1/-1 conversion (explicitly called out on `KeySlot.nodeAddress` and `KeySlotUpsertRequest.nodeAddress`).
4. **All mutations are revision-safe.** Update/PATCH-style requests carry `expectedRevision`; the backend rejects stale writes with `409 CONFLICT` rather than silently overwriting (verified server-side: every PATCH route checks `existing.revision === expectedRevision` AND guards the UPDATE itself with `WHERE revision = :expectedRevision`, a real double-checked guard against races, not just an application-level check). The frozen `webApp` never had in-place edit/PATCH UI. `web/` now does, for 10 resources (Units, Terminals, Personnel, Keys, Permissions, Event Setup, Schedules, User Groups, Key Groups, Multi-Authentication Rules — see Project Status) — every edit path reads `expectedRevision` off the already-loaded row and shows an explicit conflict message on `409` rather than retrying or overwriting. Appointments and Key Slots remain create/delete-only: Appointments by backend design (only review/permissions-patch are mutable), Key Slots because no `web/` page exposes them yet.
5. **Delete is always soft-delete.** Records move to a Super Admin-only Recycle Bin for 60 days (`RecycleBinPolicy.RETENTION_DAYS`) before purge; active dependents must block a hidden cascade delete. Historic audit events survive a purge.
6. **Offline Terminal edits never silently overwrite server state.** A conflicting offline change becomes a `SyncConflict` that only a Super Admin (`ConflictReviewPolicy.mayResolve`) can resolve, via `KEEP_SERVER` / `KEEP_TERMINAL_CHANGE` / `MERGE_MANUALLY`.
7. **Passwords and other secrets are write-only** — never rendered, logged, or returned by an API response.
8. **No unauthenticated routes beyond `/health`, `/v1/auth/login`, `/v1/auth/refresh`.** Every other backend mount must sit behind `requireAuth` (see `backend/src/middleware/auth.js`) before any sub-router. This was violated once (see Project Status — the `/v1/debug/agent-log` incident) by ad hoc debug instrumentation that also logged real personnel emails in plaintext to server-side files with no auth or redaction. Debug/diagnostic instrumentation added during development must never ship unauthenticated, and must never log real user data (names, emails, credential/enrollment references) — use synthetic identifiers or counts instead. Verify route-mounting order in `backend/src/index.js` (the only file allowed to call `app.use`/`app.get`/`app.post` directly — every other route file exports a `Router()`) whenever adding new backend surface.

### Where things live

- `shared/.../domain/` — core entities (`AdminUser`, `Site`, `Terminal`, `ManagedKey`, `KeySlot`, `AccessGrant`, `CredentialBinding`, `AuditEvent`) plus their lifecycle/enum types.
- `shared/.../policy/` — business rules as pure functions/objects over domain types (e.g. `RecycleBinPolicy`).
- `shared/.../sync/` — offline-change and conflict-resolution DTOs plus `ConflictReviewPolicy`.
- `shared/.../api/ApiContracts.kt` — `ApiPaths` (every REST endpoint name) and every request/response DTO. Treat this file as the contract between all three apps and the future backend; when adding a feature, extend this file first.
- `shared/.../protocol/` — the Key Cabinet Communication Protocol's frame layer (`SplitNibbleCodec`, `KeyCabinetCrc8`, `KeyCabinetFrame`/`KeyCabinetFrameCodec`) and command driver (`KeyCabinetLink`, `SerialTransport`), all pure Kotlin with unit tests against the vendor doc's worked examples (`shared/commonTest/.../protocol/`, including `FakeSerialTransport` for hardware-free testing). No serial I/O lives here — see boundary #1.
- `webApp/src/wasmJsMain/kotlin/com/ekms/web/` — **frozen, not built** (see Module architecture). All 19 supplier-manual routes live in two monolith files, `WebPortalScreens.kt` and `WebPortalModels.kt` (`internal class WebPortalStore`), not a one-file-per-area split despite the doc comment that used to describe one. Historical reference only — `web/` has already surpassed several of its gaps (see Project Status), so don't assume `webApp`'s audit findings still describe `web/`.
- `web/src/` — the live Super Admin portal (React+Vite+TypeScript). `src/api/client.ts` is the backend client; `src/App.tsx` is routing/shell; `src/pages/*.tsx` are the workflow screens; `src/components/MalaysiaUnitsMap.tsx` is the unit-hierarchy map view; `src/components/ErrorBoundary.tsx` guards against blank-screen failures (e.g. Leaflet map load failures in Edge).
- `backend/src/routes/` — one router file per resource (`sites.js`, `terminals.js`, `users.js`, `keys.js`, `keySlots.js`, `accessGrants.js`, `recycleBin.js`, `credentials.js`, `audit.js`, `sync.js`) plus `phase4.js`, which bundles several newer routers together (event definitions, schedules, personnel/key groups, multi-auth rules, appointments + reasons + permissions, reports). `phase4.js`'s routers are fully implemented and mounted in `backend/src/index.js`; unlike the frozen `webApp` (which never wired any of this), `web/`'s API client (`web/src/api/client.ts`) has list/create/update/delete methods for all of these, called from real pages (`SimpleResources.tsx` for events/schedules/groups/appointment-reasons, `MultiAuthPage.tsx`, `AppointmentsPage.tsx`, `LogsPages.tsx`) — see boundary #4 for the PATCH/`expectedRevision` details and the caveat that this hasn't been compiled/run yet in any dev environment.
- `terminalApp/src/main/java/com/ekms/terminal/hardware/` — `AndroidSerialTransport` (implements `shared`'s `SerialTransport` against the vendor serial AAR), `CabinetHardwareController` (owns the connection, background executor, and guided enrolment/return flows on top of `KeyCabinetLink`), plus the separate `/dev/ttyS2` public-card-reader path (`PublicM1CardReader`/`PublicCardReaderController`) and NFC/fob enrollment; `terminalApp/.../ui/` — Terminal-side admin and enrollment screens; `terminalApp/.../data/TerminalAdminStore.kt` — local terminal-side state/outbox.
- `mobileApp/src/main/java/com/ekms/mobile/` — currently a minimal Super Admin companion shell.

## Working in `docs/`

`docs/API_HANDOVER_SUPER_ADMIN V{1..4}.md` are dated snapshots of the API handover — V4 is the latest; don't edit older versions, add a new one instead if asked to revise the handover. `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` is the current living Website spec and includes an acceptance checklist — consult it before changing web portal workflow behavior.

## Terminal App UX Baseline (Production — baseline + defined enhancements)

terminalApp's core screens, flow order, and terminology continue to follow
the Smart Key Cabinet User Manual V2.1 as the baseline. This is no longer
a strict exact-clone requirement — production behavior may extend beyond
the manual, but only via enhancements explicitly documented in this
section. Undocumented deviation from the manual is still a bug; a
documented enhancement below is not.

Baseline (unchanged from the manual):
- Standby -> tap-to-wake -> login screen
- Login: personnel card swipe, key card swipe (returns directly),
  account/password, Face Recognition, Fingerprint Recognition
- Key retrieval: Layout Display / List Display, user-toggleable
- Return flow: swipe -> door/blue light -> insert -> done
- Admin Menu: all 10 items from the manual (terminal name, Key Cabinet ID,
  password change, server address, activation code, key node setting, MAC
  address, Key Return Certification, return video toggle, retrieval video
  toggle)
- Color theme and first-start screensaver remain the only purely cosmetic
  differences, as before.

Documented production enhancements (beyond the manual):

1. **Key Take Flow — active feedback and timed door-close enforcement**
   (supersedes the manual's bare "door opens, insert key, done" return
   description for the TAKE side specifically):
   - Continuous beep from unlock through door-close confirmation
   - Take Warning Time: a Super Admin-configurable setting, in seconds,
     stored per-terminal (add alongside the existing Key Return
     Certification / video toggles in the Admin Menu's terminal settings)
   - Timer starts only on confirmed key-fob removal (0x16 bolt-removed),
     never on unlock or door-eject alone
   - If the timer expires before the door is reclosed, play a "please
     close the door" voice line
   - A door-left-open state (voice line played, door still not closed) is
     logged as a distinct event from the take itself, and may surface as a
     standing Super Admin alert
   - A secondary, separate timeout governs the case where the door is
     ejected but the key fob is never removed at all (open, no removal) --
     see open implementation question in the Key Take Flow spec; do not
     leave this case unbounded

   All new admin-configurable timing values follow the same settings
   storage/sync pattern as existing terminal settings (Key Return
   Certification, video toggles) -- do not introduce a separate ad-hoc
   config mechanism.

2. **Key Return Flow — active feedback and timed door-close enforcement,
   direction-reversed from the Key Take Flow** (supersedes the manual's
   bare "door opens, insert key, done" description for the RETURN side,
   layered on top of the existing Phase 3/9 return flow: swipe -> door/
   blue light -> insert):
   - The target node is identified via key-card UID swipe, resolved
     through `CardUidResolver` (shared with the login flow's personnel-
     card path -- one resolver, never re-derived per flow)
   - From the moment of that swipe, a 20-second no-insert abandonment
     ceiling starts, concurrently with the optional Key Return
     Certification login gate if that setting is enabled -- the clock is
     not paused for login, so a slow certification login eats into the
     same 20s window
   - Continuous beep from confirmed door-open through door-close
     confirmation
   - Insertion detected within 5s of door-open cancels the escalation;
     not detected by 5s plays a "please insert the key" voice line once
     and raises beep volume (same 1s interval) until insertion or
     abandonment
   - Not inserted by the 20s ceiling (measured from the original swipe,
     not from door-open): locks the fob slot, turns the light off, logs a
     distinct abandoned-return event, and alerts both the terminal user
     and Super Admin (mobile app) -- deliberately not the same as Take
     Flow's abandonment, which only re-locks an already-unlocked fob with
     no two-party alert, since nothing was ever removed
   - On confirmed insertion, the fob is locked immediately; the light
     stays on through the Door-Close Warning Time countdown -- a NEW,
     separate Super Admin-configurable setting from Take Warning Time
   - If the door is still open when that countdown expires, play a
     "please close the door" voice line and log a door-left-open event --
     distinct from both the return record and the abandoned-return
     notification, since never-inserted / inserted-but-door-left-open /
     successful-close are three different failure modes, never collapsed
     into one
   - Every exit path (success, door-open hardware fault, abandoned-
     return) leaves the light off and releases the concurrency guard

   Door-Close Warning Time follows the same settings storage/sync pattern
   as Take Warning Time and the other terminal settings -- same rule as
   Take Flow, no separate config mechanism.

Any future enhancement beyond the manual must be added to this list
explicitly before being implemented -- this file is the single record of
where terminalApp intentionally diverges from the supplier manual and why.

Architecture note (unchanged): business logic stays in `shared`, hardware-
specific code stays Android-only in terminalApp.

## NFC UID Resolution Rule (permanent)

Personnel NFC cards and key NFC cards share the same physical medium and
UID space — there is no hardware-level way to distinguish a personnel
card from a key card. This must always be resolved in software via UID
lookup, never assumed based on which screen or flow triggered the scan.

Rules that must never be violated by future changes:

1. NFC enrollment (users and keys) is a simple manual capture: scan once
   during registration, store the raw UID against that record. No feature
   extraction, unlike fingerprint/face.

2. Password login must always remain a valid path, independent of whether
   NFC/fingerprint/face is enrolled for that user. This is required for
   bootstrapping the first Super Admin (nothing else can be enrolled
   before first login) and remains a permanent fallback afterward — never
   remove password login as an option.

3. Any code path that receives a scanned UID (from the public card reader
   on ttyS2, or elsewhere) must resolve it by checking BOTH the registered
   User-card UID set and the registered Key-card UID set:
  - Match in Users -> login
  - Match in Keys -> key return trigger
  - No match in either -> unrecognized card error, no silent fallback

   Do not write new NFC-triggered flows that assume a scanned UID's
   meaning in advance (e.g. assuming "any scan on the login screen must be
   a user card") — always resolve via lookup, since the physical scan
   itself carries no type information.

The UID lookup logic lives in `shared` (pure data lookup, no Android
dependency) so terminalApp and any future web/mobile UID-based flows
reuse the same resolution logic rather than reimplementing it.

## Web/Mobile App UX Consistency

This section's rules apply to whichever app is the current Super Admin web portal — `web/` (React) going forward, `webApp` (frozen Kotlin/Wasm) as historical reference — plus `mobileApp`. Both are Super Admin-facing, not operator-facing — they do
not need to replicate the physical swipe/insert return flow or hardware
login methods (fingerprint, face, NFC) from the supplier manual. Those stay
terminal-only.

What they SHOULD carry over from terminalApp for consistency:
- Layout Display / List Display toggle for viewing keys — same underlying
  concept (visual cabinet-grid view vs simple list view), same shared state
  model, adapted to a larger screen
- Access grant model and terminology: same "which user can access which
  keys" concept as terminalApp, not a redesigned admin-only version
- Recycle Bin behavior: 60-day soft-delete window, Super Admin-only
  visibility and restore, matches terminalApp/backend rules exactly
- Sync-conflict handling: when an offline terminalApp edit conflicts with a
  webApp edit, the review UI must present both versions clearly — never
  silently resolve

What's DIFFERENT for webApp/mobileApp:
- Full CRUD for users, keys, sites, and terminals (terminalApp mostly reads
  and executes, it does not manage configuration)
- Bulk actions (e.g. batch access grant changes) are admin-portal-only
- No camera/video recording UI — that is a terminalApp/backend concern only

Keep all of this logic (layout/list state, access grant rules, Recycle Bin
timing, conflict data shape) in the `shared` module so webApp and
terminalApp consume the same source of truth rather than reimplementing it.

## Project Status

### How to read this file (status truth — read this first)

Project Status below is an **append-only session diary**. Older bullets were often left in place when later work superseded them. **If two bullets disagree, trust the newest Completed bullet and the sections in this "status truth" block — not an earlier Phase note that still says "NOT fixed".**

| Topic | Conflicting claims that used to confuse agents | Current truth (as of commit documenting PATCH + door-eject cleanup) |
|---|---|---|
| **Door eject on key take** | Phase 10 says `releaseKeyForPickup` never calls `ejectDoor()` and is still deferred. Later text says the bug is already fixed / method deleted. | Production take path is **`beginKeyTake`** (blue light → unlock → **ejectDoor** → confirm open). `releaseKeyForPickup` is **deleted dead code**. Door-eject on take is implemented in code **and hardware-verified** on a real F7G18P (see Completed "Combined hardware verification session" below) — sequence, 20s abandonment ceiling, and removal-triggered countdown all confirmed correct. Two unrelated bugs found in the same session (beep-continuity, Return-flow double-abandonment) remain open — see Known issues. |
| **Fingerprint/face enrollment hardware status** | Earlier Known issues said "fully coded and building but not hardware-verified — no R503 module or camera was available this session." | **Superseded.** Real R503 hardware (enroll/duplicate-detect/revoke) and real camera (preview/liveness/capture/revoke) are both now hardware-verified — see Completed "Combined hardware verification session" below. What's still open is narrower: the **backend credential-sync call** (`completeCredentialEnrollment`/`revokeCredentialEnrollment`) fails on every kind (confirmed for fingerprint and face; NFC untested but shares the same code path) due to a systemic zod schema bug — see Known issues. Device-local hardware behavior is verified; server-side sync of that data is confirmed broken. |
| **Key-card swipe → return** | Phase 10 says real enrolled keys never resolve a node (`matchedKey` always null). Later Known issues says fixed via `managedKeyAndSlotFor()`. | **Fixed** during Key Return Flow. Phase 10 "NOT fixed" text is historical only. |
| **Web PATCH / in-place edit** | Early spot-check and older boundary notes said `web/` has create+delete only, no update. Later Completed says PATCH wired for ~10 resources. Intro used to say portal still "hardcoded/in-memory for others." One authoring note said "no Node" as if the API were unreachable. | **Code exists** in `web/src/api/client.ts` + edit UIs for Units/Terminals/Personnel/Keys/Permissions/Events/Schedules/Groups/Multi-Auth. Production API is **already at `https://kms-cvt.com/v1`** — verification is “rebuild/redeploy `web/` and click Edit,” not “stand up a backend.” A machine without local Node cannot `npm run build` there; it can still hit the live portal. Treat as **implemented, end-to-end edit verify pending**. Frozen `webApp` still has no edit UI (irrelevant — do not build it). |
| **Personnel on web** | Next steps said "rebuild Personnel management properly on web/". | Core list/create/delete/update + card-enrollment status already exist on `web/` Personnel page. Remaining work is UX/audit polish and standing-alert features — **not** a greenfield rebuild. |
| **Theme / fonts (terminal)** | Cavotec rewrite paragraph mentions Inter and older hex tokens, then says a later pull changed Outfit / hex. | Trust **`terminalApp/.../ui/theme/Color.kt` and `Typography.kt`** in the tree, not hex values quoted in older diary paragraphs. |
| **Take/Return Flow audio** | Take/Return Flow Completed bullets above say `AudioFeedbackController` is a no-op stub. | **No longer true.** Real `SoundPool` (beep) + `MediaPlayer` (voice lines) implementation shipped and hardware-verified — see Completed "Implement real audio feedback" below. |
| **Terminal pairing** | "Terminal → live API" section documents manual Admin Menu pairing (Set server address + Key Cabinet ID + Super Admin sign-in). | **Still the only working pairing path in production today.** Backend/shared (6-digit code endpoints) and terminalApp's pairing-code screen are both **implemented and committed** (see Completed "Key Cabinet + Personnel registration" and "terminalApp pairing-code flow"), but the backend change is **not yet deployed to the VPS** and the flow has **not been run live** — `web/` also has no registration UI yet to generate a code with (see "Web Portal — Pending UI Work" section). Do not tell a user the pairing code flow works today; it's built, not live. |

**Agent rule:** When deciding whether a bug is open, check **Known issues / not yet resolved** and **Next steps** first. Only use older Completed Phase notes for history. Do not re-open struck-through or superseded items.

### Completed
- Step 1-3: shared policy/sync/Recycle Bin foundation, Super Admin Users &
  Credentials, Sites & Terminals UI with cabinet-config validation
- Step 4: Keys, cabinet slots, and access grants (ManagedKey/KeySlot/
  AccessGrant + KeySlotAccessPolicy node-address validation), wired into
  webApp and terminalApp
- terminalApp UI/UX rebuilt to strictly match Smart Key Cabinet User
  Manual V2.1 (see "Terminal App UX Baseline (Production — baseline +
  defined enhancements)" section):
  - Phase 1: Login screen (all 4 methods)
  - Phase 2: Key retrieval (Layout/List toggle)
  - Phase 3: Return flow
  - Phase 4: Admin Menu (all 10 items)
  - Phase 5: Settings wired to behavior (toggles actually function)
- Phase 6: hardware frame protocol layer implemented in `shared`
  (split-nibble encode/decode, CRC8, frame assembly/parsing), unit tested
  against worked examples in the vendor spec. Pure protocol logic only, no
  serial I/O.
- Phase 7: real serial connection to key nodes. `shared/.../protocol/`
  gained `SerialTransport` (the minimal transport interface) and
  `KeyCabinetLink` (the full node command set from the appendix table,
  500 ms/3-attempt timeout+retry, and the section 10.4
  one-electromagnet-at-a-time guard — `engageElectromagnet` throws
  `ElectromagnetConcurrencyException` instead of transmitting if a
  different node is already engaged), unit tested with `FakeSerialTransport`
  (no physical device). terminalApp's `AndroidSerialTransport` implements
  the real `/dev/ttyS1` @ 19200 8N1 side; `CabinetHardwareController` now
  drives `KeyCabinetLink` instead of a terminalApp-local protocol
  implementation. The old `KeyCabinetProtocol.kt`/`CabinetSerialPort.kt`
  (duplicated frame/CRC logic in Android code) were deleted, fully
  superseded by the shared layer.
- Phase 8: public card-swipe reader (section 9), independent from the
  node-level 0x15/0x17 card reads — confirmed genuinely separate serial
  ports/protocols, not just adjacent code (`PublicM1CardReader` on
  `/dev/ttyS2` @ 9600 8N1, ASCII poll `02 AF DD`/parse, vs. `KeyCabinetLink`
  on `/dev/ttyS1` @ 19200, split-nibble/CRC8 framing — verified the vendor
  AAR's `Device` defaults to 8 data bits/1 stop/no parity when only
  path+speed are set, so both already get correct 8N1 with no explicit
  override needed). Added the missing piece: `PublicCardReaderController`
  now starts automatically when `TerminalAdminApp` is idling at the login
  screen and stops automatically otherwise (including on app exit), and a
  detected card feeds the key-card-swipe return trigger from phase 3 (same
  entry point the phase 3 manual tap already used, so the flow stays
  testable with no reader attached).
  - Personnel-card swipe was NOT wired at this point — see the card-UID
    disambiguation fix below, which resolves it.

- Phase 9: real hardware wired into retrieval/return.
  **Electromagnet direction reconfirmed** (a phase 9 request initially
  described it backwards — 0x14 for retrieval, 0x13 for return — which
  would have inverted the field-verified mapping; asked and kept the
  existing 0x13=unlock/0x14=lock resolution). `CabinetHardwareController`
  gained `releaseKeyForPickup` (0x13 engage, then 0x16 Test Micro Switch
  must confirm the bolt is actually gone before reporting success — an
  acknowledged command alone is not treated as proof) and a two-phase
  `beginKeyReturn` (0x11 Blue Light On + 0x23 Eject Door) /
  `waitForKeyInserted` (polls 0x16 until bolt-present, then 0x14 release +
  0x12 Blue Light Off). Both auto-connect the cabinet with saved/default
  settings if not already open, since an operator reaches these directly
  from login with no admin "Connect" step first.
  `TerminalKeyRetrievalScreen` shows a "Releasing…" pending state and
  disables the rest of the grid/list while one release is in flight — the
  phase 7 electromagnet guard is the backstop, not the primary defense
  against a double-tap. `TerminalKeyReturnScreen` now completes only once
  hardware confirms insertion (falls back to the old fixed-delay behavior
  only when `resolveReturningKey` can't identify a node to address).
  Concurrency guard re-verified explicitly with a new test
  (`KeyCabinetLinkTest`, "two retrieval attempts in quick succession") on
  top of the existing phase 7 coverage, not just assumed still correct.
  Personnel-card login and real card-to-key return identification were
  stubbed at this point; both are now resolved by the card-UID
  disambiguation fix below.

- **Card-UID disambiguation fix**: personnel NFC cards and key NFC cards
  share the same physical medium and UID space — there is no hardware-level
  way to tell them apart, so a scanned UID's meaning can only be decided in
  software, by looking it up, never assumed from which screen triggered the
  scan. Added `shared/.../domain/CardUidResolution.kt`
  (`CardUidResolver`/`CardUidMatch`, pure decision over two already-resolved
  nullable record IDs — no raw UID or Android dependency, so both
  terminalApp and any future web/mobile UID flow apply the same rule,
  including how a double-enrollment is surfaced as `Ambiguous` rather than
  silently picking a side), unit tested (`CardUidResolutionTest`, 4 cases).
  Generalized the old key-only `EncryptedFobEnrollmentStore` into
  `EncryptedUidEnrollmentStore` (namespaced by a `storeName` constructor
  param, so personnel-card and key-card enrollments live in two fully
  separate Keystore-backed stores); `TerminalAdminApp` now owns one instance
  of each. Added `CardEnrollmentScreen` (Dashboard → "Card enrolment") for
  the one-scan manual capture requirement (no feature extraction, unlike
  fingerprint/face) — enrolling into one store proactively rejects a UID
  already enrolled in the other, as defense in depth alongside the
  resolver's explicit `Ambiguous` handling. Fixed the actual runtime bug:
  `TerminalAdminApp`'s public-reader `onCardDetected` used to assume every
  scan was a key-card return trigger and discard the UID; it now looks the
  UID up against both stores and branches on `CardUidResolver`'s result —
  personnel match signs in via the new password-independent
  `TerminalAdminStore.authenticateByUserId`, key match passes the resolved
  `ManagedKey` straight into the return flow (now carried through
  `ReturnFlow.AwaitingCertification.matchedKey` so certification doesn't
  lose it), no match or an ambiguous match surfaces an explicit error, never
  a silent fallback. `resolveReturningKey`'s "only key currently taken"
  heuristic remains only as the fallback for the login screen's UID-less
  manual key-card tap (a hardware-free testing convenience). Password login
  is untouched and remains valid independent of any NFC enrollment,
  including for the very first Super Admin sign-in.
  **Verified this session:** `:terminalApp:compileDebugKotlin`,
  `:terminalApp:assembleDebug`, and `:shared:allTests` all pass. Not yet
  exercised: manual UI walkthrough in an emulator/device (this fix was
  verified by compile/test only, not by running the app), and — same as
  Phase 9 above — no physical F7G18P run of the section 9 reader against
  real enrolled cards.

- **Phase 10: physical F7G18P hardware verification.** First real run of
  phases 7-9 and the card-UID fix against actual hardware (previously
  exercised only through `FakeSerialTransport` and Gradle compile/test).
  Driven interactively over ADB (screenshots + `input tap`/`input text`)
  against a connected device, with the user performing the physical
  actions (cabinet door, key fob, cards) on request.
  - **Confirmed correct on real hardware:** cabinet connection at
    `/dev/ttyS1` @ 19200 8N1; the 0x13=unlock/0x14=lock electromagnet
    direction and `testMicroSwitch`/0x16-0x17 bolt-detection (both
    absence and presence); the return flow's full sequence (blue light →
    door eject → insert → secure → light off); the section 10.4
    one-electromagnet-at-a-time concurrency guard (blocks a second node
    with zero hardware side effects, purely client-side in
    `KeyCabinetLink`); the public card reader detecting real M1 cards;
    the guided key-enrolment screen's full release/NFC-compare/save/
    return/auto-secure cycle; personnel-card swipe login end-to-end
    (`CardUidResolver` → `authenticateByUserId`).
  - **Bug found and fixed this session:** `CardEnrollmentScreen`'s
    `PublicCardReaderController` was built inside a keyless `remember {}`,
    so its `onCardDetected` closure permanently captured whichever
    user/key was selected at first composition, ignoring later
    selections — a scan could silently enrol to the wrong record despite
    the UI showing the correct one selected. Fixed with
    `rememberUpdatedState` on the category/selected-user/selected-key
    reads. Also added a "Revoke this record's card" button (the store
    already had `revoke()`; nothing called it), needed to clean up and
    re-verify the fix live.
  - **Bug found and fixed this session:** `CardEnrollmentScreen`'s key
    list was wired to `retrievalKeys` (`KeySlotDemoData.keys()` — hard-
    coded fixtures like "HQ Service Vehicle") instead of `snapshot.keys`
    (the real `TerminalKey` records), so a key-card could only ever bind
    to a fictional demo key, never a real enrolled one. Fixed by changing
    `CardEnrollmentScreen`'s `keys` param from `List<ManagedKey>` to
    `List<TerminalKey>` and passing `snapshot.keys`.
  - **Bug found in Phase 10, FIXED later (do not reopen):** `TerminalAdminApp`'s
    public-reader `CardUidMatch.Key` branch originally looked up the matched key
    in demo `retrievalKeys` (`ManagedKey` fixtures) using a real `TerminalKey.id`
    — two incompatible ID spaces, so `matchedKey` was always null and return
    never drove hardware. **Superseded:** Key Return Flow added
    `managedKeyAndSlotFor()` to bridge a real `TerminalKey` into a synthetic
    `ManagedKey`/`KeySlot` at that call site. See status-truth table above.
  - **Bug found in Phase 10, SUPERSEDED (do not patch `releaseKeyForPickup`):**
    At the time, `CabinetHardwareController.releaseKeyForPickup` unlocked without
    `ejectDoor()`. **Current truth:** production retrieval no longer uses that
    method — it goes through Key Take Flow → `beginKeyTake` (includes
    `ejectDoor()`). Dead `releaseKeyForPickup` was deleted later. Hardware
    verification of `beginKeyTake` is still outstanding (Next steps).
  - Both Phase-10 fixed bugs (CardEnrollmentScreen stale closure; demo-key list)
    and the two items above (later resolved/superseded) are logged in session
    memory (`phase10_retrieval_door_eject_bug.md`, `phase10_card_uid_bugs.md`)
    for history — those memory files may still describe the pre-fix state.

- **Key Take Flow (production enhancement, not in the supplier manual)**:
  implements CLAUDE.md's "Terminal App UX Baseline (Production)" §1 —
  supersedes the manual's bare "door opens, insert key, done" description
  for the TAKE side specifically. Selecting an available key in
  `TerminalKeyRetrievalScreen` now hands off to a dedicated full-screen
  `TerminalKeyTakeScreen` (same architecture as the Section 3 return
  flow) instead of releasing inline; the grid's old `pendingKeyId`/
  "Releasing…" state was removed as obsolete.
  `CabinetHardwareController` gained three new methods: `beginKeyTake`
  (Blue Light On → Unlock 0x13 → Eject Door 0x23 → confirm open via 0x22,
  re-locking/light-off on any failure), `pollForKeyRemoval` (two
  independent timers from door-open: 5 s raises beep volume only, 20 s is
  the hard abandonment ceiling that auto re-locks and lights off before
  reporting), and `waitForDoorCloseAfterTake` (polls until closed; the
  Admin Menu-configurable Take Warning Time only triggers a "please close
  the door" voice-line callback at expiry, polling continues indefinitely
  until the door actually closes). A new `takeMonitoring` guard (mirrors
  `returnMonitoring`) blocks admin-console commands during an active
  take. Field-verified the 0x22 door-status byte mapping this session
  (0x00 = open/ejected, 0xFF = closed) and documented it on the new
  `isDoorOpen` helper.
  New `TerminalCabinetSettings.takeWarningTimeSeconds` (default 15,
  1-300) added to the Admin Menu alongside the existing Key Return
  Certification/video toggles, same settings storage. New
  `AudioFeedbackController` (beep/voice-line) is a no-op stub, matching
  `VideoRecordingController`'s established pattern — no confirmed audio
  hardware/asset pipeline yet.
  Added a local event outbox to `TerminalAdminStore`
  (`logEvent`/`eventOutbox`) persisting shared `AuditEvent`-shaped
  records for the flow's four outcomes (`KEY_TAKEN`, and three new
  `AuditEventType` values: `KEY_TAKE_FAILED`, `KEY_TAKE_ABANDONED`,
  `KEY_TAKE_DOOR_LEFT_OPEN`) — nothing drains this yet since there is no
  backend/sync transport, but it is real local persistence, not a stub.
  **Explicitly deferred (separate follow-up task, not done here):**
  surfacing a standing Super Admin alert for the door-left-open case in
  webApp (Super Admin view) and mobileApp (targeted user + Super Admin
  view) — both apps run on local demo data with no real cross-app sync
  yet, so this would necessarily be a demo-data-driven mockup rather than
  a live connection to terminalApp's event; deliberately scoped out of
  this pass.
  **Verified this session:** `:terminalApp:compileDebugKotlin`,
  `:terminalApp:assembleDebug`, `:shared:allTests` all pass. Not yet
  exercised: no manual UI walkthrough and no physical hardware run of the
  new flow (door-open confirmation, the two removal timers, the warning
  countdown) — built and verified by compile/test only.

- **Key Return Flow (production enhancement, not in the supplier
  manual)**: implements CLAUDE.md's "Terminal App UX Baseline
  (Production)" §2 — direction-reversed mirror of the Key Take Flow,
  layered on top of the existing Phase 3/9 return flow (swipe -> door/
  blue light -> insert) rather than replacing its entry point.
  `TerminalKeyReturnScreen` was rewritten in place with the same
  stage-machine architecture as `TerminalKeyTakeScreen`, while
  deliberately preserving the pre-existing null-key manual-tap fallback
  (`resolveReturningKey`'s hardware-free testing convenience) untouched —
  that path keeps its original simple fixed-delay behavior with no
  timers, no hardware, nothing logged.
  `CabinetHardwareController` gained `beginKeyReturnFlow` (Blue Light On →
  Eject Door 0x23 → confirm open via 0x22 — never touches the
  electromagnet, since nothing is locked to an empty node yet),
  `pollForKeyInsertion` (two independent clocks, not one reused: a 5s
  beep-volume threshold measured from door-open, and an externally-
  supplied absolute `abandonAtEpochMillis` deadline computed by the
  caller *at the original card swipe* — not from door-open, and not
  paused for however long an optional Key Return Certification login
  took), and `waitForDoorCloseAfterReturn` (mirrors the Take Flow's
  equivalent). Reuses the pre-existing `returnMonitoring` guard (the same
  one `waitForKeyInserted` used) rather than adding a new flag, since
  both mean "a key return is being monitored."
  **Fixed the Phase-10-deferred key-schema bug as part of this task** (at
  the user's explicit direction, since the new flow depends on it): added
  `managedKeyAndSlotFor()` in `TerminalAdminApp` to bridge a real
  `TerminalKey` (what card enrollment actually binds to) into a synthetic
  `ManagedKey`/`KeySlot` pair at the `CardUidMatch.Key` call site, so a
  real card swipe can now identify a real node — previously this lookup
  always failed (`retrievalKeys.firstOrNull { it.id == match.keyId }`
  compared a real `TerminalKey.id` against demo `ManagedKey` fixtures).
  This is a targeted bridge for the one call site that needed it, not a
  full unification of the two schemas — see
  `docs/Backend_Integration_Handover.md` for the underlying gap.
  `ReturnFlow` now carries `matchedSlot` and `abandonAtEpochMillis`
  explicitly (previously only `matchedKey`, with slot re-derived from
  demo `retrievalSlots`, which would not have worked for a real
  `TerminalKey`-backed match). A new top-level `LaunchedEffect` in
  `TerminalAdminApp` races the same swipe-time deadline while an optional
  Key Return Certification login is pending — if it fires first, the
  flow is logged as abandoned with no hardware cleanup needed (nothing
  was ever engaged at that stage), and is naturally cancelled by Compose
  the moment `returnFlow` moves past that exact `AwaitingCertification`
  instance.
  New `TerminalCabinetSettings.doorCloseWarningTimeSeconds` (default 15,
  1-300) added as a genuinely separate setting from `takeWarningTimeSeconds`,
  same Admin Menu/storage pattern. `AudioFeedbackController` gained
  `VoiceLine.PLEASE_INSERT_THE_KEY`.
  Logs four outcomes via the same local event outbox the Take Flow uses:
  `KEY_RETURNED` (existing type, reused) plus three new `AuditEventType`
  values — `KEY_RETURN_FAILED`, `KEY_RETURN_ABANDONED`,
  `KEY_RETURN_DOOR_LEFT_OPEN`. **Deliberate asymmetry with Take Flow,
  not shared code:** Return's abandonment additionally records that both
  the terminal user and Super Admin need alerting (via the outbox
  record's `detail` field, since actual two-party delivery is the same
  deferred webApp/mobileApp follow-up as Take Flow's standing-alert UI),
  where Take's abandonment only ever implied a single Super Admin-facing
  log entry.
  On successful return, `takenKeyIds` is updated immediately in
  `handleReturnFlowOutcome`'s `Success` case (not on `Failed`/`Abandoned`,
  since in those cases the key's physical state is either unconfirmed or
  known-unchanged) — note this bookkeeping is scoped to the demo
  retrieval grid only; a real `TerminalKey` return via card swipe was
  never reflected in `takenKeyIds` to begin with, since the grid and the
  real key/card-enrollment system are separate demo-vs-real data sources
  (see the schema-bridge note above).
  **Verified this session:** `:terminalApp:compileDebugKotlin`,
  `:terminalApp:assembleDebug`, `:shared:allTests` all pass. Not yet
  exercised: no manual UI walkthrough and no physical hardware run of the
  new flow — same caveat as the Key Take Flow above.

- **Post-pull verification + backend/portal reconciliation.** After pulling `origin/master` (which had diverged with an in-progress, staged-but-uncommitted merge left over from an earlier pull attempt), verified the merge was safe before completing it: diffed base/local/remote file sets and confirmed **zero files were touched by both sides** (local's only change was the `retrievalTerminal` type-mismatch fix below; remote's only changes were 6 files under `web/`), so there was no possibility of the earlier silent-revert failure mode recurring. Completed the merge, pushed. Then ran a full verification pass:
  - Fixed a build-breaking bug found on first build after the pull: `TerminalAdminApp.kt`'s `retrievalTerminal` mixed two unrelated types (`Terminal` from a downloaded server snapshot vs. `ManagedTerminalOption` from `KeySlotDemoData`'s fallback), which Kotlin silently widened to `Any`, breaking every `.siteId`/`.id`/`.copy()` access on it. Fixed with a `Terminal.toManagedTerminalOption()` adapter used at both assignment sites, so `retrievalTerminal` is consistently `ManagedTerminalOption`. Verified via `:terminalApp:compileDebugKotlin` and `:terminalApp:assembleDebug`.
  - `:terminalApp:build` (compile, lint, debug+release assemble) passes clean.
  - `:shared:testDebugUnitTest`/`:shared:testReleaseUnitTest` (JVM) pass; the wasm leg of `:shared:allTests` fails at `kotlinWasmStoreYarnLock` ("Lock file was changed") — confirmed pre-existing/environment, not caused by this pull (same failure reproduced before touching anything).
  - **Discovered `webApp` is now frozen and excluded from the Gradle build** (`settings.gradle.kts` commit `54d67ae`, same commit that added `web/`): "Website portal is now React in `/web`... Kotlin/Wasm webApp is frozen." `web/README.md` confirms: "Super Admin portal replacing the Kotlin/Wasm `webApp` module." This was not previously reflected anywhere in this file — see the Module architecture table and Project intro, now updated.
  - Confirmed current backend integration scope directly from code: `terminalApp` has real offline-first sync (`TerminalApiClient`/`TerminalSyncCoordinator`/`TerminalSyncOutbox`/`TerminalServerCache`); `web/`'s `src/api/client.ts` calls the real backend directly (no offline queue); `mobileApp` has zero network code anywhere (grepped, zero matches) — still 100% local demo data.
  - **Super Admin portal audit — ran against the frozen `webApp` first (full 13-section detail below), then spot-checked against `web/` and found `web/` has already moved well past what the `webApp` audit would suggest — do not treat the `webApp` findings as a proxy for `web/`'s current state.** `webApp` findings: Login, Data Synchronization, and (partially) Report/Operation Logs were genuinely wired to the real backend; Unit/Terminal/Personnel/Key/Permission Settings had list/create/soft-delete but **no in-place edit UI at all** (`ApiClient.updateSite/updateTerminal/updateUser/updateKey/updateKeySlot/updateAccessGrant` all defined against working backend `PATCH` routes but zero call sites); Event Setup, Schedule Settings, Multi-Authentication Management, and Appointment Authorization were **entirely hardcoded/in-memory demo data**, despite the backend already having full mounted routers for all of them (`backend/src/routes/phase4.js`). `webApp`'s `WebPortalModels.kt` also has several fully dead symbols from an earlier local-mutation design (`store.recycleBin`/`PortalDeletedRecord`/`restore()`/`purge()`, `archivedLifecycle()`/`restoredLifecycle()`/`purgedLifecycle()`, `DEMO_CREATED_AT`) — moot now that the module is frozen.
    **`web/` spot-check (historical — superseded by PATCH rollout below):** at the time of this audit, `web/src/api/client.ts` already had real `list`/`create`/`delete` for every area `webApp` was missing, but **no `update`/PATCH**. That gap was later closed — see Completed “PATCH/update support” and the status-truth table. Do not treat this bullet as current state.

- **Cavotec terminalApp visual theme rework** (color/typography/spacing refresh only — screen structure, flow order, and button placement were left exactly as-is per the request). Added `terminalApp/.../ui/theme/{Color.kt,Typography.kt,Theme.kt}` (`EkmsColors`/`LocalEkmsColors` for the two brand tokens with no Material3 slot — Success/Warning — plus every M3 `ColorScheme` slot exhaustively specified via alpha-compositing to prevent the default Material purple from bleeding through unset slots) and a reusable `StatusRingCard` composable, applied consistently across every hardware/lifecycle status indicator (login swipe panel, key retrieval grid, admin dashboard, hardware status card) rather than just the key grid. Verified live on real hardware via ADB screenshots for blue/grey/amber tones; the red/alarm tone was never caught in three attempts against live hardware timing and was explicitly deferred by the user ("move on for now, will polish this later") — still not confirmed as of this writing. **Note:** an external pull later iterated further on this same theme (see below) — the font and exact hex tokens described in the original request no longer match what's in the tree; treat `Color.kt`/`Typography.kt` as the current source of truth over this paragraph's specifics.

- **Personnel/credential sync between terminal and backend, and a further terminalApp/web theme iteration** (pulled in via `719ce68`/`0dcb233`, not originally authored in this session): `TerminalApiClient` gained `listUsers`/`listSites`/`createUser`/`getTerminal`/`listUserCredentials`/`completeCredentialEnrollment`/`revokeCredentialEnrollment` — the terminal's Enroll User flow can now create a real backend `AdminUser` (not just a local `TerminalUser`), and `TerminalAdminStore` gained a personnel cache (`replaceCachedPersonnel`/`personnelForEnrollment`) backing it. Backend gained `POST /v1/admin/users/:userId/credentials/complete` (new `credential_statuses.enrollment_reference` column, migration `005_credential_enrollment_reference.sql`) which completes card enrollment with an opaque reference and explicitly rejects hex-UID-shaped payloads server-side — the first place boundary #2 ("no raw UIDs") is enforced at the API layer, not just by client convention. terminalApp's theme moved from Inter to the Outfit font family and re-tuned Cavotec hex tokens (e.g. primary `#3966B1` → `#5B7FC4`); `DataReadoutTextStyle` became a `.readout()` extension function. Three new terminalApp UI files: `CabinetConnectionHints.kt` (connection-fault diagnostic hints, `HintSeverity` LIKELY/CHECK/OK), `HardwareStatusPage.kt`, `SoftComponents.kt`. Separately, `web/` shipped its own MD3 "Cavotec" theme (`--md-sys-color-*` tokens in `styles.css`, primary `#0055a5` — a different exact blue than terminalApp's, same brand direction but not a literally shared token source between the two clients — Outfit + IBM Plex Mono, new `components/ui/` kit: `Button`/`Skeleton`/`CircularProgress`/`LinearProgress`/`SegmentedControl`).
  **Build-breaking bug found and fixed after this pull:** `TerminalAdminApp.kt:771` referenced `error.statusCode` on a caught `TerminalApiException`, but that class's real property is `status` (`TerminalApiClient.kt:402`) — looked like debug-instrumentation code (see the `/v1/debug/agent-log` incident below) added alongside the personnel-sync work. Fixed the one call site; verified via `:terminalApp:build` and `:shared` JVM tests.

- **Security fix: removed an unauthenticated debug-log endpoint and all scaffolding that fed it** (see boundary #8, added as a direct result of this). `backend/src/agentDebugLog.js` backed `POST /v1/debug/agent-log`, mounted in `index.js` before any auth middleware — self-labeled "Temporary NDJSON debug ingest for agent session 5c6d1f," left in from an earlier AI-assisted debugging pass. Investigated actual exposure before removing anything: the network-reachable leg (terminalApp's 5 call sites in `TerminalAdminApp.kt`'s Add-Personnel flow) never sent raw email/name/password — only lengths, role, counts, and truncated error text — but the endpoint itself was still a real gap (anyone, unauthenticated, could write attacker-controlled content into server log files). Separately, `backend/src/routes/users.js` called the same logging function **in-process** (not over HTTP) from inside already-authenticated routes, and that leg genuinely did log real data — up to 20 real email addresses per "list users" call, plus the submitted email on every create attempt — into plaintext files (`/root/eKMS/.cursor/debug-5c6d1f.log` etc.) and stdout, with no auth/redaction/retention policy. A third, independent call site was found later (not caught in the first pass): `web/src/pages/PersonnelPage.tsx` had its own raw `fetch('/v1/debug/agent-log', ...)` sending the same 20-email payload from the browser.
  Removed entirely: the backend route + `agentDebugLog.js` itself, the 4 call sites in `users.js`, the 1 in `sync.js`, the 5 in `TerminalAdminApp.kt` (plus `postAgentDebugLog` in `TerminalApiClient.kt`, plus one adjacent local-only `Log.i` in the same code block), and the 1 in `PersonnelPage.tsx`. Audited the rest of `backend/src/index.js`'s route-mounting order line by line and confirmed (not assumed) it was the only unauthenticated route beyond the three that are supposed to be (`/health`, `/v1/auth/login`, `/v1/auth/refresh`) — `index.js` is also confirmed the *only* file that mounts anything on the Express `app` object (grepped all of `backend/src` for `app.get/post/put/patch/delete/use`; every other route file only exports a plain `Router()`). `:terminalApp:build` passes clean after removal.

- **Fix: `releaseKeyForPickup`'s door-eject bug turned out to already be fixed — the stale reference was the actual bug.** CLAUDE.md previously described `releaseKeyForPickup` as "the production key-retrieval path" that never called `ejectDoor()` (confirmed on hardware in Phase 10). Tracing the live wiring before patching anything: `TerminalKeyRetrievalScreen`'s key-tap already goes through `onTakeKey` → `TerminalAdminApp.takeKey()` → `TerminalKeyTakeScreen` → `hardwareController::beginKeyTake` (built during the Key Take Flow work, later in the same original session, before this pass) — and `beginKeyTake` already does `blueLightOn → engageElectromagnet → ejectDoor() → checkDoorStatus` correctly. `releaseKeyForPickup` had **zero call sites anywhere** — it became dead code the moment Key Take Flow shipped, but nobody deleted it or corrected this file's description of it. Deleted the method (confirmed dead, not patched-but-unused) and its now-dangling KDoc cross-reference on `beginKeyTake`. `:terminalApp:build` passes clean. **This does NOT mean the door-eject sequence is hardware-verified** — `beginKeyTake`/`pollForKeyRemoval`/`waitForDoorCloseAfterTake` have still only been compile/assemble-verified, never run on a real F7G18P; that remains the first item in Next Steps.

- **PATCH/update support added across `web/`** (closes the boundary #4 gap flagged above). Verified backend PATCH correctness first rather than assuming from route presence: 11 resources have real, double-guarded `expectedRevision` enforcement (sites/terminals/users/keys/key-slots/access-grants individually, plus event-definitions/schedules/personnel-groups/key-groups/multi-authentication-rules/appointment-reasons via a shared `softDeleteRouter` factory in `phase4.js`). Added `update*` methods to `web/src/api/client.ts` for every resource with a real route (explicit for sites/terminals/users/keys/access-grants, a generic `updatePath()` helper — mirroring the existing `createPath`/`deletePath` — for the six `softDeleteRouter`-backed resources). Gave `ResourcePage.tsx` (the shared component behind Events/Schedules/User Groups/Key Groups/Appointment Reasons) a generic `update` prop — one change covers all five. Wired individual edit dialogs into `UnitsPage`/`TerminalsPage`/`PersonnelPage`/`KeysPage`/`PermissionsPage`/`MultiAuthPage`. Every edit path reads `expectedRevision` off the already-loaded row (no extra fetch — every backend `mapRow` already returns `revision`) and, on a `409`, shows an explicit "changed by someone else, reloading" message and reloads fresh data rather than retrying or force-overwriting. Appointments intentionally got no new edit UI (backend has no generic field-edit route by design — only review/permissions-patch, both already revision-safe); Key Slots has a working backend PATCH but no `web/` page to attach it to. Found and fixed one more `/v1/debug/agent-log` call site missed above (`PersonnelPage.tsx`). Found, did not fix (pre-existing, unrelated, dead code): `client.ts`'s `listAppointmentPermissions`/`createAppointmentPermission`/`deleteAppointmentPermission` call a backend router that only actually implements `PATCH /:id` — harmless today since no page calls them.
  **Authoring caveat (do not misread as “no backend”):** the machine that wrote the PATCH UI had no local Node, so it could not run `tsc`/`vite build` there. Production API/portal were already live at `https://kms-cvt.com`. Remaining gap is rebuild/redeploy `web/` and click through an edit on the live site — not inventing or starting a backend.

- **Implement real audio feedback (beep + voice lines) for Key Take/Return Flow.** Replaced `AudioFeedbackController`'s no-op stub with a real `SoundPool` (repeating beep — same clip, volume-only variation via a `loud` parameter, never a different file, caller owns the 1s repeat interval) + `MediaPlayer` (three one-shot voice lines: PLEASE_TAKE_THE_KEY / PLEASE_INSERT_THE_KEY / PLEASE_CLOSE_THE_DOOR) implementation, plus placeholder WAV assets under `terminalApp/src/main/res/raw/`. Deliberately skips `AudioManager.requestAudioFocus()` — dedicated kiosk device, beep and voice-line must play concurrently, never duck/pause each other. **Bug found and fixed via live hardware testing** (user reported "the voice line did not come out" — logs looked clean, start→EOS→stop with correct sample rate, but genuinely silent): `MediaPlayer.create(context, resId)` opens the data source and calls `prepare()` internally *before* returning, so a `setAudioAttributes()` call afterward is too late on this device's custom `awplayer` vendor audio pipeline. Fixed by constructing `MediaPlayer()` bare, calling `setAudioAttributes()` **first**, then `setDataSource()` via `AssetFileDescriptor`, then `prepareAsync()`. Confirmed audible on real F7G18P hardware across multiple take/return cycles (logcat: `AudioFlinger`/`awplayer` sessions at the correct 5s escalation threshold), zero crashes, beep+voice-line playing concurrently with no glitches.

- **Key Cabinet + Personnel registration with one-time pairing code (Part 1: backend + shared only — `web/` UI is out of scope for this session, owned by a different developer; see the new "Web Portal — Pending UI Work" section once Part 4 lands).** Added Terminal registration fields — `vendorDeviceId` (distinct from the backend UUID `id`), structured node arrangement (`nodeRows`/`nodesPerRow`, replacing free-text), server-validated node count `1..127` per `docs/Key Cabinet Communication Protocol.md` §7.1, and `latitude`/`longitude` — plus `staffId` on `AdminUser`/`users`. All new/changed fields documented in `shared/.../api/ApiContracts.kt` (`TerminalDto`, `UserDto`, `CreateAdminUserRequest`/`UpdateAdminUserRequest`, `TerminalUpsertRequest`), which is the canonical handoff artifact for `web/`'s developer — do not re-derive field shapes from the backend alone.
  One-time pairing code: 6-digit numeric (`crypto.randomInt`, no modulo bias), 30-minute expiry, single-use (consumed transactionally with an `affectedRows` guard so two concurrent requests can never both succeed with the same code), and a DB-ledger `pairing_attempts` rate limiter (10 failures/15min per IP — a real brute-force concern at only 1,000,000 possibilities, not optional hardening; requires `app.set('trust proxy', true)` in `index.js` so `req.ip` reflects the real client through Caddy, not the proxy's own address). New unauthenticated `POST /v1/terminal/pair-with-code` (unauthenticated by necessity — a fresh terminal has no token yet) issues `TERMINAL_DEVICE`-scoped JWTs (`sub = terminals.id`, `role: 'TERMINAL_DEVICE'` — same secret/algorithm as user tokens, parallel claim shape) and marks the code consumed. Regenerating a terminal's code (`POST /v1/admin/terminals/:id/pairing-code`) revokes the terminal's existing refresh-token sessions first — **[confirmed with the user]** a lost/reset device's old session must not keep syncing once a new code is issued for a replacement device; this is not a hypothetical, it's the actual intended use of regenerate.
  `TERMINAL_DEVICE` tokens are least-privilege by construction, not by a new blanket rule: they fail the existing `requireSuperAdmin`'s role check automatically (unchanged), and a new `requireSuperAdminOrAllowedTerminalDevice` middleware — used only at the `/v1/admin` router's single mount point — allowlists exactly the 7 routes `TerminalApiClient` actually calls under the old Super-Admin-derived token (`GET /sites`, `GET`+`POST /users`, `GET /terminals/:id`, `GET`+`POST`+`POST /users/:id/credentials`[list/complete/revoke]) — enumerated by reading every `TerminalApiClient.kt` call site, not assumed. Everything else under `/v1/admin` (site/terminal CRUD, permissions, recycle bin, sync-conflict resolution, etc.), plus all of `/v1/audit` and `/v1/reports` (still plain `requireSuperAdmin`, untouched), remains 403 for a terminal token. A new `actorUserIdFor(req)` helper (added to `users.js`/`credentials.js`/`sync.js`) maps a `TERMINAL_DEVICE` token to a `null` audit `actorUserId` on the specific routes that are actually `TERMINAL_DEVICE`-reachable (`writeAudit` already defaults `actorUserId` to `null`) — routes outside the allowlist were left using raw `req.auth.sub` since a terminal token can never reach them.
  **Migration story for already-paired terminals [confirmed with the user before implementing]:** terminals paired today under the old flow hold a genuine Super-Admin-derived user-scoped token and are completely unaffected — they keep working indefinitely through the unchanged `requireAuth`/`requireSuperAdmin` path. No forced re-pair, no undefined state; `TERMINAL_DEVICE` is strictly additive.
  Migration `006_registration_and_pairing.sql` (idempotent, `ignoreDuplicates: true` via the existing `migratePhase2.js` pattern) adds the new `terminals`/`users` columns, a separate `terminal_refresh_tokens` table (can't reuse `refresh_tokens` — its `user_id` column has an FK to `users(id)`, which a terminal's id can never satisfy), and `pairing_attempts` for the rate limiter.
  **Verified:** `:shared:compileDebugKotlinAndroid`, `:terminalApp:compileDebugKotlin`, `:terminalApp:assembleDebug`, `:shared:testDebugUnitTest` all pass — confirms `shared`'s new `Terminal`/`AdminUser` fields don't break terminalApp. **Not verified:** the backend JS changes were reviewed by careful manual line-by-line reading only (file-by-file: `pairing.js`, `terminals.js`, `users.js`, `index.js`, `middleware/auth.js`, `routes/auth.js`, `credentials.js`, `sync.js` — every `req.auth.sub` usage checked against the TERMINAL_DEVICE allowlist), never by actual execution — this dev machine has no Node (same known gap as the PATCH-rollout authoring machine above). Since production already runs on the VPS, this is a **redeploy gap, not an unreachable-backend gap**: `pair-with-code` will not be live at `https://kms-cvt.com` until these changes are deployed there (see `backend/DEPLOY.md`).
  **Not done in this pass (see Next steps):** terminalApp's actual pairing-flow UI (Part 2), the `mobileApp` check (Part 3), and the `web/` handoff documentation (Part 4) — Parts 2-4 were scoped as follow-on work, not bundled into this commit.

- **terminalApp pairing-code flow (Part 2).** A fresh/unpaired terminal's first screen is now `TerminalPairingScreen` (6-digit code entry) — gates the entire app (standby, login, Admin Menu) until redeemed, exactly as specced. `TerminalApiClient.baseUrl` now defaults to `https://kms-cvt.com` (no manual entry required); Admin Menu's "Set server address" remains as a fallback, and the pairing screen itself has a collapsed "Advanced: server address" field for the same reason — a 6-digit code can't encode a URL, something has to supply one before pairing for on-prem/non-default deployments. `TerminalApiClient.pairWithCode()` calls the new unauthenticated endpoint and stores the returned TERMINAL_DEVICE-scoped tokens in the same `accessToken`/`refreshToken` slots `login()` uses (this terminal has no separate device-identity storage — pairing establishes the terminal's persistent backend session the same way manual Super Admin sign-in used to). On success, `TerminalAdminApp` persists `terminal.id` as Key Cabinet ID and reuses the **existing** `syncCoordinator.bootstrap()` pipeline (same call Admin Menu's Bootstrap button makes) — best-effort, since a bootstrap hiccup shouldn't undo pairing that already succeeded. Invalid/expired/consumed code or a network failure shows an explicit error with no partial state (cabinetId/tokens only written after `pairWithCode()` actually succeeds).
  **Pairing gate is keyed on `cabinetId.isNotBlank()` alone, deliberately not `apiClient.isAuthenticated`** — the existing `signOut()` (unchanged) already clears the stored access/refresh token on every personnel sign-out, so gating on token presence would have re-shown the pairing screen after the very first operator logout. "Reset" per spec means explicitly clearing Key Cabinet ID; nothing here does that implicitly.
  Scope was kept to exactly device-to-account pairing: existing NFC/password/fingerprint/face personnel login, `CardUidResolver`, Key Take/Return Flow, and hardware protocol code are all untouched (grep-verified, not just asserted).
  **Verified:** `:terminalApp:compileDebugKotlin`, `:terminalApp:assembleDebug`, `:shared:testDebugUnitTest` all pass. **Not verified:** no manual UI walkthrough, no live run against the pairing endpoint — which itself isn't deployed yet (see status-truth table). Do not report this flow as working end-to-end until both the deploy and a live run happen.

- **`mobileApp` check (Part 3) — no changes needed, confirmed not assumed.** Grepped the whole module for every new field/concept from this work (`staffId`, `vendorDeviceId`, `nodeRows`, `nodesPerRow`, `latitude`, `longitude`, pairing, `VendorRole`) — zero matches. `mobileApp`'s only file touching `shared` (`SuperAdminCompanionApp.kt`) imports just `CredentialKind`/`KeySlotDemoData`/`TerminalConnectionState`, none of which changed, and never constructs a `Terminal(...)` or `AdminUser(...)` directly. `mobileApp` still has zero network dependencies (grepped for ktor/retrofit/okhttp — none) and remains 100% local demo data, consistent with its existing status. Confirmed `:mobileApp:compileDebugKotlin` still passes clean against the updated `shared` module. No speculative work was built here.

- **`web/` registration-workflow handoff documentation (Part 4)** — added the "Web Portal — Pending UI Work (Registration Workflow)" section above (Terminal registration form fields + breaking response-shape change + pairing-code display/regenerate requirements, Personnel `staffId` field, confirmation that `UserRole.VENDOR` already existed and needed no new work) and marked "Terminal → live API"'s manual steps as being-superseded-but-not-yet, pending VPS deploy and a live run of Part 2. No `web/` code was written or should be inferred from this — that remains the other developer's work, this session's job ends at documenting the contract precisely enough to build from.

- **Fingerprint + face enrollment (real implementations, not stubs) — audited first, then built.** An audit pass confirmed NFC card enrollment already targeted real synced personnel (`snapshot().users` populated by `syncCoordinator.bootstrap()`), but fingerprint/face login chips (`TerminalLoginScreen`) were pure static labels with zero real integration, and `backend/src/routes/credentials.js`'s anti-raw-material check only recognized NFC-hex-shaped raw material — `FINGERPRINT`/`FACE_RECOGNITION` had no real protection against a raw template/embedding being sent as the "opaque" reference. Also found and studied `../eKMSHardwareTester` (sibling repo, not inside this one — `ekmshardwaretester-main` in README.md's "reference material only" note actually lives at `../eKMSHardwareTester`), which has real, working R503 and OpenCV/MediaPipe face code — confirmed the R503 port assignment (`/dev/ttyS0` @ 57,600 8N1) that `CabinetGateway.kt`'s comment had never been verified against real wiring, and confirmed the same `android_serialport_api` vendor AAR terminalApp already uses.
  **Part A** — `rejectRawMaterialReference(credentialKind, ref)` in `credentials.js`: a recognized prefix (`cardref_`/`fptemplate_`/`faceref_`/`vendorref_`/`ref_`) always passes; otherwise each kind is checked against its own raw-shape denylist (hex for NFC, unchanged; a ≥20-char base64/hex-blob pattern for `FINGERPRINT`/`FACE_RECOGNITION`, since real templates/embeddings run far longer than any reasonable reference even before the schema's 128-char cap).
  **Part B (fingerprint)** — full port from the tester's `R503FingerprintProtocol.kt`, not a spec: `shared/.../protocol/R503FingerprintProtocol.kt` mirrors `KeyCabinetLink`'s existing `SerialTransport`-based split (reused `SerialTransport`/`FakeSerialTransport` as-is), with the tester's `Thread.sleep`-based finger-polling deliberately NOT ported into `shared` (Kotlin Multiplatform, no cross-platform sleep) — that moved to `terminalApp/.../hardware/FingerprintHardwareController`, parallel to `CabinetHardwareController`. Packet-read timeout model tightened to one shared per-packet deadline (matching `KeyCabinetLink.readNodeFrameBytes`) instead of the tester's independently-fresh timeout per sub-read. 15 unit tests in `shared/commonTest` — `handshake`/`checkSensor` request frames hand-computed byte-for-byte as an independent checksum cross-check, the rest exercise real multi-packet parsing, checksum corruption, and timeouts; all pass. `FingerprintEnrollmentScreen` reachable via `openAdmin()`'s existing gate, targets `personnelForScreens` (confirmed real synced personnel). Since the R503 auto-assigns and owns the template on-device (0-199 slot in its own library — the biometric data itself never leaves the sensor module), there's no separate opaque-reference design needed: `FingerprintTemplateStore` reports `fptemplate_<id>` (the actual on-device slot) as `enrollmentReference`. Local `(userId -> templateId)` mapping is plain SharedPreferences, not Keystore-encrypted, since an integer slot number isn't credential material. Revoke deletes the slot from the sensor (0x0C) before clearing the local mapping.
  **Part C (face)** — ported the decision-independent pieces first (`OpenCvFaceEngine` — YuNet+SFace, `MediaPipeFaceLandmarkerEngine` — 468-landmark+blendshape, `FaceTemplateEnrollmentSession` — 5-sample averaging, `SFaceEmbeddingExtractor`, `FaceModelStore`), added `org.opencv:opencv:5.0.0.1` + `com.google.mediapipe:tasks-vision:0.10.35` to `terminalApp/build.gradle.kts`, and empirically verified (not just via dependency resolution) that neither is a blocker against this module's pinned AGP 8.11.1/compileSdk 36/Kotlin 2.2.20 baseline: a real `:terminalApp:assembleDebug` succeeded end-to-end including native `.so` packaging (`libopencv_java5.so`, `libmediapipe_tasks_jni.so`, `libc++_shared.so` all present for `arm64-v8a`, the near-universal modern Android ABI, alongside armeabi-v7a/x86/x86_64). This adds the three model assets the code needs to actually function (not just compile) — `face_detection_yunet_2023mar.onnx` (227 KB), `face_recognition_sface_2021dec.onnx` (37 MB), `face_landmarker.task` (3.6 MB) — **~41 MB of binary assets in `terminalApp/src/main/assets/models/`, roughly doubling repo size; no Git LFS configured.** `FaceProfileStore` ported with one addition beyond the tester (which has no backend concept): an `enrollmentReference` field (`faceref_<uuid>`) generated at `save()` time alongside the existing AES-256-GCM-via-Keystore encrypted embedding — mirrors `fptemplate_<id>`'s bridge pattern, satisfying boundary #2 (raw embedding never reaches `completeCredentialEnrollment`).
  **RGB-only active liveness for v1 — user-confirmed decision**, made after reviewing an explicit tradeoff comparison (RGB-only: reuses code that already exists/builds, but is a real — not hypothetical — weaker anti-spoof tier than IR, vulnerable to a convincing video/photo replay; RGB+IR: matches the vendor manual's stated spec, camera ID `"0"`/IR confirmed addressable via the tester's `CameraDiagnosticActivity`, but no IR-liveness algorithm exists anywhere — tester, this codebase, or any dependency — and the vendor manual's actual section 4.8.3/4.8.4 content isn't in this repo, so building toward it now would mean guessing at both the fusion architecture and the anti-spoof algorithm). **User's decision: RGB-only for v1, explicit plan to upgrade to a better model/method later** — treat this as an intentional, temporary, lower-assurance choice, not a placeholder to silently forget. Ported `ActiveBlinkLivenessChallenge` (blink + random head-turn state machine) and `FaceDetectionOverlayView` unchanged, then built `FaceCameraController` (Camera2 on RGB camera `"1"`, owns the liveness challenge + 5-sample capture loop, publishes a `FaceEnrollmentPhase` state machine) and `FaceEnrollmentScreen` (Compose, embeds the camera preview via `AndroidView`-wrapped `TextureView`+overlay, auto-opens the camera once permission is granted so the operator can frame themselves, liveness+capture only run once "Enroll face" is tapped). Added a `frameBusy` `AtomicBoolean` backpressure guard (matches the tester's own `detectionBusy` pattern, initially missed on first port and caught before commit) so the single-thread processing executor skips a frame rather than queuing an unbounded backlog if inference falls behind the capture cadence. Added `android.permission.CAMERA` + non-required camera `<uses-feature>` entries to `AndroidManifest.xml` (previously undeclared). `FaceEnrollmentScreen` wired the same way as fingerprint: `openAdmin()` gate, `personnelForScreens`, `reportFaceEnrollment`/`reportFaceRevoke` calling `completeCredentialEnrollment`/`revokeCredentialEnrollment` with `credentialKind = FACE_RECOGNITION`. Explicitly not ported: `FaceVerificationSession` (login/matching, out of scope — same "enrollment only" precedent Part B established for fingerprint's `verifyTemplateOnce`/`autoIdentify`).
  **Verified this session:** `:shared:testDebugUnitTest` (15/15 new R503 tests + full suite), `:terminalApp:compileDebugKotlin`, `:terminalApp:assembleDebug` all pass, for both the fingerprint and face work. **Not verified:** no physical R503 module or camera hardware was available this session — fingerprint is FakeSerialTransport-tested only, face has no live camera/liveness/enrollment run at all. Do not report either as hardware-confirmed until a real F7G18P run happens.

- **Git LFS set up for the face-model assets, forward-only, no history rewrite.** Added `.gitattributes` (repo root) tracking `terminalApp/src/main/assets/models/{*.onnx,*.task,*.tflite,*.pb,*.bin}` via LFS, then re-added the three existing model files under those attributes so they're stored as LFS pointers from this point forward (commit `3b4c8dd`) — verified the working tree content itself was unchanged (still 232589/3758596/38696353 bytes; the `.task` file still parses as a valid zip archive) before and after. Deliberately did **not** rewrite history to strip the ~41 MB of raw blobs already committed in `ff332f5` — see the Known Issues entry below for the reasoning (a hash-changing rewrite was judged materially riskier than the one-time bloat, given the concurrently-active `web/` developer and this repo's prior silent-revert merge incident). `git lfs install` is now a required one-time local setup step for anyone working in this repo — added to Toolchain above.

- **Combined hardware verification session (Take/Return Flow, fingerprint, face) — three parts, run in strict order on a real F7G18P + R503 module + camera, per the user's "stop and report on any failure" instruction.**
  **Part 1 (Take/Return Flow) — stopped early by explicit user request, not fully completed.** Take Flow confirmed on real hardware: door sequence (blue light → unlock → eject → confirm-open via 0x22), the 20s hard abandonment ceiling (measured from logs at 20.09s — within ~100ms of spec), abandonment correctly re-locks the fob and turns the light off before reporting, Take Warning Time's countdown starts at confirmed fob **removal** (not door-open, reconstructed precisely from event-outbox/logcat timestamps), the success path, and door-left-open logged as its own distinct event. Return Flow: a real card-swipe correctly resolved a node via `CardUidResolver` (not the manual-tap fallback), confirming that path works on real hardware; the 20s no-insert abandonment test was attempted but produced an **unexplained duplicate** — two `KEY_RETURN_ABANDONED` events ~28s apart from what should have been one swipe/one abandonment (see Known issues, unresolved — the clarifying question was never answered). Return Flow's **success/insertion path was never attempted at all** in this session (no Door-Close Warning Time countdown or `PLEASE_INSERT_THE_KEY` voice-line confirmation). A real bug was found and left unfixed: the continuous beep (spec: continuous from unlock through door-close) audibly stops a few seconds after door-open and never escalates louder at the 5s mark — working hypothesis only (unconfirmed): the `MediaPlayer` voice-line start at 5s may be interrupting the concurrent `SoundPool` beep loop on this device's custom `awplayer`/CedarX audio stack. User's exact instruction ending Part 1: *"please stop part 1. will look into this later on. the process is too chunky. i will remake the process after this. proceed to next part"* — read this as the user's stated intent to redesign Take/Return Flow, not just patch these two bugs; don't invest in point-fixes here without checking whether a redesign has since superseded them.
  **Part 2 (fingerprint, R503) — hardware behavior confirmed working; backend sync confirmed broken.** A real 6-scan `AutoEnroll` cycle completed end-to-end, producing `fptemplate_0` correctly stored in `FingerprintTemplateStore` (confirmed by reading the device's SharedPreferences directly). Re-scanning the same already-enrolled finger correctly hit the R503's own on-device duplicate-detection (confirmation code 0x27, "Fingerprint already exists") rather than silently succeeding or crashing. Revoke (0x0C) correctly deleted the on-device template slot, confirmed by immediate re-enrollment afterward. The checklist's specific "deliberately misaligned/dirty-finger" failure case was not literally run — the duplicate-detection case above was treated as satisfying the same spirit (a real, clean error path), not a substitute the user was asked about. Every backend-sync attempt in Part 2 failed: first on a malformed `terminalId` (this device's local `cabinetId` had been set to a non-UUID test placeholder via `adb run-as`, since the real pairing-code backend isn't deployed yet — fixed by using a valid-UUID-shaped placeholder instead, a test-setup fix, not a code fix), then — with a valid UUID — on a second, distinct bug that persisted (see the new Known issues entry below). User's exact instruction after the second bug: *"Log it, move to Part 3 (face)."*
  **Part 3 (face) — three real hardware/runtime bugs found and fixed in-session, then full local pipeline confirmed working; backend sync confirmed broken by the same bug as Part 2.** Bug 1 (blocked all camera preview): `FaceEnrollmentScreen`'s `TextureView.SurfaceTextureListener` was attached inside a `LaunchedEffect`, but `AndroidView`-hosted `TextureView` can fire the one-shot `onSurfaceTextureAvailable` callback before that effect runs, so the listener registration silently missed it and the camera never opened — fixed by checking `textureView.isAvailable`/`textureView.surfaceTexture` up front and attaching directly if already available, in addition to keeping the listener for the normal case; confirmed fixed live (real preview now visible). Bug 2 (liveness unreliable, user-interrupted: *"rework this liveness method. its ineffecient"*): diagnosed as a sampling-rate/aliasing problem — the ~350ms frame-pump interval is too coarse to reliably catch a natural ~100-400ms blink, compounding a fragile sequential multi-gate state machine with no partial credit. Per the user's explicit choice ("simplify the challenge itself" over "fix the sampling problem"), deleted `ActiveBlinkLivenessChallenge.kt` and replaced it with `ActiveHeadTurnLivenessChallenge.kt` (single-gesture, random left/right head-turn only, 15s timeout, no blink/eyes-open gates — a sustained geometric state tolerates coarse polling fine, unlike a blink) — confirmed fixed live, passing on the very next attempt. Bug 3 (crashed the very next capture attempt after the liveness fix): `FaceProfileStore.encrypt()` pre-generated an IV via `SecureRandom` and passed it to `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))` — AndroidKeyStore's default `setRandomizedEncryptionRequired(true)` rejects a caller-supplied IV for `ENCRYPT_MODE` outright (`InvalidAlgorithmParameterException: Caller-provided IV not permitted`), a real hardware-only failure mode no compile/build check could have caught. Fixed by calling `cipher.init(ENCRYPT_MODE, key)` with no IV and reading the Keystore-generated one back via `cipher.iv` after init (`decrypt()` is unaffected — it legitimately needs to supply the known IV). **This exact bug pattern also existed in the original `../eKMSHardwareTester` code this was ported from** — its save path was apparently never exercised against a real AndroidKeyStore either. After all three fixes: full enrollment (liveness pass → 5-sample capture → encrypted save) confirmed working end-to-end on real hardware; a deliberate reject-case test (holding a photo up to the camera for the head-turn challenge) correctly **timed out** rather than passing; the saved profile was confirmed directly from the device's SharedPreferences (5 samples, a correctly-formatted `faceref_<uuid>` reference, and a ciphertext+IV blob with no raw embedding visible); revoke was confirmed to fully clear the local profile. The `frameBusy` backpressure guard has no logging of its own, so its activation wasn't directly log-confirmed — but across 3 real capture cycles running live OpenCV+MediaPipe inference on this device's 32-bit ARM (armeabi-v7a) CPU, there were zero crashes/ANRs/memory pressure, consistent with (not direct proof of) it working as designed. **Honest liveness robustness assessment:** the reworked head-turn-only challenge is now reliably passable by a live person and correctly rejected a static/passive photo (it couldn't produce the required yaw score, so it timed out) — but this was not an adversarial security test. A more deliberate spoof (physically rotating/tilting a printed photo or a phone screen to mimic head-yaw geometry) was **not attempted** and remains untested. The rework fixed a reliability bug for legitimate users; it does not change the already-documented RGB-only-vs-RGB+IR tradeoff or its weaker anti-spoof tier.
  **Verified this session:** `:terminalApp:compileDebugKotlin`, `:terminalApp:assembleDebug` both pass after all three Part 3 fixes; all fixes additionally confirmed by live, repeated real-hardware runs (not just compile/assemble) — this is the first entry in this file where "verified this session" means physical hardware, not just the build. **Not yet committed:** the Part 3 fixes (TextureView race, liveness rework, IV fix) and the stale-UI-copy correction (`FaceEnrollmentScreen`'s description text no longer mentions "blink") were still local/uncommitted as of this writing — commit once the user confirms no further Part 3 iteration is needed.

### Known issues / not yet resolved

Open work only — resolved/superseded items live in Completed or the status-truth table, not here.

- **Systemic credential backend-sync bug: `expectedRevision: null` is rejected by the backend's zod schema, breaking `completeCredentialEnrollment`/`revokeCredentialEnrollment` for every credential kind.** Confirmed on real hardware on two independent code paths this session (fingerprint revoke, face complete **and** revoke) — both failed with "Invalid credential enrollment completion/revoke" even after ruling out every other cause (e.g. face's failure persisted with a valid-UUID `terminalId`, and revoke's schema doesn't even include `terminalId`, so it can only be `expectedRevision`). Root cause: `backend/src/routes/credentials.js`'s three routes (`enroll`/`complete`/`revoke`) all declare `expectedRevision: z.number().int().nonnegative().optional()` — zod's `.optional()` only tolerates the key being *absent*, not an explicit JSON `null` — while `TerminalApiClient.kt`'s `Json { encodeDefaults = true }` always serializes `expectedRevision: Long? = null` as an explicit `null` on every call, since no terminalApp call site currently populates it. This almost certainly also blocks NFC card-credential sync (same code path, never independently retested this session). **Not fixed** — the user explicitly chose "log it, keep testing" over fixing it now, both times it was found (Part 2 and again when it resurfaced identically in Part 3). The fix is small and already has a precedent in the same file (`terminalId` already correctly uses `.nullable().optional()`) — add `.nullable()` to `expectedRevision` on all three routes — but it's a `backend/` change that also needs a VPS redeploy before it affects `https://kms-cvt.com`; do not make this change without the user's explicit go-ahead (see "Production is already live").
- **Take Flow beep-continuity bug (confirmed on real hardware, not root-caused):** the continuous take-side beep audibly stops a few seconds after door-open and never escalates louder at the 5s mark, contradicting the documented "continuous beep from unlock through door-close confirmation" spec. Unconfirmed working hypothesis: starting the `MediaPlayer` voice line at the 5s mark may interrupt the concurrent `SoundPool` beep loop on this device's `awplayer`/CedarX audio stack. See status-truth table — the user has indicated an intent to redesign the whole Take/Return flow ("too chunky... will remake"), so check whether that supersedes this before point-fixing it.
- **Return Flow "double abandonment" event (confirmed on real hardware, cause unresolved):** a single card-swipe/no-insert test produced two separate `KEY_RETURN_ABANDONED` events ~28 seconds apart in the local event outbox. The clarifying question (double-swipe? card held near reader?) was asked but never answered — the user moved on before responding. Needs a repeat test with closer observation before it can be ruled root-caused or dismissed as a test artifact.
- **Return Flow's success/insertion path has never been hardware-tested at all** — no run of insertion-within-5s, the `PLEASE_INSERT_THE_KEY` voice line, or the Door-Close Warning Time countdown has happened on real hardware yet (Part 1 only exercised the abandonment path, and even that was ambiguous — see above).

- **Pairing-code flow (backend + terminalApp, both Parts 1-2) is fully coded and committed but not live**: needs a VPS redeploy (`backend/DEPLOY.md`) and has never been run against a real terminal/server. `web/` also has no registration UI yet to generate a code with — see "Web Portal — Pending UI Work (Registration Workflow)". Until all three land, the only working pairing path is the manual Admin Menu flow in "Terminal → live API". **This session's test device bypassed the pairing gate locally** (an `adb run-as` SharedPreferences edit setting `cabinetId` to a placeholder UUID, purely to unblock testing since the real flow isn't deployed) — this is test-device-local state only, not evidence the flow works, and not a production concern; don't mistake that device's local prefs for a real paired terminal.
- **Fingerprint and face enrollment: device-local hardware behavior is now hardware-verified; backend credential-sync is confirmed broken.** See Completed "Combined hardware verification session" above for exactly what was tested. Real R503 enrollment/duplicate-detection/revoke and real camera preview/liveness/5-sample-capture/revoke all confirmed working on physical hardware this session. What remains open: (1) `completeCredentialEnrollment`/`revokeCredentialEnrollment` fail for every credential kind due to the systemic zod bug above — so enrolled credentials never actually reach the backend/Personnel Management page today; (2) fingerprint's specific "misaligned/dirty finger" failure case was never literally tested; (3) face's liveness was only tested against a passive/static photo, not a deliberately-manipulated spoof attempt.
- **Face enrollment ships RGB-only active liveness — reworked this session from blink+head-turn to head-turn-only** (see Completed above for why: blink detection was unreliable due to a polling-rate/blink-duration mismatch, not a threshold-tuning issue). Confirmed live: reliably passable by a real person, correctly times out against a static photo. Still a deliberate, user-confirmed v1 choice with a real, weaker anti-spoof tier than the vendor manual's RGB+IR spec (section 4.8.3/4.8.4, not available in this repo) — the rework improved reliability, not the security tier. A deliberately-manipulated spoof (rotating/tilting a photo to mimic head-yaw) was not tested. Explicit plan to upgrade later; do not treat this as final or silently "fix" the anti-spoof tier without revisiting the tradeoff.
- **`terminalApp/src/main/assets/models/`'s ~41 MB of binary face-model assets are permanently in git history as raw blobs** (commit `ff332f5`) — Git LFS was set up afterward (commit `3b4c8dd`, `.gitattributes` + the three files re-added as LFS pointers), so nothing new grows the problem, but the historical ~41 MB itself was deliberately **not** removed via a history rewrite (`git filter-repo`/BFG/`git lfs migrate import`) — that would change every commit hash from `ff332f5` onward, forcing the concurrently-active `web/` developer to hard-reset/re-clone instead of a normal `git pull`, which given this repo's prior silent-revert merge incident was judged a materially worse risk than the one-time bloat. Treat a history rewrite here as a **future, deliberately coordinated maintenance action** (both developers idle, remote refs cleaned, everyone re-clones after) — never do it as a routine session action. Everyone working in this repo needs `git lfs install` run once locally (see Toolchain) or these files silently resolve to unusable pointer stubs.
- **Key Take Flow is hardware-verified for its core path (door sequence, 20s abandonment ceiling, removal-triggered countdown, success path)** — see Completed "Combined hardware verification session" above. **Key Return Flow is only partially exercised**: real card-swipe node resolution works, but the abandonment-path test produced an unexplained duplicate event (open, see above) and the success/insertion path (Door-Close Warning Time, `PLEASE_INSERT_THE_KEY`) has never been run at all. The user stopped this testing early and stated intent to redesign the flow ("too chunky... will remake this process") — treat that as likely superseding point-fixes to the beep-continuity/double-abandonment bugs above until confirmed otherwise.
- **`web/` PATCH/update rollout not verified by build or live edit** in the environment that authored the change (that laptop had no Node — it could not `npm run build` locally). That does **not** mean the backend is missing: production is `https://kms-cvt.com`. Remaining work is rebuild/redeploy `web/` to the VPS (see `backend/DEPLOY.md` Part F) and manually test at least Units edit + `409` conflict against the live API.
- **`web/` has not had a full section-by-section UX audit** against the vendor manual — only API-client / spot checks. Page-level UX and shared-type-equivalent checks still outstanding.
- **Appointment-permissions API client stubs are broken/dead:** `client.ts` exposes `list`/`create`/`delete` for `/v1/admin/appointment-permissions`, but the backend router only implements `PATCH /:id`. Harmless today (`AppointmentPermissionsPage` just re-renders Appointments); clean up or implement properly before wiring a real page.
- **Key Slots:** backend PATCH exists; no dedicated `web/` page exposes slot CRUD yet.
- **Standing Super Admin / mobile alerts** for take door-left-open and return abandoned-return are not built (events may exist locally on terminal; no portal/mobile delivery UI).
- **`mobileApp`:** still 100% local demo data, zero network.
- Personnel management in frozen `webApp` was a shallow form — **moot** (module frozen). Live work is `web/PersonnelPage.tsx` (already has real backend CRUD + enrollment column); remaining gaps are audit/polish, not a missing page.
- Orphaned terminal workflow scaffolds remain reference-only under `terminalApp/reference/` — do not merge as production UI.
- Theme polish: terminal red/alarm status tone was never confirmed live on hardware (deferred by user during Cavotec pass).

### Next steps (in order)
- Commit the uncommitted Part 3 fixes from this session (`FaceEnrollmentScreen` TextureView race fix + stale-copy correction, `ActiveHeadTurnLivenessChallenge` liveness rework replacing `ActiveBlinkLivenessChallenge`, `FaceProfileStore` AndroidKeyStore IV fix) — all confirmed working live on hardware, just not yet in git
- Fix and deploy the systemic `expectedRevision`-null credential-sync bug (`backend/src/routes/credentials.js`, add `.nullable()` to all three routes' `expectedRevision` schema, matching the existing `terminalId` pattern in the same file) — currently blocks `completeCredentialEnrollment`/`revokeCredentialEnrollment` for every credential kind (NFC/fingerprint/face) on the live backend; needs the user's explicit go-ahead per "Production is already live" before touching `backend/` or redeploying
- Resolve the two open Take/Return Flow bugs found this session (beep-continuity stopping early / never escalating; Return Flow's duplicate abandonment event) — but check first whether the user's stated intent to redesign the flow ("too chunky... will remake this process") has superseded point-fixing them
- Hardware-test Key Return Flow's untested success/insertion path (Door-Close Warning Time countdown, `PLEASE_INSERT_THE_KEY` voice line) — never run this session
- Deploy the pairing/registration backend changes to the VPS (`backend/DEPLOY.md`); build `web/`'s Terminal/Personnel registration UI per "Web Portal — Pending UI Work (Registration Workflow)" (someone else's work, not this session's); then do a live end-to-end pairing run (register a terminal on the portal → type the code into a fresh terminalApp install → confirm bootstrap sync completes) before calling the flow done — code for all three pieces already exists, none of it has been run together
- Verify `web/` PATCH/update against **live** `https://kms-cvt.com`: rebuild portal, deploy `web_dist` on the VPS, manually edit at least Units (including `409` conflict reload). Do not stand up a new backend for this.
- Full section-by-section audit of `web/` against the vendor manual / handover checklist
- Standing-alert UI on `web/` (and later mobileApp) for door-left-open (take) and abandoned-return (return)
- Optional cleanup: appointment-permissions client/router mismatch; Key Slots admin page if product needs it; terminal alarm-tone polish; fingerprint's untested "misaligned finger" failure case; a real adversarial liveness spoof test (rotated/tilted photo) rather than just a static one
- Do **not** schedule a greenfield “rebuild Personnel on web/” — that page already exists; improve it in place if UX gaps remain after audit

### Reference
- Hardware protocol: `docs/Key Cabinet Communication Protocol.md` (note
  the spaces in that filename) is the authoritative vendor spec — read
  before any phase 8+ work. `docs/Key_Cabinet_Communication_Protocol.md`
  (underscored) is a project-level index onto it and defers to it on any
  conflict.
- Terminal UX baseline: Smart Key Cabinet User Manual V2.1 (baseline +
  defined enhancements — see "Terminal App UX Baseline (Production —
  baseline + defined enhancements)" section for what's permitted to
  diverge and how)