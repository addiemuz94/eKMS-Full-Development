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

1. **Only the Android Terminal touches cabinet hardware.** Website and Mobile must never open a serial port, send a cabinet command/frame, or perform reader/NFC/biometric capture. The split-nibble/CRC8 frame protocol and full node command set (`KeyCabinetLink`, plus `SplitNibbleCodec`/`KeyCabinetCrc8`/`KeyCabinetFrame`) live in `shared/.../protocol/` as pure Kotlin with no serial dependency, so they're unit-testable without hardware — but only `terminalApp/src/main/java/com/ekms/terminal/hardware/` (`AndroidSerialTransport`, `CabinetHardwareController`) may actually open `/dev/ttyS1`/`/dev/ttyS2` and drive them.
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
- `shared/.../protocol/` — the Key Cabinet Communication Protocol's frame layer (`SplitNibbleCodec`, `KeyCabinetCrc8`, `KeyCabinetFrame`/`KeyCabinetFrameCodec`) and command driver (`KeyCabinetLink`, `SerialTransport`), all pure Kotlin with unit tests against the vendor doc's worked examples (`shared/commonTest/.../protocol/`, including `FakeSerialTransport` for hardware-free testing). No serial I/O lives here — see boundary #1.
- `webApp/src/wasmJsMain/kotlin/com/ekms/web/` — portal screens/models, one `*Screen.kt`/`*Models.kt` pair per workflow area (see the supplier-workflow-to-screen mapping table in `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md`). Sample/in-memory data here stands in for the backend and must eventually be replaced by real API calls.
- `terminalApp/src/main/java/com/ekms/terminal/hardware/` — `AndroidSerialTransport` (implements `shared`'s `SerialTransport` against the vendor serial AAR), `CabinetHardwareController` (owns the connection, background executor, and guided enrolment/return flows on top of `KeyCabinetLink`), plus the separate `/dev/ttyS2` public-card-reader path (`PublicM1CardReader`/`PublicCardReaderController`) and NFC/fob enrollment; `terminalApp/.../ui/` — Terminal-side admin and enrollment screens; `terminalApp/.../data/TerminalAdminStore.kt` — local terminal-side state/outbox.
- `mobileApp/src/main/java/com/ekms/mobile/` — currently a minimal Super Admin companion shell.

## Working in `docs/`

`docs/API_HANDOVER_SUPER_ADMIN V{1..4}.md` are dated snapshots of the API handover — V4 is the latest; don't edit older versions, add a new one instead if asked to revise the handover. `docs/WEB_PORTAL_WORKFLOW_HANDOVER.md` is the current living Website spec and includes an acceptance checklist — consult it before changing web portal workflow behavior.

## Terminal App UX Baseline (STRICT — exact clone)

terminalApp must replicate the Smart Key Cabinet User Manual V2.1 exactly.
This is not "inspired by" — every screen, flow order, interaction, button
placement, and piece of terminology in the manual must be built as
described. Do not add, remove, reorder, or reinterpret any step.

The ONLY two things allowed to differ from the manual:
1. Color theme / visual branding
2. The first-start standby/screensaver screen shown before login

Everything else must match, including:
- Standby → tap-to-wake → login screen
- Login screen with all four methods together: personnel card swipe, key
  card swipe (returns directly), account/password, Face Recognition
  button, Fingerprint Recognition button
- Key retrieval: both "Layout Display" and "List Display", user-toggleable
- Return flow: swipe key card near the card-swipe area, box door pops open
  with blue light, insert key, done — no extra confirmation screens
- Optional key-return re-authentication, controlled by a terminal setting
  ("Key Return Certification")
- Background video/photo recording during retrieval and/or return,
  controlled by terminal settings, never a user-facing step
- Full Admin Menu, reachable only after admin login, containing: terminal
  name, Key Cabinet ID, modify administrator password, set server address,
  activation code setup, key node setting, Ethernet MAC address display,
  key return certification toggle, return video toggle, key retrieval
  video toggle

Before building or modifying any terminalApp screen, check it against the
manual section by section. If something in the current code deviates from
the manual (beyond color/screensaver), it is a bug to fix, not a design
choice to keep.

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

## Project Status

### Completed
- Step 1-3: shared policy/sync/Recycle Bin foundation, Super Admin Users &
  Credentials, Sites & Terminals UI with cabinet-config validation
- Step 4: Keys, cabinet slots, and access grants (ManagedKey/KeySlot/
  AccessGrant + KeySlotAccessPolicy node-address validation), wired into
  webApp and terminalApp
- terminalApp UI/UX rebuilt to strictly match Smart Key Cabinet User
  Manual V2.1 (see "Terminal App UX Baseline (STRICT)" section):
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

### Known issues / not yet resolved
- Personnel management in webApp is currently a shallow free-text form
  (no role picker, no email/site validation) since the old
  UserManagementPolicy-based flow was deleted — needs rebuilding against
  current architecture
- Orphaned scaffold TerminalWorkflowModels.kt/TerminalWorkflowScreens.kt in
  terminalApp — audited, found to NOT correctly match the manual (extra
  confirmation steps, recording notice banners violating "never
  user-facing", wrong terminology). Moved to reference-only, not merged:
  see `terminalApp/reference/*.reference.kt.bak` (includes
  `FobEnrollmentScreen.reference.kt.bak`, archived alongside it since it
  depended on the same types) and `terminalApp/reference/README.md` for why.
- **Needs physical hardware to fully verify (phases 7-9 and the card-UID
  fix):** everything so far was only exercised through `FakeSerialTransport`
  and Gradle compile/test/assemble — no physical F7G18P run yet for the
  retrieval/return command sequences, the auto-connect path, real
  bolt-detection timing, or the section 9 reader's UID lookup against real
  enrolled cards.

### Next steps (in order)
- Phase 10 (suggested): verify phase 9's command sequences and the card-UID
  disambiguation fix against a physical F7G18P — this is the first
  not-yet-done item and unblocks confidently trusting the rest of the
  hardware-wired flows
- After hardware phases: rebuild Personnel management properly

### Reference
- Hardware protocol: `docs/Key Cabinet Communication Protocol.md` (note
  the spaces in that filename) is the authoritative vendor spec — read
  before any phase 8+ work. `docs/Key_Cabinet_Communication_Protocol.md`
  (underscored) is a project-level index onto it and defers to it on any
  conflict.
- Terminal UX baseline: Smart Key Cabinet User Manual V2.1 (STRICT clone,
  color theme + first-start screensaver are the only allowed differences)