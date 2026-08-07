-- Preserve a soft-deleted user's real email across the Recycle Bin so a new account can reuse it.
-- users.email keeps its plain UNIQUE KEY uq_users_email (001_init.sql) unscoped by lifecycle_state
-- — MySQL has no partial/filtered index — so a soft-deleted row's email must be moved out of that
-- column, not left in place, or ER_DUP_ENTRY blocks any new account registering with the same
-- address. original_email is the parking spot: users.js's DELETE /:id route moves the real email
-- here and writes a generated unique placeholder into `email` instead; the RECYCLE_BIN -> ACTIVE
-- restore route moves it back (after checking no ACTIVE user already holds it — see users.js /
-- recycleBin.js's restore route for the conflict-block logic, not enforced by a DB constraint
-- here since a soft-deleted row and an active row are both allowed to have coexisted, briefly, in
-- the previous email's `uq_users_email` slot at different times).
-- Same length/collation as `email` (VARCHAR(255), inherits the table's utf8mb4_unicode_ci default
-- — no COLLATE override, matching every other ALTER in this migration set). Nullable: only ever
-- populated while a user is in the Recycle Bin; ACTIVE users have NULL here.
-- Safe to re-apply: migrate runner ignores duplicate-column errors.

ALTER TABLE users
  ADD COLUMN original_email VARCHAR(255) NULL AFTER email;
