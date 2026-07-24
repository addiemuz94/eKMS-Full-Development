-- Opaque personnel/key credential enrollment references (never raw NFC UIDs).
-- Safe to re-apply: migrate runner ignores duplicate-column errors.
ALTER TABLE credential_statuses
  ADD COLUMN enrollment_reference VARCHAR(128) NULL AFTER terminal_id;
