# eKMS Backend Integration Handover

**Audience:** the backend developer picking up this project. You have not seen this codebase before. This document tells you exactly what already exists in code, what's real vs. mocked, and what's still undecided — so you know what to build first and what to go ask the project owner about before you start.

**One-paragraph orientation:** eKMS is a key-cabinet management system with three client apps — an Android Terminal that drives the physical key cabinet hardware directly over a serial protocol, a Kotlin/Wasm web portal for Super Admins, and a thin Android mobile companion app — all sharing Kotlin domain models and API contracts through a `shared` module. **There is no backend today.** The web portal and mobile app run entirely against hardcoded, in-memory sample data that stands in for a backend. Your job is to build the real REST backend that these three clients already assume exists.

---

## 1. API contract status

### Important: the file you uploaded describes a different system than what's implemented

`docs/API Documentation_20260424.md` (the file you provided) is the vendor's own **"Key Cabinet Management Platform" third-party integration API** — a protocol for a third-party backend to sync with the *vendor's own embedded/Android/commercial terminal firmware* (endpoints like `/syncDirectWs/appendUser`, `/syncDirectWs/readKey`, `/syncDirectWs/remoteTake`, `/push/postRecord`, using field names like `numberStr`, `nodeAddr`, `adminType`, `card`, `faceList`, `terminalUuid`).

**None of it is implemented anywhere in this codebase.** I grepped the entire repository for every distinctive term from that document — `syncDirectWs`, `remoteTake`, `CommonResult`, `terminalUuid`, `adminType`, `faceList`, `fingerList`, `numberStr` — and got zero matches, in any file, in any module. Every one of that document's 21 endpoints (photo/video upload, the 15 `syncDirectWs/*` sync operations, and the event-record push receiver) is **not referenced anywhere** in `shared`, `webApp`, `terminalApp`, or `mobileApp`.

This makes sense once you understand the architecture: this project's Android Terminal does **not** run the vendor's terminal firmware and does **not** talk to the vendor's management backend. It's a custom-built Android app that opens the cabinet's raw serial port directly (`/dev/ttyS1`, split-nibble/CRC8 framing per `docs/Key Cabinet Communication Protocol.md`) and bypasses the vendor's entire software stack. The vendor doc you uploaded describes an alternative integration path this project chose not to use.

**Action item — flag this to the project owner before doing any work against that document:** it's unclear whether that vendor Management Platform API is:
- (a) pure background reference material from vendor evaluation, safe to ignore entirely (most likely, given the codebase never references it), or
- (b) something the real backend is still expected to integrate with for some reason (e.g. interoperating with off-the-shelf vendor cabinets alongside the custom ones) — there is currently **zero evidence for this** in the code or in CLAUDE.md.

Don't build against `API Documentation_20260424.md` until this is confirmed. One incidental thing worth keeping, though: that document's event-type list (Take/Return/Door open/close/Illegal open/close/Boot/Admin settings/Emergency open/close/Self-check/Timeout unreturned/Login photo/Node fault/recovery/Password wrong/Alcohol test) is richer than this project's own `AuditEventType` enum (see §2) and may be useful inspiration later, even though it's not a contract to implement.

### What's actually implemented: `shared/api/ApiContracts.kt` against `docs/API_HANDOVER_SUPER_ADMIN V4.md`

The real contract is `docs/API_HANDOVER_SUPER_ADMIN V4.md` — a project-authored draft spec (V1–V3 are superseded dated snapshots; V4 is current). `shared/src/commonMain/kotlin/com/ekms/shared/api/ApiContracts.kt` implements this as a set of path-string constants (`ApiPaths`) plus request/response DTOs — **but it is contract-only.** There is no HTTP client anywhere in this repository (confirmed by checking every `build.gradle.kts` and grepping for Ktor/OkHttp/Retrofit/`HttpClient`/`fetch(` — zero matches across the whole repo). No base URL, no auth-token handling, no actual network call exists yet. You are building the transport layer from scratch on both ends conceptually, though the client-side wiring is also not yet started (see §5).

Of the 18 endpoint paths defined in `ApiPaths`:

**Fully modeled (path + complete request/response DTOs):**
- `ADMIN_SITES`, `ADMIN_TERMINALS`, `ADMIN_KEYS`, `ADMIN_KEY_SLOTS`, `ADMIN_ACCESS_GRANTS` — each has an upsert request DTO (create and update share one type, distinguished by whether `expectedRevision` is populated) matching V4's required-fields table closely.
- `ADMIN_KEY_FOB_ENROLLMENT` — response DTO plus an audit-payload DTO. By design, the request body (which would carry the raw scanned UID) is deliberately *not* in this shared contract — see §4.
- `SYNC_BOOTSTRAP`, `SYNC_PUSH` — request/response DTOs exist and field names line up with V4 §5's examples (`terminalId`, `lastSuccessfulSyncEpochMillis`, `localRevision`, `changes`, `auditEvents`).

**Partially modeled (path exists, but the DTO is incomplete or the V4 spec describes more than the DTO covers):**
- `ADMIN_USERS` — create/update/account-status DTOs exist, but there's no DTO for the list-with-pagination query V4 describes (`GET /v1/admin/users?state=ACTIVE&siteId=&cursor=`), and no explicit single-user response shape (the raw `AdminUser` domain model is presumably meant to be reused, but that's an assumption, not a documented one).
- `ADMIN_USER_CREDENTIALS` — only has a DTO for *requesting* enrollment. V4 also specifies `GET .../credentials` (list status) and `POST .../credentials/{credentialId}/revoke`; neither has a corresponding DTO, even though the domain model already has an `AuditEventType.KEY_FOB_REVOKED` value implying revoke was planned.
- `RECYCLE_BIN` — only a list-response DTO exists. V4 describes five distinct operations (list, get-one, restore, purge-one, purge-expired); only the list is modeled. Restore and purge have no request DTOs at all.
- `SYNC_CONFLICTS` — `SyncConflict`/`ConflictResolution` types exist (in the `sync` package, not tied to this path constant), but there's no request DTO for the actual `POST /v1/admin/sync-conflicts/{id}/resolve` call V4 documents with a concrete JSON shape (`strategy`, `mergedPayload`, `expectedConflictRevision`). The closest thing, `ConflictResolution.mergedPayloadJson`, stores merge content as an untyped string, not the structured object V4 implies.
- `TerminalBootstrapResponse.changesJson` is typed as `List<String>` (raw, un-typed JSON strings) rather than a structured list of configuration changes — V4's text describes richer content ("authoritative configuration changes, user/credential/access cache") than this field's type can express as-is.

**Not modeled at all (path exists in `ApiPaths` but zero DTOs anywhere):**
- `AUTH_LOGIN`, `AUTH_REFRESH` — V4 §2 gives a full request/response shape (identifier, password, clientType, deviceId → access token, refresh token, profile, role, expiry, permitted site IDs); none of it exists as a Kotlin type yet. **This means authentication itself — arguably the first thing a backend needs — is entirely undesigned in code.**
- `SUPER_ADMIN_DASHBOARD` — no summary/metrics DTO.
- `TERMINAL_DATA_READ`, `TERMINAL_DATA_DOWNLOAD` — referenced only as plain strings inside webApp notice/toast text (see §5), never as typed contracts. It's not even fully clear from the code how these two differ from `SYNC_BOOTSTRAP`/`SYNC_PUSH` — worth clarifying with the project owner whether they're duplicative naming from an earlier draft or a genuinely separate operation.
- `AUDIT_EVENTS` — no request or response DTO, despite `AuditEventType` having 25 defined values ready to be emitted.

**Missing from `ApiContracts.kt` entirely (not even a path constant), but specified in V4:**
- V4 §6, "Vendor approval" (`/v1/vendor/registrations`, `/v1/vendor/requests`, `/v1/admin/vendor-requests`, approve/reject) — V4 itself labels this "next Super Admin feature," so this may be intentionally deferred, but it's worth knowing it hasn't been started at the contract level at all, even though `mobileApp`'s companion app already has placeholder UI text expecting it ("Vendor approvals will load from the backend once the API and sync layer are connected").

**One deliberate, correct divergence worth knowing about (not a bug):** V4's summary table (§3) lists a Key's required field as `keyFobUid`, but the actual `KeyUpsertRequest` DTO uses `fobEnrollmentReference` instead — an opaque, non-reversible token, never a raw UID. This is intentional: CLAUDE.md's security boundary explicitly forbids raw NFC UIDs from ever appearing in a shared DTO. If you see `keyFobUid` mentioned in older docs or in conversation, the real field name to implement against is `fobEnrollmentReference`, and its value must stay opaque server-side too (see §4).

---

## 2. Data models ready for backend

All of these live in `shared/src/commonMain/kotlin/com/ekms/shared/domain/` (plus `sync/` and `policy/` for the sync/Recycle Bin types) and are annotated `@Serializable`, so they're ready to use as-is for JSON wire format on a Kotlin backend, or as a schema reference for any other stack.

| Model | Completeness | Notes for the backend |
|---|---|---|
| `AdminUser` (id, displayName, email, role, assignedSiteIds, accountStatus, lifecycle) | Complete as a shape | No password/credential field on this model at all — credentials are a separate `CredentialBinding` record. No client UI currently populates every field (see §5's personnel gap). |
| `UserRole` (SUPER_ADMIN / TECHNICIAN / VENDOR) | Complete, deliberately minimal | Doc comment says explicitly: "add roles only when their rules are defined" — don't expand this without a corresponding rule change. |
| `AccountStatus` (ACTIVE / DISABLED) | Complete | Distinct concept from Recycle Bin lifecycle — a disabled user still exists and can be re-enabled; a recycled user is soft-deleted. Don't conflate the two states. |
| `Site` (id, name, address, lifecycle) | Complete, simple | No geolocation, timezone, or contact fields — just name/address. |
| `Terminal` (id, siteId, name, boxAddress, serialNumber, lifecycle, configuredSlotCount, cabinetSerialPort, cabinetBaudRate, connectionState) | Complete | `cabinetSerialPort`/`cabinetBaudRate` are explicitly documented as "Web and Mobile may display but never open this port" — these are metadata about the Terminal's own hardware config, not something the backend drives directly. `connectionState` is status-only, never a command. |
| `ManagedKey` (id, siteId, displayName, fobEnrollmentReference, lifecycle) | Complete, by design minimal | `fobEnrollmentReference` is a placeholder-by-design opaque string — see §4. This is intentionally not a rich key record; box/node placement lives separately on `KeySlot`. |
| `KeySlot` (id, terminalId, nodeAddress, managedKeyId, lifecycle) | Complete | `nodeAddress` is the real cabinet protocol address (1–127, door is address 0 and is never a slot) — never apply a UI +1/-1 offset to this field, per CLAUDE.md. |
| `AccessGrant` (id, userId, siteId, keyIds, validFromEpochMillis, validUntilEpochMillis, lifecycle) | Complete | One grant covers a *set* of keys, with optional time-bounding. Note the terminal-local bootstrap store's equivalent type only supports a single key per grant — see the schema-fragmentation note below. |
| `CredentialBinding` (id, userId, kind, reference, active, lifecycle) | Complete shape, but enforcement is a promise, not code | Doc comment states NFC UIDs/passkey verifiers "must be encrypted at rest in production" — this model doesn't enforce that; it's a requirement for your backend implementation to actually satisfy. |
| `CredentialKind` (NFC_CARD, STATIC_UID_DIGITAL_KEY_PROTOTYPE, FINGERPRINT, FACE_RECOGNITION, VENDOR_PASSKEY) | Complete enum | Per V4 §8: face-recognition auth is "not enabled" yet and a true phone NFC Digital Key is deferred — `STATIC_UID_DIGITAL_KEY_PROTOTYPE` is the interim approach (a physical NFC tag mapped to a logical ID), don't assume phone-native NFC works today. |
| `AuditEvent` / `AuditEventType` (25 values) | Types are complete; no ingestion/query contract | The type enum is thorough (login, key take/return, user/site/key changes, Recycle Bin moves, conflict lifecycle, fob enrollment/revoke, etc.) but nothing calls `/v1/audit/events` yet from any client — see §1's gap note. |
| `RecycleBinEntry` / `RecycleBinPolicy` | Complete, pure logic | 60-day retention, Super-Admin-only access and purge — see §3 for the caveat that this is client-side-shaped logic the backend must independently re-enforce, not delegate to. |
| `LifecycleMetadata` (state, createdAtEpochMillis, updatedAtEpochMillis, deletedAtEpochMillis, deletedByUserId) | Complete, reused everywhere | Every soft-deletable entity embeds this the same way — consistent shape across User/Site/Terminal/Key/KeySlot/AccessGrant/CredentialBinding. |
| `OfflineChange` / `SyncConflict` / `ConflictResolution` | Shape complete, mechanism absent | See §3 — these describe the data shape of a sync conflict, not how one actually gets created, transmitted, or resolved. |
| `CardUidMatch` / `CardUidResolver` | Complete, deliberately narrow | Pure decision logic only — see §4. |

### Fields that are placeholder/opaque by design (not oversights)

- `ManagedKey.fobEnrollmentReference: String?` — an opaque token issued by the Terminal's protected enrollment flow, explicitly documented as "not a card UID and cannot be reversed to one." The backend should treat this as an inert string it stores and returns, never something to decode or validate the format of.
- `CredentialBinding.reference: String` — same idea: this is expected to hold something backend-and-Terminal-meaningful but never a raw UID/template in transit to Web or Mobile.
- `TerminalBootstrapResponse.changesJson: List<String>` — genuinely underspecified (see §1), not an intentional opacity choice like the two above.

### Demo/sample data — do not treat as reference data

Two objects are explicitly self-documented in code as non-production stand-ins and exist purely so the web portal has something to render without a backend:
- `KeySlotDemoData` (in `domain/KeySlotManagement.kt`) — hardcoded terminals/keys/slots/access grants (e.g. `terminal_hq_demo`, `key_hq_vehicle_demo`).
- `SuperAdminDemoData` (in `domain/UserManagement.kt`) — hardcoded sites/users/credential-status rows (e.g. `usr_super_admin_demo`, `usr_technician_demo`).

Every ID inside these two objects is throwaway fixture data. Don't treat any of it as seed data for a real deployment.

### A schema-fragmentation problem the backend will need to resolve

There are currently **three different, incompatible "person" schemas** in this codebase, and **two different "key" schemas**, none of which sync with each other today:

1. **`AdminUser`** (shared, described above) — the richest model, presumably the eventual backend source of truth.
2. **`TerminalUser`** (terminal-local, in `terminalApp`'s `TerminalAdminStore`) — only `id`, `displayName`, `username`, `role` (its own separate `TerminalUserRole` enum, not `UserRole`), `isPreset`, `createdAtEpochMillis`, plus a locally-stored password hash. No email, no site assignment, no account status, no lifecycle/soft-delete.
3. **`PortalPerson`** (webApp-local, in `WebPortalModels.kt`) — yet another shape: `displayName`, `employeeId`, `siteId`, `userGroup` (free text), `accessWindow` (free text), `accountStatus`, `credentialSummary`. No email, no real `role` field at all.

Similarly, `TerminalKey` (terminal-local: `displayName`, `boxAddress`, `nodeAddress`, `fobFingerprint` baked onto one record) conflates what `shared` splits into two records (`ManagedKey` + `KeySlot`), and uses a different fob-identity mechanism (a SHA-256 hash it computes itself) than `ManagedKey.fobEnrollmentReference` (an opaque reference from a different enrollment flow).

None of these three/two schemas currently reference each other's IDs. **You will need a reconciliation strategy** — either the backend defines canonical `AdminUser`/`ManagedKey` records and both clients migrate to consuming them directly (dropping their local schemas), or you build an explicit mapping/import step. This isn't decided anywhere in the codebase; it's a design conversation to have with the project owner before building the sync endpoints.

---

## 3. Sync and conflict logic

**What's implemented:** the data shapes only — `OfflineChange` (an operation ID, target entity type/ID, the revision it was based on for optimistic-concurrency detection, and an opaque `payloadJson: String` for the actual change content), `SyncConflict` (wraps a losing `OfflineChange` plus the server revision it collided with), and `ConflictResolutionStrategy` (`KEEP_SERVER` / `KEEP_TERMINAL_CHANGE` / `MERGE_MANUALLY`) with a `ConflictResolution` record of who resolved it and how.

**What's NOT implemented — this is the most important thing to understand about this area:**
- **No actual queueing.** There is no offline outbox, no local persistence of pending `OfflineChange` records anywhere in `terminalApp`, and no retry logic. The type exists; nothing produces or stores instances of it yet.
- **No transport.** As noted in §1, there is zero HTTP client anywhere in the repo. Nothing serializes an `OfflineChange` and sends it anywhere.
- **`MERGE_MANUALLY` has no actual merge mechanism.** It's an enum value plus a nullable string field (`mergedPayloadJson`). There is no diff/patch utility, no validation that a merged payload is well-formed, and no function anywhere that actually *applies* any of the three resolution strategies to a record. Building the real merge-resolution behavior (both the UI a Super Admin uses to construct a manual merge, and the backend logic that applies it) is entirely greenfield work.
- **`ConflictReviewPolicy.mayResolve(role)` checks exactly one thing:** `role == SUPER_ADMIN`. It's a pure role gate — no revision check, no "is this conflict already resolved" check, nothing about `SyncConflict.requiresSuperAdminReview`. Don't assume more enforcement exists here than that one line.

**Is this backend-agnostic, or does it assume specific backend behavior?**

It's backend-agnostic in the sense that these are plain, stateless data classes with no coupling to any particular server implementation — any backend that produces/consumes this exact JSON shape would work with the clients as designed. But it's *underspecified* rather than *safely abstract*: because `payloadJson` is an opaque string with no documented per-entity-type schema, and `TerminalBootstrapResponse.changesJson` is likewise an untyped `List<String>`, **the actual contract for "what goes inside a change payload" doesn't exist yet.** You will effectively be defining that JSON schema yourself as you implement the endpoints — it's not hidden somewhere else in the codebase, it genuinely isn't decided. Recommend nailing this down explicitly with the project owner (ideally as a typed sealed structure per entity, not a raw string) before writing the sync endpoints, since three different clients will need to agree on it.

One correctness note from `policy/RecycleBinPolicy.kt` worth carrying into your backend design: the "cascade-block" rule described in CLAUDE.md ("active dependents must block a hidden cascade delete") is **not implemented as a shared, reusable function anywhere.** `DeletePreflightResponse` (in `ApiContracts.kt`) models the *result* of such a check (`allowed`, `blockingReason`, `dependentRecordCount`) but isn't wired to any specific endpoint, and no dependency-scanning logic exists in `shared` for the backend to reuse. You'll need to implement the actual "does this record have live dependents" check yourself, server-side, from scratch.

---

## 4. NFC UID resolution

**This is fully local-only today — confirmed by reading every relevant file; there is no network call or backend assumption anywhere in this flow.**

The rule (from CLAUDE.md, unchanged and load-bearing): personnel NFC cards and key NFC cards physically share the same UID space with no hardware-level way to tell them apart, so a scanned UID's meaning must always be resolved by lookup, never assumed from context.

How it's actually split across layers:
- **The lookup itself (raw UID → is this enrolled, and to what?) lives entirely in `terminalApp`, not `shared`.** `EncryptedUidEnrollmentStore` (terminalApp) stores raw UIDs Android-Keystore-encrypted (AES-GCM) in local SharedPreferences. Two separate instances exist — one for personnel cards, one for key cards — with separate encryption keys and separate storage files, so a lookup against one can never accidentally match a record in the other.
- **The decision logic (given two already-resolved nullable IDs, what does this scan mean?) lives in `shared`** as `CardUidResolver`, a pure function with zero Android/network dependency. This is a narrower claim than CLAUDE.md's summary sentence ("the lookup logic lives in shared") might suggest at a glance — the *lookup* is Terminal-only by necessity (raw UIDs can't leave the Terminal, per the project's core security boundary); only the *resolution decision* is shared.
- Uniqueness across the two stores is enforced **twice, both client-side, both today**: proactively at enrollment time (enrolling a UID as a personnel card is rejected if it's already a key card, and vice versa) and defensively at resolution time (if a UID somehow exists in both, `CardUidResolver` returns an explicit `Ambiguous` result rather than guessing — surfaced to the operator as an error, never silently resolved).

**What the backend needs to support for this to work end-to-end:** honestly, very little is required of the backend for the *current* implementation, because it doesn't talk to a backend at all yet. But two things are worth flagging for when it does:
1. If/when the backend becomes the source of truth for enrollment records (rather than the Terminal's local encrypted store), it will need to enforce the same UID-uniqueness-across-both-categories rule server-side — the client-side double-guard described above is not something the backend can rely on a client to have honestly performed, per the general principle that clients aren't trusted for security-relevant enforcement (see §3's identical caveat re: `RecycleBinPolicy`).
2. The backend must never be sent a raw UID as part of this flow — only the opaque `fobEnrollmentReference`/enrollment-status types already modeled in `shared`. This is a hard boundary (CLAUDE.md rule, not a suggestion), and the current code already respects it structurally (there's no code path that could leak a raw UID into a shared DTO). Keep it that way when you design the actual enrollment-sync endpoints.

---

## 5. What's explicitly NOT ready

### Mocked/hardcoded backend responses currently in place

- **`webApp`'s entire data layer is one in-memory object, `WebPortalStore`** (in `WebPortalModels.kt`), holding hardcoded seed lists for every workflow area (sites, terminals, personnel, keys, key slots, access grants, event definitions, schedules, user/key groups, multi-auth rules, appointments, appointment reasons, key records, system/equipment logs). Every "save" action is a local list mutation — nothing persists across a page reload, nothing calls a network. Helpfully, most of these mutation functions already set an operator-facing notice string naming the exact production endpoint that action should eventually call (e.g. "Production will call `POST /v1/admin/sites`"), which is a good checklist as you implement each endpoint. Two screens (Personnel and Key Records PDF/Excel export buttons) are pure stubs with no export logic behind them at all — they just show a message saying export is a backend-backed action in production.
- **The web portal's login screen performs zero real authentication** — any non-blank company/account/password combination signs in successfully. This is expected given `AUTH_LOGIN` has no backend yet (§1), but worth stating plainly: there is currently no auth gate of any kind protecting the web portal preview.
- **`terminalApp`'s `TerminalAdminStore`** is a real, working local bootstrap store (Android SharedPreferences + PBKDF2 password hashing) — not mocked in the sense of being fake, but explicitly documented in its own code as a stand-in: "during the backend-sync milestone, the central server becomes authoritative for user, key and credential data." It currently has no awareness that a backend will eventually exist alongside it.
- **`mobileApp`'s companion app** has UI already anticipating backend-delivered data that doesn't exist yet — explicit in-code notices like "Vendor approvals will load from the backend once the API and sync layer are connected" and "Backend data will replace this sample list."
- The Admin Menu's "Set server address" field (Terminal hardware settings, per the supplier manual) is stored and displayed, but **nothing in the codebase currently reads or dials that address** — configuring it today has no effect.

### Endpoints assumed but not confirmed against real vendor Management Platform behavior

As established in §1, this codebase does not integrate with the vendor's Management Platform API at all — so there is nothing to "confirm" there in the sense of checking assumptions against real vendor behavior, because no vendor-platform assumptions exist in the code. The real open question is the one flagged in §1: **whether that vendor API is relevant to this project's backend at all.** Get that answered before scoping backend work around it.

Separately, within the project's *own* intended contract (`API_HANDOVER_SUPER_ADMIN V4.md`), a few things are documented as spec but have no client-side usage anywhere yet to validate the shape against real behavior — most notably the entire Vendor Approval flow (§1) and the audit-event ingestion endpoint. These aren't "vendor platform" risk, just "designed on paper, never exercised against a running client" risk — expect some shape adjustment once you wire a real client against them.

### Personnel management gap (already known, detailed here for backend field planning)

Both client UIs collect meaningfully less than `AdminUser` needs, and in incompatible ways:

| `AdminUser` field | terminalApp `EnrollUserScreen` | webApp `PersonnelScreen` |
|---|---|---|
| `email` | Not collected at all | Not collected at all |
| `role` (full `UserRole` enum) | Binary Technician/Vendor toggle only; no Super Admin option; uses its own separate `TerminalUserRole` type | Not collected at all — the closest field, "Multi-authentication personnel group," is an unrelated free-text grouping concept |
| `assignedSiteIds` (validated set) | Not collected — terminalApp has no site concept | A single free-text "Affiliated unit" field, fuzzily matched to a site by name, not a validated multi-select |
| `accountStatus` | No enable/disable concept at all | Set automatically to `PENDING_APPROVAL` on creation; no UI exists to change it afterward |
| `lifecycle` (soft-delete) | Not tracked (only a creation timestamp) | Not tracked (uses a separate ad hoc wrapper for its own Recycle Bin, not `LifecycleMetadata`) |

Practically: when you build the real `/v1/admin/users` endpoints, expect the first UI work needed on the client side to be a genuine field-collection rebuild on both webApp and terminalApp, not just wiring existing forms to a real endpoint. This was already the project's own next-planned step before this handover, independent of anything backend-specific.

### Other loose ends worth a quick look, low priority

- `terminalApp/src/main/java/com/ekms/terminal/ui/SiteTerminalAdminScreen.kt` and its companion `CabinetGateway.kt` interface are dead code — defined but never composed into any navigable screen in the running app. They model an earlier Sites/Terminals concept for the Terminal app that isn't part of the current architecture (Site/Terminal management lives in webApp only today). Safe to ignore or delete; not a backend concern, just noise if you go searching the terminalApp source for how it currently manages sites.
- There's a separate archived scaffold (`terminalApp/reference/*.reference.kt.bak`) already flagged in CLAUDE.md as intentionally not merged — ignore it, it's reference-only.

---

## Suggested first steps

1. **Get the vendor-API question (§1) answered** before writing any endpoint code — it changes whether "backend" means one system or two.
2. **Design authentication first.** `AUTH_LOGIN`/`AUTH_REFRESH` have zero DTOs and nothing downstream can be built safely without knowing the token/session shape.
3. **Pin down the `payloadJson`/`changesJson` schema** (§3) with the project owner before building sync — this is the one place where "the contract" genuinely doesn't exist yet, not just unimplemented.
4. **Decide the schema-fragmentation strategy** (§2) — canonical backend models vs. three/two incompatible client-local schemas — since it affects how every other endpoint's request/response shape should look.
5. Everything else in `ApiContracts.kt` (Sites, Terminals, Keys, Key Slots, Access Grants, Recycle Bin list) is reasonably well-specified and is probably the fastest path to a visibly working vertical slice once auth exists.
