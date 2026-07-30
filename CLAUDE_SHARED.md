# CLAUDE_SHARED.md

_Part of the split CLAUDE.md documentation set — cross-cutting info all three apps (or agents in general) need. See [CLAUDE.md](CLAUDE.md) for the index, the "Production is already live" warning, and the non-negotiable architectural boundaries list (both kept there since they apply everywhere)._

## Project

eKMS is a Kotlin Multiplatform key-management system: an Android Terminal (physical key cabinet controller), an Android mobile Super Admin companion app, and a Super Admin web portal, all sharing domain models/policies/API contracts through a `shared` module. A real backend now exists (`backend/`, Express.js + MySQL, REST API at `/v1`) — terminalApp has real offline-first sync wiring against it (`TerminalApiClient`/`TerminalSyncCoordinator`/`TerminalSyncOutbox`), and the live Super Admin portal is the React app in `web/` (real-backend-connected for list/create/update/delete across the main admin areas — see Project Status). Some older areas (e.g. appointment-permissions client stubs) and full vendor-manual UX audit remain incomplete. `mobileApp` remains 100% local in-memory demo data with zero network code.

## Toolchain (do not drift from this baseline)

- JDK 17, Gradle **8.13**, Android Gradle Plugin 8.11.1, Kotlin 2.2.20, `compileSdk = 36`, `minSdk = 26`.
- Do not upgrade to Gradle 9.x without migrating the whole KMP build to a newer AGP model — see `docs/BUILD_SETUP.md`.
- Kotlin and Java bytecode targets are explicitly pinned to JVM 17 on Android modules; a mismatch here is a known historical failure mode (Kotlin defaulting to 21 vs. Java defaulting to 11) and build scripts guard against it.
- **Git LFS is required** to correctly check out `terminalApp/src/main/assets/models/` (face-enrollment ML models — see "Face enrollment" in `CLAUDE_TERMINAL.md`'s Project Status and its Known Issues note). Run `git lfs install` once per machine after cloning; without it, `git pull`/`git clone` will leave those files as small LFS pointer-text stubs instead of real model binaries, and `OpenCvFaceEngine`/`MediaPipeFaceLandmarkerEngine` will fail to load at runtime. `.gitattributes` (repo root) defines the tracked patterns (`*.onnx`, `*.task`, `*.tflite`, `*.pb`, `*.bin` under that one directory) — extend it there, not elsewhere, if a new model format is added.
- **Windows/PowerShell dev machines**: `JAVA_HOME` is not set by default in a fresh shell — every Gradle invocation needs `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (or your JDK 17 install) set first, or Gradle fails before reaching any real error.
- **Node is present on this dev machine, just not on `PATH`**: `C:\Program Files\nodejs\node.exe`/`npm.cmd` exist and work — prepend `$env:PATH = "C:\Program Files\nodejs;$env:PATH"` in a fresh shell before any `npm`/`node` command, or you'll get "not recognized" and wrongly conclude Node isn't installed on this machine (many earlier Completed entries assumed exactly that — check this note before repeating that claim). `web/node_modules` is not checked in; run `npm install` once before `npm run build`/`npm run lint` can find `tsc`/`vite`/`oxlint`.

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
| `backend` | Node.js (Express + MySQL) | **Already running in production** on the VPS behind `https://kms-cvt.com/v1` (see `CLAUDE_BACKEND.md`'s "Production is already live" section and `backend/DEPLOY.md`). Source of truth for routes is this repo's `backend/`; day-to-day agent work usually does **not** need to start or redeploy it unless the user asks. REST at `/v1`: `auth`, `admin` (sites/terminals/users/keys/key-slots/access-grants/recycle-bin/sync-conflicts/event-definitions/schedules/personnel-groups/key-groups/multi-authentication-rules/appointment-reasons/appointments/appointment-permissions), `audit`, `reports`, `terminal/sync`. |
| `docs` | — | Backend/API handover documents; treat `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` and the `API_HANDOVER_SUPER_ADMIN` series as the living spec for the backend/portal contract. `docs/Backend_Integration_Handover.md` predates the real backend's existence and is now stale in places (still says "there is no backend today") — read it for the schema-fragmentation and NFC-UID background, which are still accurate, but don't trust its "not implemented" claims about the backend itself without checking `backend/` first. |

### Non-negotiable architectural boundaries

See `CLAUDE.md` — kept there (not duplicated here) since these 8 rules apply everywhere regardless of which file an agent opens first.

### Where things live

- `shared/.../domain/` — core entities (`AdminUser`, `Site`, `Terminal`, `ManagedKey`, `KeySlot`, `AccessGrant`, `CredentialBinding`, `AuditEvent`) plus their lifecycle/enum types.
- `shared/.../policy/` — business rules as pure functions/objects over domain types (e.g. `RecycleBinPolicy`).
- `shared/.../sync/` — offline-change and conflict-resolution DTOs plus `ConflictReviewPolicy`.
- `shared/.../api/ApiContracts.kt` — `ApiPaths` (every REST endpoint name) and every request/response DTO. Treat this file as the contract between all three apps and the future backend; when adding a feature, extend this file first.
- `shared/.../protocol/` — the Key Cabinet Communication Protocol's frame layer (`SplitNibbleCodec`, `KeyCabinetCrc8`, `KeyCabinetFrame`/`KeyCabinetFrameCodec`) and command driver (`KeyCabinetLink`, `SerialTransport`), all pure Kotlin with unit tests against the vendor doc's worked examples (`shared/commonTest/.../protocol/`, including `FakeSerialTransport` for hardware-free testing). No serial I/O lives here — see boundary #1 in `CLAUDE.md`.
- `webApp/src/wasmJsMain/kotlin/com/ekms/web/` — **frozen, not built** (see Module architecture). All 19 supplier-manual routes live in two monolith files, `WebPortalScreens.kt` and `WebPortalModels.kt` (`internal class WebPortalStore`), not a one-file-per-area split despite the doc comment that used to describe one. Historical reference only — `web/` has already surpassed several of its gaps (see `CLAUDE_WEB.md` Project Status), so don't assume `webApp`'s audit findings still describe `web/`.
- `web/src/` — the live Super Admin portal (React+Vite+TypeScript). `src/api/client.ts` is the backend client; `src/App.tsx` is routing/shell; `src/pages/*.tsx` are the workflow screens; `src/components/MalaysiaUnitsMap.tsx` is the unit-hierarchy map view; `src/components/ErrorBoundary.tsx` guards against blank-screen failures (e.g. Leaflet map load failures in Edge).
- `backend/src/routes/` — one router file per resource (`sites.js`, `terminals.js`, `users.js`, `keys.js`, `keySlots.js`, `accessGrants.js`, `recycleBin.js`, `credentials.js`, `audit.js`, `sync.js`) plus `phase4.js`, which bundles several newer routers together (event definitions, schedules, personnel/key groups, multi-auth rules, appointments + reasons + permissions, reports). `phase4.js`'s routers are fully implemented and mounted in `backend/src/index.js`; unlike the frozen `webApp` (which never wired any of this), `web/`'s API client (`web/src/api/client.ts`) has list/create/update/delete methods for all of these, called from real pages (`SimpleResources.tsx` for events/schedules/groups/appointment-reasons, `MultiAuthPage.tsx`, `AppointmentsPage.tsx`, `LogsPages.tsx`) — see boundary #4 for the PATCH/`expectedRevision` details and the caveat that this hasn't been compiled/run yet in any dev environment.
- `terminalApp/src/main/java/com/ekms/terminal/hardware/` — `AndroidSerialTransport` (implements `shared`'s `SerialTransport` against the vendor serial AAR), `CabinetHardwareController` (owns the connection, background executor, and guided enrolment/return flows on top of `KeyCabinetLink`), plus the separate `/dev/ttyS2` public-card-reader path (`PublicM1CardReader`/`PublicCardReaderController`) and NFC/fob enrollment; `terminalApp/.../ui/` — Terminal-side admin and enrollment screens; `terminalApp/.../data/TerminalAdminStore.kt` — local terminal-side state/outbox.
- `mobileApp/src/main/java/com/ekms/mobile/` — currently a minimal Super Admin companion shell.

## Working in `docs/`

`docs/API_HANDOVER_SUPER_ADMIN V{1..4}.md` are dated snapshots of the API handover — V4 is the latest; don't edit older versions, add a new one instead if asked to revise the handover. `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` is the current living Website spec and includes an acceptance checklist — consult it before changing web portal workflow behavior.

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

## Hardware Feature Findings

Permanent record of features that were investigated against real hardware and found not
currently buildable — read this before re-proposing one of these, so the investigation isn't
silently redone. This section is reference-quality, not a diary entry: it documents the finding
and the reasoning, not the session-by-session narrative of how it was tested (that narrative
lived in a since-reverted temporary test-tooling branch of work; the temporary tooling itself was
fully removed after the investigation concluded, matching this repo's `SoundTestActivity`
precedent for throwaway hardware-eval tools).

This section spans terminal (reader hardware) and mobile (HCE) — kept whole here rather than split; `CLAUDE_MOBILE.md` and `CLAUDE_TERMINAL.md` each carry a one-line pointer back to it.

### Digital Key (NFC phone-as-card) — infeasible on current reader hardware

**Goal that was investigated:** let a Super Admin's phone act as a physical key card at the
terminal's public card reader (`/dev/ttyS2`, `PublicM1CardReader`), instead of requiring a
separate physical NFC card — a "Digital Key" feature.

**Three independent test signals, all pointing the same direction:**

1. **Documentation search**: `docs/Key Cabinet Communication Protocol.md` and the rest of the
   repo's vendor documentation contain no reader command beyond the single documented UID poll,
   `02 AF DD`. Nothing in the spec suggests the reader supports APDU exchange, ISO14443-4, or any
   other command that could carry a chosen/asserted identity rather than a raw anti-collision UID.
2. **Real HCE (Host Card Emulation) phone test**: a standard Android `HostApduService` was
   registered and tapped against the real reader. It only ever produced a randomized,
   NFC-standard-shaped UID (the `08`-prefix pattern typical of Android's anti-collision layer) —
   never a chosen or previously-captured UID, and never reproducibly the same value twice.
3. **Raw-opcode probing on real hardware**: with a real physical card present at the reader (to
   rule out "reader is idle" as a confound), 8 adjacent candidate opcodes were sent — 16 attempts
   total across two clean passes — against the live reader. Zero responses beyond the exact-match
   `02 AF DD` baseline.

**Reconciling finding — the reader is not the bottleneck.** The reader itself was proven working
throughout (it correctly answers the one command it supports, every time, with a real card
present). The actual constraint is that **standard Android's public HCE API deliberately
randomizes the low-level anti-collision UID for user-privacy reasons, and no public API lets an
app override it.** That randomization happens below the app's HCE service — it's a platform
privacy control, not a bug or a configuration option — so no amount of app-side code on a
standard Android phone can present a chosen or stable UID to any reader.

**Samsung Wallet / Infinix native-NFC finding.** Phone-as-card was observed to work on those two
specific OEMs, but only via a proprietary, manufacturer-locked system capability — not a public
Android API, and not something any third-party app (including this one) can invoke or replicate.
This is OEM-specific behavior, not a general Android capability, and doesn't generalize to other
phones.

**Samsung Wallet Partner Program — considered and ruled out.** This is a business partnership
program for access-control credentials, and it requires a reader capable of a compatible secure
protocol (not raw-UID cloning) — the current reader hardware doesn't qualify. It also wouldn't
help on Infinix or other non-Samsung OEMs regardless, so it doesn't solve the general problem
even where it might technically apply.

**Conclusion:** Digital Key (phone-as-card) is not currently buildable against this reader
hardware, on any phone, via any mechanism available to a third-party app. Two directions remain
viable if this is revisited later:
- **(a) "Magic card" mechanism** — the phone reads/writes a separate, genuinely rewritable
  physical NFC card (the phone never presents itself as the card to the reader; it just
  provisions a real card the user then taps). Buildable today with current hardware.
- **(b) Reader hardware upgrade** — replace the current reader with one that's genuinely
  APDU/ISO14443-4-capable, which would make real HCE-based Digital Key possible.

**Status:** marked "Coming Soon" in `mobileApp`'s UI — an in-app dialog only, with neutral
end-user copy that does not reference this hardware-limitation investigation (that context is
reference-only, meant for engineers/agents, not end users). Not on the current roadmap; revisit
only if the reader hardware changes or the magic-card direction becomes a product priority.

## Project Status (shared-module foundational history)

These are the only Project Status changelog entries that are genuinely about the `shared` module's foundation rather than any one app; all other Project Status content (status-truth table, per-app Completed entries, Known issues, Next steps) lives in `CLAUDE_TERMINAL.md`, `CLAUDE_WEB.md`, `CLAUDE_MOBILE.md`, and `CLAUDE_BACKEND.md`.

### Completed

- Step 1-3: shared policy/sync/Recycle Bin foundation, Super Admin Users & Credentials, Sites & Terminals UI with cabinet-config validation.
- Step 4: Keys, cabinet slots, access grants (`ManagedKey`/`KeySlot`/`AccessGrant` + `KeySlotAccessPolicy` node-address validation), wired into webApp and terminalApp.
