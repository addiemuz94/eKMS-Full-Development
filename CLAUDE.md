# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

eKMS is a Kotlin Multiplatform key-management system: an Android Terminal (physical key cabinet controller), an Android mobile Super Admin companion app, and a Kotlin/Wasm Compose web portal, all sharing domain models/policies/API contracts through a `shared` module. There is no backend yet — the Website and Mobile UIs currently run against local in-memory sample data as an interactive preview of the workflow; a real backend must replace these with authenticated, revision-aware REST calls (see `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md`).

The `ekmshardwaretester-main` project mentioned in README.md is reference material only and is not part of this production build.

## Toolchain (do not drift from this baseline)

- JDK 17, Gradle **8.13**, Android Gradle Plugin 8.11.1, Kotlin 2.2.20, `compileSdk = 36`, `minSdk = 26`.
- Do not upgrade to Gradle 9.x without migrating the whole KMP build to a newer AGP model — see `docs/BUILD_SETUP.md`.
- Kotlin and Java bytecode targets are explicitly pinned to JVM 17 on Android modules; a mismatch here is a known historical failure mode (Kotlin defaulting to 21 vs. Java defaulting to 11) and build scripts guard against it.

## Common commands

Run from the repo root (`gradlew.bat` on Windows, `./gradlew` on POSIX shells).

```
gradlew.bat build                                  # build all modules
gradlew.bat :shared:allTests                        # run shared commonTest (KMP test target)
gradlew.bat :shared:test --tests "*RecycleBinPolicyTest*"   # run a single test class
gradlew.bat :terminalApp:assembleDebug               # build Android Terminal app
gradlew.bat :mobileApp:assembleDebug                  # build Android mobile companion app
gradlew.bat :webApp:wasmJsBrowserDevelopmentRun       # run the web portal in a browser
gradlew.bat :webApp:wasmJsBrowserProductionWebpack     # production web bundle
```

`shared` is the only module with tests today (`shared/src/commonTest`), run via the Kotlin/JVM+Wasm multiplatform test tasks. There is no lint/format command configured beyond the Gradle/Kotlin compiler.

Open the project in Android Studio at the repo root (not a module subfolder) with the Kotlin Multiplatform plugin; select JDK 17 and Gradle 8.13 explicitly, since the IDE default may pick something else.

## Module architecture

| Module | Target | Role |
|---|---|---|
| `shared` | Android + Wasm (commonMain/commonTest) | Cross-platform domain models, access policies, soft-delete/Recycle Bin rules, sync-conflict DTOs, and the canonical API path/DTO contracts. This is the single source of truth other modules and the eventual backend must agree with. |
| `terminalApp` | Android only | The physical F7G18P key-cabinet terminal app. Owns all hardware I/O: cabinet serial protocol, NFC UID reads, fingerprint/camera. |
| `mobileApp` | Android only | Super Admin companion app (thin UI layer today, no hardware access). |
| `webApp` | Kotlin/Wasm + Compose | Super Admin web portal, following the supplier's Web manual workflow sections (Unit/Terminal/Personnel/Key/Permission Settings, Data Sync, Reports, Appointments, etc). |
| `docs` | — | Backend/API handover documents; treat `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` and the `API_HANDOVER_SUPER_ADMIN` series as the living spec for what the backend must implement. |

### Non-negotiable architectural boundaries

These rules are enforced by convention across the codebase (see comments in `ApiContracts.kt` and `AdminModels.kt`) — preserve them in any change:

1. **Only the Android Terminal touches cabinet hardware.** Website and Mobile must never open a serial port, send a cabinet command/frame, or perform reader/NFC/biometric capture. `terminalApp/src/main/java/com/ekms/terminal/hardware/` is the only place serial I/O happens (`KeyCabinetProtocol.kt` implements the supplier's split-nibble/CRC8 protocol over `/dev/ttyS1` at 19200 baud).
2. **No raw credential material ever leaves the Terminal.** NFC UIDs, fingerprint/face templates, and Digital Key secrets are never represented in shared DTOs or sent to Website/Mobile — only an opaque `fobEnrollmentReference`/enrollment state. See `ManagedKey.fobEnrollmentReference` and `FobEnrollmentResponse` in `shared`.
3. **Every physical key-node address is canonical.** Node address `0` is always the door; key nodes are addresses within `1..configuredSlotCount`. Never apply a hidden UI +1/-1 conversion (explicitly called out on `KeySlot.nodeAddress` and `KeySlotUpsertRequest.nodeAddress`).
4. **All mutations are revision-safe.** Update/PATCH-style requests carry `expectedRevision`; the backend (not yet built) is expected to reject stale writes rather than silently overwrite.
5. **Delete is always soft-delete.** Records move to a Super Admin-only Recycle Bin for 60 days (`RecycleBinPolicy.RETENTION_DAYS`) before purge; active dependents must block a hidden cascade delete. Historic audit events survive a purge.
6. **Offline Terminal edits never silently overwrite server state.** A conflicting offline change becomes a `SyncConflict` that only a Super Admin (`ConflictReviewPolicy.mayResolve`) can resolve, via `KEEP_SERVER` / `KEEP_TERMINAL_CHANGE` / `MERGE_MANUALLY`.
7. **Passwords and other secrets are write-only** — never rendered, logged, or returned by an API response.

### Where things live

- `shared/.../domain/` — core entities (`AdminUser`, `Site`, `Terminal`, `ManagedKey`, `KeySlot`, `AccessGrant`, `CredentialBinding`, `AuditEvent`) plus their lifecycle/enum types.
- `shared/.../policy/` — business rules as pure functions/objects over domain types (e.g. `RecycleBinPolicy`).
- `shared/.../sync/` — offline-change and conflict-resolution DTOs plus `ConflictReviewPolicy`.
- `shared/.../api/ApiContracts.kt` — `ApiPaths` (every REST endpoint name) and every request/response DTO. Treat this file as the contract between all three apps and the future backend; when adding a feature, extend this file first.
- `webApp/src/wasmJsMain/kotlin/com/ekms/web/` — portal screens/models, one `*Screen.kt`/`*Models.kt` pair per workflow area (see the supplier-workflow-to-screen mapping table in `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md`). Sample/in-memory data here stands in for the backend and must eventually be replaced by real API calls.
- `terminalApp/src/main/java/com/ekms/terminal/hardware/` — cabinet serial protocol, gateway/controller layers, NFC/fob enrollment; `terminalApp/.../ui/` — Terminal-side admin and enrollment screens; `terminalApp/.../data/TerminalAdminStore.kt` — local terminal-side state/outbox.
- `mobileApp/src/main/java/com/ekms/mobile/` — currently a minimal Super Admin companion shell.

## Working in `docs/`

`docs/API_HANDOVER_SUPER_ADMIN V{1..4}.md` are dated snapshots of the API handover — V4 is the latest; don't edit older versions, add a new one instead if asked to revise the handover. `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` is the current living Website spec and includes an acceptance checklist — consult it before changing web portal workflow behavior.

Add the following section to CLAUDE.md under an appropriate heading (e.g. "UX Baseline" or "Design Conventions"):

## Terminal App UX Baseline

terminalApp's user-facing flow must match the supplier's Smart Key Cabinet
User Manual (V2.1), since terminalApp runs on the same F7G18P hardware the
manual describes. Do not invent new UX patterns for core flows — replicate:

- Standby screen: tap-to-wake into login, not always-on login screen
- Login methods (all on one screen): personnel card swipe, key card swipe
  (returns directly), account/password, Face Recognition button, Fingerprint
  Recognition button
- Key retrieval screen: must support both "Layout Display" (grid matching
  physical cabinet layout) and "List Display" (simple list) — user-toggleable
- Return flow: physically-driven, not menu-driven. Swipe key card near the
  card-swipe area, box door pops open with blue light, user inserts key.
  No extra confirmation screens.
- Optional key-return re-authentication: if "Key Return Certification" is
  enabled in terminal settings, return requires login again before completing
- Video/photo recording during retrieval/return is a background toggle
  (terminal setting), never a user-facing step
- Admin menu is a separate mode, only reachable after admin login — never
  mixed into the ordinary operator flow

Architecture note: business logic (key/node validation, access grants,
sync-conflict resolution, Recycle Bin rules) belongs in the `shared` module
so it can be reused by webApp and mobileApp later. Hardware-specific code
(serial protocol, fingerprint module, camera, NFC) stays Android-only in
terminalApp.

Add the following section to CLAUDE.md, right after "Terminal App UX Baseline":

## Web/Mobile App UX Consistency

webApp and mobileApp are Super Admin-facing, not operator-facing — they do
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