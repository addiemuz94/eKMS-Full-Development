-- Phase 1 (Revised): Regional Admin, key checkouts, per-site office hours, vendor passkey requests.
-- REGIONAL_ADMIN itself needs no schema change (users.role is VARCHAR(32), app-layer validated).
-- Safe to re-apply: migrate runner ignores duplicate-table / duplicate-key errors.

CREATE TABLE IF NOT EXISTS key_checkouts (
  id CHAR(36) NOT NULL PRIMARY KEY,
  key_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  terminal_id CHAR(36) NOT NULL,
  taken_at_epoch_ms BIGINT NOT NULL,
  due_at_epoch_ms BIGINT NOT NULL,
  -- OPEN | RETURNED only. OVERDUE is derived at query time (status = 'OPEN' AND due_at_epoch_ms
  -- < now), never stored/cron-flipped — see keyCheckouts.js's mapCheckout().
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  is_emergency TINYINT(1) NOT NULL DEFAULT 0,
  emergency_window_ends_at_epoch_ms BIGINT NULL,
  extension_requested_at_epoch_ms BIGINT NULL,
  -- PENDING | APPROVED | DENIED
  extension_status VARCHAR(32) NULL,
  extension_approved_by_user_id CHAR(36) NULL,
  extension_new_due_at_epoch_ms BIGINT NULL,
  returned_at_epoch_ms BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  KEY idx_checkouts_terminal_status (terminal_id, status),
  KEY idx_checkouts_key (key_id),
  KEY idx_checkouts_user (user_id),
  CONSTRAINT fk_checkouts_key FOREIGN KEY (key_id) REFERENCES managed_keys(id),
  CONSTRAINT fk_checkouts_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_checkouts_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Per-site office hours, including timezone (matches Site's existing per-site province/city
-- granularity). `revision` was added beyond the originally sketched schema — the spec asked
-- for the same expectedRevision-guarded PATCH pattern as terminal_cabinet_settings, which is
-- impossible without a revision counter to check against.
CREATE TABLE IF NOT EXISTS site_office_hours (
  site_id CHAR(36) NOT NULL PRIMARY KEY,
  open_time TIME NOT NULL DEFAULT '08:00:00',
  close_time TIME NOT NULL DEFAULT '17:00:00',
  -- Default is Asia/Kuala_Lumpur, not UTC: every site in this system is Malaysia-based (see
  -- web/src/geo/malaysiaLocations.ts) — flag any exception at backfill/fill-in time.
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kuala_Lumpur',
  revision BIGINT NOT NULL DEFAULT 1,
  updated_by_user_id CHAR(36) NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  CONSTRAINT fk_office_hours_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill defaults for sites that already exist, same "one sensible default, flag it" approach
-- terminal_cabinet_settings used for its own backfill in 007. Real per-site timezones (if any
-- site turns out not to be Asia/Kuala_Lumpur) need filling in later — not guessed here.
INSERT INTO site_office_hours (site_id, open_time, close_time, timezone, revision, updated_at_epoch_ms)
SELECT
  s.id,
  '08:00:00',
  '17:00:00',
  'Asia/Kuala_Lumpur',
  1,
  UNIX_TIMESTAMP() * 1000
FROM sites s
WHERE NOT EXISTS (
  SELECT 1 FROM site_office_hours h WHERE h.site_id = s.id
);

-- Deliberately minimal placeholder — full request/approval UX is designed later in the
-- mobileApp phase. No revision column: approve/reject are single state-transition actions
-- guarded by checking status = 'PENDING' in the UPDATE's WHERE clause itself (the same
-- double-checked-guard spirit as expectedRevision, just keyed on status instead of a counter,
-- since this table has no general field-level PATCH to protect).
CREATE TABLE IF NOT EXISTS vendor_passkey_requests (
  id CHAR(36) NOT NULL PRIMARY KEY,
  vendor_user_id CHAR(36) NOT NULL,
  site_id CHAR(36) NOT NULL,
  requested_at_epoch_ms BIGINT NOT NULL,
  -- PENDING | APPROVED | REJECTED
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  approved_by_user_id CHAR(36) NULL,
  approved_at_epoch_ms BIGINT NULL,
  passkey_code CHAR(4) NULL,
  passkey_expires_at_epoch_ms BIGINT NULL,
  KEY idx_vendor_passkey_site_status (site_id, status),
  KEY idx_vendor_passkey_vendor (vendor_user_id),
  CONSTRAINT fk_vendor_passkey_vendor FOREIGN KEY (vendor_user_id) REFERENCES users(id),
  CONSTRAINT fk_vendor_passkey_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
