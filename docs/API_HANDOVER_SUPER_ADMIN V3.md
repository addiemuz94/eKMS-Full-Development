# eKMS — API HANDOVER: Super Admin Foundation

Status: contract draft for the backend developer. The Android and web clients use these names and rules; the backend must own authentication, data persistence, access decisions, and audit integrity.

## 1. Global contract

- Base path: `/v1`
- Format: JSON, UTF-8.
- IDs: backend-generated UUIDs or ULIDs. Clients must never use display names as identifiers.
- Times: UTC Unix epoch milliseconds in JSON fields ending in `EpochMillis`.
- Every mutating request requires `Authorization: Bearer <access-token>` and an `Idempotency-Key` header.
- Every mutable record has a monotonic `revision` field.
- Never silently overwrite a different revision received from an offline Terminal.
- Record every security or administrative mutation as an immutable audit event.

## 2. Authentication and authorization

### `POST /v1/auth/login`

Request:

```json
{
  "identifier": "admin@example.com",
  "password": "user-entered-secret",
  "clientType": "WEB",
  "deviceId": "stable-app-installation-id"
}
```

Response: access token, refresh token, authenticated user profile, role, token expiry, and permitted site IDs.

Backend rules:

- Only `SUPER_ADMIN` can call full administration, Recycle Bin, and conflict-resolution APIs.
- Terminal hardware enrolment still requires Super Admin authorization from the terminal client.
- A credential replacement must revoke the old phone/tag binding before activating the replacement.

## 3. Super Admin master data

These entities have full create/read/update/soft-delete behaviour on Website and Terminal Admin Page:

| Entity | Collection endpoint | Required core fields |
|---|---|---|
| User | `/v1/admin/users` | displayName, email, role, assignedSiteIds |
| Site | `/v1/admin/sites` | name, address |
| Terminal | `/v1/admin/terminals` | siteId, name, boxAddress, serialNumber, configuredSlotCount, cabinetSerialPort, cabinetBaudRate |
| Key | /v1/admin/keys | siteId, displayName, fobEnrollmentReference |
| Key slot | /v1/admin/key-slots | terminalId, nodeAddress, managedKeyId |
| Access grant | /v1/admin/access-grants | userId, siteId, exact keyIds, validity period |
| Credential binding | `/v1/admin/credentials` | userId, credential kind, encrypted reference, active |

Required operations for each collection:

```text
GET    /v1/admin/{entity}?siteId=&state=ACTIVE&page=
POST   /v1/admin/{entity}
GET    /v1/admin/{entity}/{id}
PATCH  /v1/admin/{entity}/{id}       with expectedRevision
DELETE /v1/admin/{entity}/{id}       soft delete only
```

`DELETE` does not erase the record. It moves it to the Recycle Bin and creates an audit event.

### 3.1 API HANDOVER — Sites and Terminals (Step 3)

Website and Terminal both expose the same Super Admin CRUD. The backend is the
source of truth; an offline Terminal sends a revision-aware change through the
existing sync outbox rather than overwriting a newer Website edit.

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/sites?state=ACTIVE` | List active sites, paged and filterable. |
| `POST /v1/admin/sites` | Create a site. |
| `GET /v1/admin/sites/{siteId}` | Read one site with its revision. |
| `PATCH /v1/admin/sites/{siteId}` | Update a site using `expectedRevision`. |
| `DELETE /v1/admin/sites/{siteId}` | Preflight and soft-delete only; never cascade silently. |
| `GET /v1/admin/sites/{siteId}/terminals` | List active terminals assigned to the site. |
| `GET /v1/admin/terminals?siteId=&state=ACTIVE` | List terminals. |
| `POST /v1/admin/terminals` | Create a terminal and cabinet configuration record. |
| `GET /v1/admin/terminals/{terminalId}` | Read one terminal with its revision. |
| `PATCH /v1/admin/terminals/{terminalId}` | Update terminal identity/configuration using `expectedRevision`. |
| `DELETE /v1/admin/terminals/{terminalId}` | Soft-delete only. |
| `GET /v1/admin/terminals/{terminalId}/status` | Read online/offline/sync state for Web and Mobile monitoring. |

Create or update a site:

```json
{
  "name": "Kuala Lumpur HQ",
  "address": "Kuala Lumpur",
  "expectedRevision": 12
}
```

Create or update a terminal:

```json
{
  "siteId": "site_01H...",
  "name": "HQ Main Cabinet",
  "boxAddress": 1,
  "serialNumber": "F7G18P-KL-001",
  "configuredSlotCount": 48,
  "cabinetSerialPort": "/dev/ttyS1",
  "cabinetBaudRate": 19200,
  "expectedRevision": 8
}
```

Required backend validation:

- `boxAddress` and `configuredSlotCount` must be integers from `1` to `255`.
- A key slot's `nodeAddress` must remain within `1..configuredSlotCount`; there
  is no client-side hidden `-1` address conversion.
- A terminal belongs to exactly one active site.
- Do not require global uniqueness of `boxAddress`: it is a physical cabinet
  address, not a cloud-wide terminal identifier.
- `cabinetSerialPort` and `cabinetBaudRate` are configuration metadata only.
  Web and Mobile must never receive permission to open a terminal serial port.
- Deleting a site with active terminals, keys or grants returns a preflight
  validation error with dependent counts. The caller must first archive the
  dependencies or request an explicit, audited cascade policy later.

Hardware boundary:

- Saving a terminal configuration must never issue a cabinet command.
- Only the Android Terminal's hardware layer may later open `/dev/ttyS1` and
  use the saved Box Address for an explicit, authorized operation.
- Hardware connection result and cabinet status are separate from the static
  configuration record and must be returned through the status endpoint.

### 3.2 API HANDOVER — Keys, cabinet slots and access grants (Step 4)

Website and Terminal both manage key records, physical cabinet-slot mappings
and exact grants. Mobile is read-only. The backend is authoritative, and an
offline Terminal must queue revision-aware edits instead of overwriting a newer
Website edit.

| Endpoint | Purpose |
|---|---|
| GET /v1/admin/keys?siteId=&state=ACTIVE | List active key records. Never return a raw fob UID. |
| POST /v1/admin/keys | Create a logical key record. |
| GET /v1/admin/keys/{keyId} | Read one key record and revision. |
| PATCH /v1/admin/keys/{keyId} | Update name or site using expectedRevision. |
| POST /v1/admin/keys/{keyId}/fob-enrollment | Android Terminal-only protected fob enrollment; receives a raw UID only over authenticated TLS and returns an opaque enrollment reference. |
| DELETE /v1/admin/keys/{keyId} | Preflight and soft-delete only. |
| GET /v1/admin/key-slots?terminalId=&state=ACTIVE | List registered physical slot mappings. |
| POST /v1/admin/key-slots | Register a key-node mapping or an intentionally empty slot. |
| PATCH /v1/admin/key-slots/{slotId} | Update a slot using expectedRevision. |
| DELETE /v1/admin/key-slots/{slotId} | Soft-delete a mapping. |
| GET /v1/admin/terminals/{terminalId}/slot-availability | Return configured capacity, assigned nodes and last hardware report. |
| GET /v1/admin/access-grants?userId=&siteId=&state=ACTIVE | List exact grants. |
| POST /v1/admin/access-grants | Create a user-to-exact-key grant. |
| PATCH /v1/admin/access-grants/{grantId} | Update key list or validity period using expectedRevision. |
| DELETE /v1/admin/access-grants/{grantId} | Soft-delete a grant. |

Key request fields: siteId, displayName, optional fobEnrollmentReference, and
optional expectedRevision. The reference is opaque and cannot be reversed into
a UID by Website, Mobile or ordinary API responses.

Key-slot request fields: terminalId, nodeAddress, nullable managedKeyId, and
optional expectedRevision. A nullable managedKeyId explicitly represents a
registered physical slot that is currently empty.

Access-grant request fields: userId, siteId, exact keyIds, optional
validFromEpochMillis, optional validUntilEpochMillis and expectedRevision.

Required backend validation:

- A key record belongs to exactly one active site.
- A real fob UID is accepted only by the protected Android Terminal enrollment
  endpoint. Store an encrypted value or keyed verifier; audit enrollment,
  replacement and revocation without logging the UID.
- A key-node address must be an integer in 1..configuredSlotCount. Node 0 is
  the cabinet-door node and is never a key slot.
- A terminal may not contain two active mappings for the same node address.
- One active physical key may belong to only one active slot at a time.
- The selected key and selected terminal must belong to the same active site.
- A grant must contain at least one active key, and every selected key must
  belong to the grant site.
- A Technician or Vendor may receive a grant only at a site assigned to that
  user. Super Admin access follows the server's separate policy.
- A disabled, recycled or purged user cannot hold an active grant.
- validUntilEpochMillis must not be earlier than validFromEpochMillis. At
  authorization time, terminals must deny expired grants even when offline.
- Soft-deleting a key is blocked while it has active slot mappings or active
  grants. Return a dependency preflight response; do not cascade silently.

Hardware and authorization boundary:

- Saving a key, slot or grant must never send a cabinet command.
- The terminal is allowed to use the configuration only after login,
  authorization and local cache validation have all succeeded.
- The server must supply active users, keys, slot mappings and grants in the
  terminal bootstrap cache with revisions and expiry. It must also receive
  TAKE, RETURN, failed authorization and configuration audit events through
  the sync outbox.

## 4. API HANDOVER — Recycle Bin (confirmed requirement)

Only Super Admin may access this feature.

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/recycle-bin` | List deleted records that can still be restored. Filter by `recordType`, `siteId`, or `deletedBy`. |
| `GET /v1/admin/recycle-bin/{entryId}` | View deletion metadata and restorable record data. |
| `POST /v1/admin/recycle-bin/{entryId}/restore` | Restore the exact record, increment revision, and audit it. |
| `DELETE /v1/admin/recycle-bin/{entryId}` | Permanently purge one record before expiry. Super Admin only. |
| `POST /v1/admin/recycle-bin/purge-expired` | Server-side scheduled purge of entries older than the retention limit. Never callable by non-Super-Admin clients. |

When a record is soft-deleted, the backend must return:

```json
{
  "id": "bin_01H...",
  "recordType": "KEY",
  "recordId": "key_01H...",
  "recordLabel": "Forklift Key",
  "deletedByUserId": "usr_01H...",
  "deletedAtEpochMillis": 1780000000000,
  "expiresAtEpochMillis": 1785184000000,
  "restorePayloadVersion": 7
}
```

Policy:

- Default retention is **60 days** (the operational implementation of up to two months).
- Super Admin may restore during the retention period.
- Super Admin may permanently clear an item earlier.
- The server must automatically purge expired entries.
- Purging removes the live record payload but never deletes immutable historical audit events. Audits retain a safe snapshot of the record label and ID.
- A key, site, or user with live dependencies must be blocked from deletion or require an explicit cascade decision returned by the backend as a validation error. No invisible cascade delete.

## 5. API HANDOVER — Offline Terminal sync and conflict review

### Download current configuration

`POST /v1/terminal/sync/bootstrap`

```json
{
  "terminalId": "terminal_01H...",
  "lastSuccessfulSyncEpochMillis": 1770000000000,
  "localRevision": 41
}
```

Response includes the authoritative configuration changes, user/credential/access cache, required revisions, and server time.

### Upload terminal changes and audit outbox

`POST /v1/terminal/sync/push`

```json
{
  "terminalId": "terminal_01H...",
  "changes": [
    {
      "operationId": "op_01H...",
      "entityType": "KEY_SLOT",
      "entityId": "slot_01H...",
      "baseRevision": 41,
      "submittedAtEpochMillis": 1780000000000,
      "submittedByUserId": "usr_01H...",
      "payloadJson": "{...}"
    }
  ],
  "auditEvents": []
}
```

Response must separately list accepted operations and conflicts. A conflict means the backend preserves both versions and creates a `SyncConflict` record.

### Super Admin conflict resolution

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/sync-conflicts?status=OPEN` | List all unresolved conflicts. |
| `GET /v1/admin/sync-conflicts/{id}` | Show server version, terminal edit, timestamps and audit context. |
| `POST /v1/admin/sync-conflicts/{id}/resolve` | Super Admin chooses `KEEP_SERVER`, `KEEP_TERMINAL_CHANGE`, or `MERGE_MANUALLY`. |

Resolution request:

```json
{
  "strategy": "MERGE_MANUALLY",
  "mergedPayload": { "only": "when strategy is MERGE_MANUALLY" },
  "expectedConflictRevision": 3
}
```

## 6. API HANDOVER — Vendor approval (next Super Admin feature)

The Super Admin must select exact keys—not only a site or group.

| Endpoint | Purpose |
|---|---|
| `POST /v1/vendor/registrations` | Vendor self-registration pending Super Admin approval. |
| `POST /v1/vendor/requests` | Request: vendor name, company, reason, person in charge, site, date/time. |
| `GET /v1/admin/vendor-requests?status=PENDING` | Super Admin review queue. |
| `POST /v1/admin/vendor-requests/{id}/approve` | Select exact `keyIds`, terminal/site, start/end and create temporary pass. |
| `POST /v1/admin/vendor-requests/{id}/reject` | Reject with reason. |

Approval rules:

- Generate a unique six-digit passkey per approved request.
- Store a slow password hash/verifier, never plaintext. Display the readable code only once to the issuer/requester through a secure workflow.
- The passkey is reusable until its exact expiry and limited to the approved site, terminal, and exact key IDs.
- Terminal cache must retain a verifier and hard expiry, so vendor passes expire locally even offline.
- Rate-limit failed passkey attempts and emit `LOGIN_DENIED` audit events.

## 7. API HANDOVER — Audit records

`POST /v1/audit/events` accepts terminal outbox events; `GET /v1/audit/events` supports Super Admin filtering.

Minimum audit event types: login success/denial, key take, key return, credential replacement, user/site/key change, bin move, bin restore, permanent purge, conflict create/resolve, vendor approval, and failed passkey attempt.

Audit records are append-only. A later correction must create a new event; do not edit a previous security event.

## 8. Explicitly not in this contract yet

- Camera/face-recognition authorization is not enabled.
- Physical fob scanning and key-node verification are not enabled by the Step 4
  administration UI. They require the protected Terminal enrollment workflow.
- A true phone NFC Digital Key is deferred pending an APDU-capable terminal reader.
- The current Static UID Digital Key prototype uses the tested physical NFC tag attached to a Technician/Super Admin phone and maps that UID to a logical DigitalKeyId.
- Cabinet serial command transport remains Android Terminal-only and never belongs in this web/mobile API contract.
