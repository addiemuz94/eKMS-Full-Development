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
| User | `/v1/admin/users` | displayName, email, role, assignedSiteIds, accountStatus |
| Site | `/v1/admin/sites` | name, address |
| Terminal | `/v1/admin/terminals` | siteId, name, boxAddress, serialNumber |
| Key | `/v1/admin/keys` | siteId, displayName, keyFobUid |
| Key slot | `/v1/admin/key-slots` | terminalId, nodeAddress, managedKeyId |
| Access grant | `/v1/admin/access-grants` | userId, siteId, exact keyIds, validity period |
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

### API HANDOVER — Users & Credentials (implemented client flow, backend pending)

The Website and protected Terminal Super Admin pages both support the following user-management flow:

1. Create a `SUPER_ADMIN`, `TECHNICIAN`, or `VENDOR` user.
2. Assign one or more sites. `TECHNICIAN` and `VENDOR` must have at least one assigned site; a `SUPER_ADMIN` may have global scope.
3. Edit name, email, role, and assigned sites using revision-safe updates.
4. Disable or enable an account without deleting its history.
5. Move a user to the Recycle Bin, restore it, or permanently purge it from the Super Admin Bin.
6. Request protected credential enrolment. The Website creates a workflow request; the Terminal performs any physical NFC or fingerprint capture.

| Endpoint | Required behavior |
|---|---|
| `GET /v1/admin/users?state=ACTIVE&siteId=&cursor=` | List active users, their `accountStatus`, assigned sites, credential summary and record revision. |
| `POST /v1/admin/users` | Create a user from `displayName`, `email`, `role`, and `assignedSiteIds`; default `accountStatus` to `ACTIVE`. |
| `GET /v1/admin/users/{userId}` | Return full editable user profile, safe credential status summary, revision, and allowed actions. |
| `PATCH /v1/admin/users/{userId}` | Update profile/role/site scope only when `expectedRevision` matches. Return `409 CONFLICT` rather than overwriting a newer record. |
| `POST /v1/admin/users/{userId}/account-status` | Set `ACTIVE` or `DISABLED`; invalidates active login/session tokens when disabling. |
| `DELETE /v1/admin/users/{userId}` | Soft-delete the user to Recycle Bin; return the `RecycleBinEntry`. Never erase audit records. |
| `GET /v1/admin/users/{userId}/credentials` | Return safe metadata only: credential kind, status, last changed time and enrolment job ID. Do not return UID, passkey, face image, or biometric template. |
| `POST /v1/admin/users/{userId}/credential-enrolment-requests` | Create an NFC/fingerprint enrolment request for an assigned Terminal. Returns the time-bounded job ID that the Terminal can download through sync. |
| `POST /v1/admin/users/{userId}/credentials/{credentialId}/revoke` | Revoke a credential and send an immediate revocation change to all relevant Terminals. |

Create request:

```json
{
  "displayName": "Technician Example",
  "email": "technician@example.com",
  "role": "TECHNICIAN",
  "assignedSiteIds": ["site_01H..."]
}
```

Revision-safe update request:

```json
{
  "displayName": "Technician Example",
  "email": "technician@example.com",
  "role": "TECHNICIAN",
  "assignedSiteIds": ["site_01H...", "site_01J..."],
  "expectedRevision": 12
}
```

Account-status request:

```json
{
  "accountStatus": "DISABLED",
  "expectedRevision": 12
}
```

Credential-enrolment request:

```json
{
  "credentialKind": "FINGERPRINT",
  "terminalId": "terminal_01H...",
  "expectedRevision": 13
}
```

Credential-security rules:

- Browser and Mobile must never receive raw fingerprint templates, face templates/images, passkeys, or unencrypted NFC UID values.
- The Terminal must receive only the assigned enrolment job and must bind the physical capture result locally before syncing a safe completion status.
- Credential replacement revokes the previous binding before the replacement becomes active.
- Disabling or recycling a user must deny new access immediately on all online Terminals and be placed first in the offline sync outbox for other Terminals.

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
- A true phone NFC Digital Key is deferred pending an APDU-capable terminal reader.
- The current Static UID Digital Key prototype uses the tested physical NFC tag attached to a Technician/Super Admin phone and maps that UID to a logical DigitalKeyId.
- Cabinet serial command transport remains Android Terminal-only and never belongs in this web/mobile API contract.