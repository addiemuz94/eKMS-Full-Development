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
  - **Bug found, NOT fixed (deferred — real design work, not a
    one-liner):** `TerminalAdminApp`'s public-reader `CardUidMatch.Key`
    branch looks up the matched key in `retrievalKeys` (demo `ManagedKey`
    fixtures) using a real `TerminalKey.id` — two different ID
    spaces/types that can never match. `matchedKey` is therefore always
    null for a real enrolled key, so `TerminalKeyReturnScreen`'s
    documented null-`slot` fallback ("no node to address... falls back to
    the screen's original fixed-delay completion") fires every time:
    confirmed live — tapping a real enrolled key fob at login correctly
    resolved as `CardUidMatch.Key` and showed "insert the key," but sent
    zero physical commands. This is the same "two incompatible key
    schemas" gap the backend handover doc already flags
    (`ManagedKey`+`KeySlot`, shared/demo, vs `TerminalKey`,
    terminal-local) — the swipe-to-return production path is non-
    functional for any real key until this is bridged.
  - **Bug found, NOT fixed (deferred — needs a real design pass, same as
    above):** `CabinetHardwareController.releaseKeyForPickup` (the
    production key-retrieval path) only calls `engageElectromagnet` +
    `testMicroSwitch` — it never calls `ejectDoor()`. Confirmed live: the
    electromagnet unlocked correctly, but the door never physically
    opened, so the key was unreachable. `beginKeyReturn` does not have
    this bug (it already calls `ejectDoor()`). Manually sending 0x23 from
    the admin console immediately fixed it, confirming the fix is just
    adding the missing call — but the door-eject/electromagnet-engage
    ordering and any UX implications are being left for a dedicated pass
    rather than patched ad hoc.
  - Both deferred bugs and both fixed bugs are logged in this session's
    memory (`phase10_retrieval_door_eject_bug.md`,
    `phase10_card_uid_bugs.md`) for continuity across conversations.

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
- **`releaseKeyForPickup` never ejects the cabinet door** (confirmed on
  real hardware in Phase 10) — the production key-retrieval path unlocks
  the electromagnet but never sends 0x23, so the operator cannot
  physically reach the released key. `beginKeyReturn` does not have this
  bug. Fix confirmed simple (add the missing `ejectDoor()` call,
  empirically validated order: engage → eject → test micro switch) but
  deliberately deferred to a dedicated pass rather than patched ad hoc —
  see `phase10_retrieval_door_eject_bug.md` in this project's memory.
- Phase 10 also found and fixed two bugs in `CardEnrollmentScreen` in the
  same session (stale-closure enrollment misdirection; wired to demo key
  fixtures instead of real keys) — see the Completed section above and
  `phase10_card_uid_bugs.md` for detail; these are resolved, not
  outstanding.
- ~~The key-card swipe-to-return trigger cannot resolve any real enrolled
  key~~ — **fixed** during the Key Return Flow implementation (see
  Completed section above, `managedKeyAndSlotFor()`); was previously
  logged here and in `phase10_card_uid_bugs.md` as a Phase 10-deferred
  bug, now resolved.
- Key Take Flow and Key Return Flow (both new this session) have not yet
  been run against physical hardware — only compile/test/assemble
  verified so far, same caveat the rest of the hardware-wired flows had
  before Phase 10.

### Next steps (in order)
- Verify the Key Take Flow and Key Return Flow against a physical
  F7G18P (door-open confirmation, both flows' timer pairs, the Take
  Warning Time / Door-Close Warning Time countdowns) — this is now the
  first not-yet-hardware-verified item
- Fix `releaseKeyForPickup`'s missing door-eject (the one remaining
  Phase 10-deferred bug — see `phase10_retrieval_door_eject_bug.md`) —
  needs a dedicated design pass per the user's explicit request, not an
  ad hoc patch
- webApp/mobileApp standing-alert UI: the Key Take Flow's door-left-open
  case (Super Admin-only) and the Key Return Flow's abandoned-return case
  (terminal user + Super Admin, two-party) — both demo-data-driven
  mockups, explicitly scoped out of their respective implementation
  passes; likely worth building together given the overlapping UI
- After hardware phases: rebuild Personnel management properly

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