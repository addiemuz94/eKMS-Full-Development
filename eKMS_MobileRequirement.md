# eKMS Mobile App — Feature Requirements

**Audience:** Mobile dev team  
**Scope:** `mobileApp` (Android companion app)  
**Status:** Decisions locked — ready to build (Only B). See **Decisions (locked)** below.

**Ownership (flexible — either may cover the other when timing requires):**

| Tag | Who | Focus |
|---|---|---|
| **Haikal's part** | Haikal (remote) | Docs, backend, portal, `mobileApp`, deploy, phone/emulator tests |
| **Adi's part** | Adi (office) | Physical F7G18P verification (pairing, Key Attachment, take/return, passkey on terminal) |

---

## 1. Target Users

The app is used by all four roles: **Super Admin, Regional Admin, Technician, and Vendor.**

---

## 2. Main Dashboard

- The summary shown must be **scoped to locations the logged-in user is permitted to access** — no data from locations outside their access should be shown.
- **Map view:**
  - Only pins for **permitted locations** are shown; all other cabinet/terminal locations are hidden from the map entirely.
  - Tapping a location pin lets the user get directions — this should hand off to the device's native maps app (Google Maps or equivalent), prompting the standard "open in Maps app" / location-permission flow, same as any GPS-directions handoff.

---

## 3. Key Access — Core Purpose of the App

This is the app's primary function, used mainly by **Technician** and **Vendor** roles. There is a dedicated **"Apply Key Access"** section.

**Access model: Only B (exception access).** The requester asks for temporary access to a **location outside their normally-permitted (standing) site assignments** — not a PIN for keys they already have standing grants for. The earlier grant-scoped duration-slider passkey UI in `mobileApp` is **superseded** and must be replaced to match this model.

### 3.1 Technician Flow

1. Technician taps **Apply**.
2. The form **auto-fills**: name and email from the signed-in profile.
3. Technician manually selects:
   - A **location outside their normally-permitted locations** (exception access — not locations they already have standing assignment to)
   - The **specific key** they want to use at that location
   - **Date and time** for both key pickup (use) and return (calendar pickers)
   - A **reason** for the request
4. On submit, the request routes to the **Regional Admin responsible for the selected location** (via that site's Region) for approval.
5. That Regional Admin receives a **push notification** about the pending request (FCM — new work; see Decisions).
6. On approval, the system issues a **4-digit PIN** to the requesting Technician, valid until the approved **return** datetime, to be entered at the terminal.

### 3.2 Vendor Flow

Same general shape as Technician, with additional requirements:

- Vendor must submit supporting documents as part of the request:
  - Work Permit
  - NIOSH card
  - IC (identity card)
  - Accepted as PDF or other common file formats
  - **Max 5 MB per file**, stored **in the MySQL database** (`MEDIUMBLOB`) — see Decisions
- Vendor must select a **Person in Charge (PIC)** from a list — this list should only show **Technicians assigned to the selected location** (not all technicians system-wide).
- **Approval is sequential, in two stages:**
  1. Person in Charge approves first
  2. Regional Admin approves second
- **If PIC rejects:** the request **still forwards to Regional Admin** with the rejection noted (RA may still approve or reject).
- Once Regional Admin approval is complete, the Vendor receives the same **4-digit PIN** mechanism as Technician, valid until the approved return datetime, to be entered at the terminal.

---

## Decisions (locked)

| # | Topic | Decision |
|---|---|---|
| 1 | Push notifications | **New work** (FCM). Not an extension of an existing feature. Notify Regional Admin (Technician + Vendor requests) and PIC (Vendor requests); notify requester on approve/reject. |
| 2 | Vendor document upload | **Max 5 MB per file.** Stored **in MySQL** (`MEDIUMBLOB`), not object storage. |
| 3 | PIC rejection | **Option B** — still forward to Regional Admin with rejection noted. |
| 4 | Date/time window | **Calendar** pickup + return. **Allow any range for now.** Do **not** enforce the 24h / region `max_key_access_duration_minutes` ceiling as a product rule for this flow. |
| 5 | Access model | **Only B — exception access.** Location outside standing permitted sites; not re-requesting keys at sites the user already has standing access to. |

---

## Build phases (summary)

| Phase | Owner | Work |
|---|---|---|
| 0 | Haikal | This requirements + `CLAUDE_MOBILE.md` decision capture (done when this section is present) |
| 1 | Haikal | Technician Only-B Apply (backend + mobile); Adi verifies PIN→terminal take later |
| 2 | Haikal | Vendor docs + PIC + two-stage approval |
| 3 | Haikal | FCM push + Alerts list |
| 4 | Haikal | Dashboard map + directions. (Terminals list + Overview counts already live.) |
| 5 | **Adi** | Office F7G18P checklist (pairing, Key Attachment, passkey→take, Return Flow, etc.) |

---

*Prepared from internal requirements notes. Decisions locked Jul 2026.*
