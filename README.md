# eKMS — Kotlin Multiplatform Foundation

This is a fresh production-oriented eKMS build. The supplied `ekmshardwaretester-main` project is reference material only; it is not reused as the production app.

## Modules

| Module | Purpose |
|---|---|
| `shared` | Cross-platform domain models, access policies, soft-delete/Recycling Bin rules, sync conflict DTOs, API contract names. |
| `terminalApp` | Android application for the F7G18P terminal. Cabinet serial, NFC UID reader, fingerprint and camera integrations remain Android-only. |
| `mobileApp` | Android-first Super Admin companion and later Technician/Vendor app. |
| `webApp` | Kotlin/Wasm Compose Super Admin web portal. |
| `docs` | Backend handover documents. |

## Confirmed policies in Step 1

- Website and Terminal both provide full Super Admin user/key/site editing.
- Website/backend remains the source of truth; an offline Terminal queues changes.
- Conflicting offline edits require Super Admin review. The system never silently overwrites either version.
- Delete moves data to a Super Admin-only Recycle Bin for 60 days, or until a Super Admin clears it sooner.
- Historic audit events are retained after a record is permanently purged.
- Vendor passkeys are reusable until their approved expiry, but limited to approved terminal, site and exact keys.

## Open in Android Studio

1. Install a current Android Studio with the **Kotlin Multiplatform** plugin.
2. Open the `ekms-platform` folder as a Gradle project.
3. In **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**, select **JDK 17** as the Gradle JDK.
4. Select **Gradle 8.13** as the Gradle distribution. Do not use Gradle 9.x with this project baseline.
5. Sync the project, then run `terminalApp` or `mobileApp` on an Android emulator/device.
6. Run `webApp [wasmJs]` to open the Super Admin web screen in a browser.

The baseline toolchain is: JDK 17, Gradle 8.13, Android Gradle Plugin 8.11.1,
Kotlin 2.2.20, and `compileSdk = 36`. The Android modules explicitly compile
both Java and Kotlin to JVM 17 so their bytecode targets cannot drift apart.

The web target is Kotlin/Wasm. It is suitable for the shared Super Admin UI foundation; browser-specific integration should remain isolated in `webApp`.

## Backend handover

Give the backend developer [BACKEND_DEVELOPER_HANDOVER.md](docs/BACKEND_DEVELOPER_HANDOVER.md) first. It is the current cross-system handover for the Terminal workflow, backend APIs, offline sync, and guided live key enrolment. The earlier [API_HANDOVER_SUPER_ADMIN.md](docs/API_HANDOVER_SUPER_ADMIN.md) remains useful as foundation detail, but does not cover the later live-hardware flow.

## Current implementation

- Step 1: shared policy, sync-conflict and Recycle Bin foundation.
- Step 2: Super Admin Users & Credentials (applied in the working Android Studio project).
- Step 3: responsive Sites & Terminals UI, shared cabinet-configuration validation,
  and backend handover. See `docs/STEP_3_SITES_TERMINALS_HANDOFF.md`.
- Step 4: Keys, cabinet slots and access grants, shared by Website and Terminal.
  Every key slot is validated against its terminal's configured Box Address +
  Node Address range (see `docs/Key_Cabinet_Communication_Protocol.md`), and
  access grants bind a user to an exact set of keys as their own record,
  separate from the user. Both follow the Step 1 soft-delete/Recycle Bin and
  sync-conflict patterns.

## Next build step

Wire the Website and Terminal previews to the backend API described in
`docs/API_HANDOVER_SUPER_ADMIN V4.md`, replacing local preview data with
authenticated, revision-aware calls.
