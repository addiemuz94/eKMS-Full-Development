# CLAUDE.md

Guidance for agents working in this repository.

eKMS is a Kotlin Multiplatform key-management system: an Android Terminal (physical key cabinet controller), an Android mobile Super Admin companion app, and a Super Admin web portal, all sharing domain models/policies/API contracts through a `shared` module. A real backend (`backend/`, Express.js + MySQL, REST API at `/v1`) is already deployed in production — terminalApp has real offline-first sync wiring against it, and the live Super Admin portal is the React app in `web/`. `mobileApp` has recently gained real backend wiring for its Access (Passkey) tab but is otherwise still local demo data.

**Read this first:** this file used to be one large document; it's now split by concern. Read this file plus whichever of the 5 files below matches the module you're touching — most tasks only need one or two of them. The two sections kept on this page (Production is already live, and the architectural boundaries) apply **everywhere**, regardless of which file you open next, so read those two before touching sync/backend assumptions in any module.

## Jump table

| Jump to | What it covers |
|---|---|
| [CLAUDE_SHARED.md](CLAUDE_SHARED.md) | Cross-cutting info all three apps (or agents in general) need: project overview, toolchain/build pins, common commands, module architecture, where things live, `docs/` conventions, NFC UID Resolution Rule, Web/Mobile UX Consistency, Hardware Feature Findings (Digital Key) |
| [CLAUDE_BACKEND.md](CLAUDE_BACKEND.md) | `backend/`-specific: full "Production is already live" detail, the terminal↔API endpoint table/auth model, Credential policy (policy A), backend-side Project Status (migrations, route allowlists, deploy status) |
| [CLAUDE_TERMINAL.md](CLAUDE_TERMINAL.md) | `terminalApp`-specific: Terminal App UX Baseline (all 3 documented enhancements), terminal-side pairing wire-up + operator checklist, hardware protocol/manual references, terminalApp's Project Status (Phase 1-9F, hardware verification state) |
| [CLAUDE_WEB.md](CLAUDE_WEB.md) | `web/`-specific: Web Portal Registration Workflow, cabinet behavioral settings, `web/`'s Project Status (PATCH rollout, sort/filter/search, dialogs, dark mode) |
| [CLAUDE_MOBILE.md](CLAUDE_MOBILE.md) | `mobileApp`-specific: current state, Digital Key test-tooling history, Passkey mobile-side, `mobileApp`'s Project Status |

## Production is already live — do not reinvent the backend

**The API and Super Admin portal are already deployed on a VPS and reachable on the public internet.** Agents must not assume "there is no backend," invent a new deploy, or treat local-only Node as a requirement to use the API.

| URL | What |
|---|---|
| `https://kms-cvt.com/` | Live React portal (`web/` build served from the VPS) |
| `https://kms-cvt.com/v1/...` | Live Express API |
| `https://kms-cvt.com/health` | Health check |
| Terminal Admin Menu → server address | `https://kms-cvt.com` (no trailing slash; **not** an `api.` subdomain) |

Deploy/ops details live in `backend/DEPLOY.md` (Docker Compose prod, Caddy, Cloudflare DNS for `kms-cvt.com`) and `CLAUDE_BACKEND.md`. **Default agent scope:** work on `shared/`, `terminalApp/`, `web/`, and docs. Touch `backend/` only when the user explicitly asks for an API/schema/deploy change — and even then prefer updating the existing VPS deploy over standing up a parallel stack. A laptop without Node/Docker can still verify against production (browser → portal, terminal → `https://kms-cvt.com`); "no local Node" only means you cannot run `npm run build` / local Vite in that environment, **not** that the backend is unavailable.

## Non-negotiable architectural boundaries

These rules are enforced by convention across the codebase (see comments in `ApiContracts.kt` and `AdminModels.kt`) — preserve them in any change, regardless of which module you're working in or which of the 5 linked files you came from:

1. **Only the Android Terminal touches cabinet hardware.** Website and Mobile must never open a serial port, send a cabinet command/frame, or perform reader/NFC/biometric capture. The split-nibble/CRC8 frame protocol and full node command set (`KeyCabinetLink`, plus `SplitNibbleCodec`/`KeyCabinetCrc8`/`KeyCabinetFrame`) live in `shared/.../protocol/` as pure Kotlin with no serial dependency, so they're unit-testable without hardware — but only `terminalApp/src/main/java/com/ekms/terminal/hardware/` (`AndroidSerialTransport`, `CabinetHardwareController`) may actually open `/dev/ttyS1`/`/dev/ttyS2` and drive them.
2. **No raw credential material ever leaves the Terminal.** NFC UIDs, fingerprint/face templates, and Digital Key secrets are never represented in shared DTOs or sent to Website/Mobile — only an opaque `fobEnrollmentReference`/enrollment state. See `ManagedKey.fobEnrollmentReference` and `FobEnrollmentResponse` in `shared`.
3. **Every physical key-node address is canonical.** Node address `0` is always the door; key nodes are addresses within `1..configuredSlotCount`. Never apply a hidden UI +1/-1 conversion (explicitly called out on `KeySlot.nodeAddress` and `KeySlotUpsertRequest.nodeAddress`).
4. **All mutations are revision-safe.** Update/PATCH-style requests carry `expectedRevision`; the backend rejects stale writes with `409 CONFLICT` rather than silently overwriting (verified server-side: every PATCH route checks `existing.revision === expectedRevision` AND guards the UPDATE itself with `WHERE revision = :expectedRevision`, a real double-checked guard against races, not just an application-level check). The frozen `webApp` never had in-place edit/PATCH UI. `web/` now does, for 10 resources (Units, Terminals, Personnel, Keys, Permissions, Event Setup, Schedules, User Groups, Key Groups, Multi-Authentication Rules — see `CLAUDE_WEB.md` Project Status) — every edit path reads `expectedRevision` off the already-loaded row and shows an explicit conflict message on `409` rather than retrying or overwriting. Appointments and Key Slots remain create/delete-only: Appointments by backend design (only review/permissions-patch are mutable), Key Slots because no `web/` page exposes them yet.
5. **Delete is always soft-delete.** Records move to a Super Admin-only Recycle Bin for 60 days (`RecycleBinPolicy.RETENTION_DAYS`) before purge; active dependents must block a hidden cascade delete. Historic audit events survive a purge.
6. **Offline Terminal edits never silently overwrite server state.** A conflicting offline change becomes a `SyncConflict` that only a Super Admin (`ConflictReviewPolicy.mayResolve`) can resolve, via `KEEP_SERVER` / `KEEP_TERMINAL_CHANGE` / `MERGE_MANUALLY`.
7. **Passwords and other secrets are write-only** — never rendered, logged, or returned by an API response.
8. **No unauthenticated routes beyond `/health`, `/v1/auth/login`, `/v1/auth/refresh`.** Every other backend mount must sit behind `requireAuth` (see `backend/src/middleware/auth.js`) before any sub-router. This was violated once (see `CLAUDE_BACKEND.md` Project Status — the `/v1/debug/agent-log` incident) by ad hoc debug instrumentation that also logged real personnel emails in plaintext to server-side files with no auth or redaction. Debug/diagnostic instrumentation added during development must never ship unauthenticated, and must never log real user data (names, emails, credential/enrollment references) — use synthetic identifiers or counts instead. Verify route-mounting order in `backend/src/index.js` (the only file allowed to call `app.use`/`app.get`/`app.post` directly — every other route file exports a `Router()`) whenever adding new backend surface.
