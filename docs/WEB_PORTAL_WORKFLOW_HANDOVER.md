# eKMS Website Workflow Handover

**Status:** Website workflow/UI implementation, 22 July 2026

**Audience:** Backend developer, Website developer, Solution Architect

**Supplier reference:** *Smart Key Management System Software Platform User Manual V2.0*
**Source:** `webApp/src/wasmJsMain/kotlin/com/ekms/web/`

## Purpose and scope

The eKMS Website now follows the supplier Web manual's functional workflow while retaining the eKMS blue/teal visual theme and the project security rules.

The current Website source is an interactive **local workflow preview**. Its sample records prove navigation, screen behaviour, and data needed by each workflow; they are not a production database. The backend must replace these lists with authenticated, revision-aware API calls.

## Non-negotiable boundaries

1. The Website manages configuration, users, permissions, approvals, reports and synchronisation requests.
2. Only the signed-in Android Terminal performs cabinet serial I/O, key-slot actions, reader access and physical proof collection.
3. The Website must never open `/dev/ttyS1`, `/dev/ttyS2`, send serial frames, or present a raw fob UID.
4. A Website key record may show only `fobEnrollmentState` and an opaque `fobEnrollmentReference`.
5. Super Admin is the only preset identity. Its initial password is a deployment secret; never hard-code it in Website source, Git, logs or an API response.
6. Every data mutation uses an access token, `Idempotency-Key`, `expectedRevision`, and an immutable audit event.
7. Delete is soft-delete to the Super Admin-only Recycle Bin for **60 days**. Active dependencies block a hidden cascade delete. Historic audits remain after purge.
8. A Terminal offline change never silently overwrites a newer Website change. Conflicts require Super Admin review.

## Supplier-manual workflow mapping

| Supplier workflow | eKMS Website page | Required server behaviour |
|---|---|---|
| Login | Login | `POST /v1/auth/login`; resolve company/account/password server-side; return role, site scope, session and `mustChangePassword` where required. |
| Home Page | Dashboard | Summary: available/taken keys, online terminal count, pending approvals, terminal sync/status. No direct hardware action. |
| Unit Settings | Unit Settings | CRUD `/v1/admin/sites`; support name, province/state, city, superior unit, revision and lifecycle. |
| Terminal Settings | Terminal Settings | CRUD `/v1/admin/terminals`; terminal name, site/unit, Android device UID, node layout A/B/C, node count, return-authentication flag, status/sync metadata. |
| Personnel Management | Personnel Management | CRUD `/v1/admin/users`; filter by unit/person; support employee ID, personnel group, schedule, credentials status and export. Password is write-only. |
| Key Setting | Key Settings | CRUD `/v1/admin/keys`; unit, terminal, name, physical location/node, time limit and key group. Never return raw UID. |
| Permission Settings | Permission Settings | CRUD `/v1/admin/access-grants`; bind exact key IDs to a user and validity window. Site-wide permission alone is invalid. |
| Event Setup | Event Setup | CRUD `/v1/admin/event-definitions`; unit, name, code/number and type/requirement. |
| Schedule Settings | Schedule Settings | CRUD `/v1/admin/schedules`; daily/weekly/monthly frequency and validated time window. |
| Multi-authentication Rules | Multi-authentication Rules | CRUD `/v1/admin/multi-authentication-rules`; primary personnel group, assistant groups and key group. |
| User Group / Key Group | User Groups / Key Groups | CRUD `/v1/admin/personnel-groups` and `/v1/admin/key-groups`; name, group number and unit. |
| Data Synchronization | Data Synchronization | Select terminal and issue `READ_FROM_TERMINAL` or `DOWNLOAD_TO_TERMINAL` request. Backend coordinates terminal sync, revision validation, audit and conflict creation. |
| Report Data: pickup/return | Pickup & Return Records | Query `/v1/reports/key-operations` by date, terminal, key and person. Mark take/return only after Terminal physical proof. |
| Report Data: operation log | Operation Log | Query safe, append-only system and equipment logs. Export through authenticated backend-generated PDF/Excel jobs. |
| Appointment Authorization | Appointment Authorization | Create appointment for exact unit, terminal, key(s), person, date/time and reason. Reviewer approves/rejects with audit. |
| Appointment Reason Setting | Appointment Reason Settings | CRUD `/v1/admin/appointment-reasons`; only active reasons appear in appointment creation. |
| Appointment Permission Settings | Appointment Permission Settings | Manage exact key IDs allowed by the selected appointment. Backend derives time-bounded authorization after approval. |
| System Operation Log | System Operation Log | `/v1/reports/system-operation-logs`, filtered by unit/actor/time. |
| Equipment Operation Log | Equipment Operation Log | `/v1/reports/equipment-operation-logs`, filtered by terminal/node/time; safe outcomes only, never raw serial frames. |

## Terminal configuration requirements

Terminal registration must include the supplier workflow fields below. The Website saves configuration only; it does not exercise cabinet hardware.

| Field | Rule |
|---|---|
| Terminal/cabinet name | Human-readable name unique within the owning unit/site. |
| Unit/site | One active terminal belongs to one active unit/site. |
| Type | Current deployment is Android Terminal. |
| Device UID | Must match the physical F7G18P terminal identity at provisioning/sync. |
| Super Admin password | Deployment/server-side credential provisioning only. Never return plaintext. |
| Key return authentication | Boolean policy included in the terminal cache. |
| Node layout | `A` standard key slot; `B` compact door with node key slot; `C` compact door without node key slot. |
| Row/node counts | Backend validates against cabinet capability. Key node addresses are canonical physical values; never silently subtract or add one. |

## Required API contracts

The shared source of API path and DTO names is:

`shared/src/commonMain/kotlin/com/ekms/shared/api/ApiContracts.kt`

### Collections

```text
/v1/admin/users
/v1/admin/sites
/v1/admin/terminals
/v1/admin/keys
/v1/admin/key-slots
/v1/admin/access-grants
/v1/admin/event-definitions
/v1/admin/schedules
/v1/admin/personnel-groups
/v1/admin/key-groups
/v1/admin/multi-authentication-rules
/v1/admin/appointments
/v1/admin/appointment-reasons
/v1/admin/appointment-permissions
```

Each collection supports paged/filterable `GET`, `POST`, record `GET`, revision-safe `PATCH`, and soft-delete `DELETE` unless a transition endpoint is more appropriate.

### Data synchronization

```text
POST /v1/terminal/sync/read
POST /v1/terminal/sync/download
POST /v1/terminal/sync/bootstrap
POST /v1/terminal/sync/push
GET  /v1/admin/sync-conflicts
POST /v1/admin/sync-conflicts/{id}/resolve
```

`TerminalDataSynchronizationRequest` contains:

```json
{
  "terminalId": "terminal_01H...",
  "direction": "READ_FROM_TERMINAL",
  "sections": ["PERSONNEL", "KEYS_AND_SLOTS", "ACCESS_PERMISSIONS"],
  "expectedTerminalRevision": 17
}
```

The backend may queue the request, but it must not claim a physical configuration or operation completed until the authenticated Terminal reports its result.

### Appointment review

```text
POST /v1/admin/appointments
POST /v1/admin/appointments/{id}/review
PATCH /v1/admin/appointment-permissions/{id}
```

Approval must validate all of the following before creating an authorization decision:

- user is active and site-assigned;
- selected terminal and all selected keys belong to the same active site;
- requested time window is valid in UTC;
- each selected key is active and not otherwise blocked;
- any schedule/multi-authentication policy is satisfied;
- reviewer has the required Super Admin permission.

### Reports and logs

```text
GET /v1/reports/key-operations
GET /v1/reports/system-operation-logs
GET /v1/reports/equipment-operation-logs
POST /v1/reports/exports
```

Report filters support `siteId`, `terminalId`, `userId`, `keyId`, `fromEpochMillis`, and `untilEpochMillis` through `ReportFilterRequest`.

## Data and privacy rules

| Data | Website behaviour |
|---|---|
| Passwords | Write-only over TLS; client does not persist it and server stores a slow hash/verifier. Never render or log. |
| NFC/fob UID | Never show or accept in Website pages. Terminal-only protected endpoint/value. |
| Fingerprint / face templates | Never expose to Website. Status/reference only. |
| Digital Key | No raw credential material in Website responses. Show activation/status only. |
| Hardware command/frame | Website never receives or creates a raw serial command. |
| Audit detail | Safe business outcome and correlation IDs only; append-only. |

## Current UI implementation status

Implemented in `webApp`:

- responsive login and Super Admin portal shell;
- all supplier-manual navigation sections listed above;
- local interactive create/selection/permission/appointment/review/recycle-bin flows;
- fob UID privacy and Terminal-only hardware warnings throughout applicable screens;
- shared API paths and DTOs for the Website workflow;
- updated domain model to remove the shared raw `keyFobUid` field in favour of opaque enrolment state/reference.

Still required for production:

1. REST client, token refresh, server error handling and persistent browser session policy.
2. Real pagination, filter parameters, PDF/Excel export jobs and download handling.
3. Server data persistence, role enforcement, idempotency and revision checks.
4. Terminal secure sync/outbox, WebSocket or polling delivery and conflict resolution persistence.
5. Full end-to-end validation against the F7G18P Terminal after the current Terminal source is merged.

## Acceptance checklist

- [ ] Website account cannot access a site, terminal or key outside its backend scope.
- [ ] Website cannot read raw fob UID, biometric template or Digital Key secret.
- [ ] Unit/terminal/user/key updates carry `expectedRevision`.
- [ ] A concurrent/offline Terminal edit creates a Super Admin conflict instead of overwriting Website data.
- [ ] Delete moves data to a 60-day Recycle Bin and dependency preflight blocks hidden cascades.
- [ ] Data Sync Read/Download requests are authenticated, auditable and terminal-confirmed.
- [ ] Appointment approval grants only exact keys, only for the approved terminal/site/window.
- [ ] Key pickup/return reports change state only after Terminal physical proof.
- [ ] Website never sends physical cabinet control commands.
