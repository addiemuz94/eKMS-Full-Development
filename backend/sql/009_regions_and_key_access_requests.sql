-- Region concept (geographic grouping ABOVE Site) + generalized key-access-request model.
-- Additive only — does not replace user_site_assignments, which Regional Admin keeps using for
-- everything that already worked that way (access grants, office hours, cabinet settings, etc).
-- Region's one job: route a Technician/Vendor's key-access request to the right Regional Admin,
-- since the request form only lets the requester pick a key/cabinet, not a person to approve it.
-- Safe to re-apply: migrate runner ignores duplicate-table / duplicate-column / duplicate-key errors.

-- Judgment call: regions get the same revision/lifecycle_state/soft-delete shape as sites, since
-- a region is a first-class Super-Admin-managed record, not a bare lookup table. `display_order`
-- is a small addition beyond what was asked (lets a future portal render regions in a chosen
-- order instead of alphabetically/by-id) — flagged, not load-bearing to anything in this pass.
-- `max_key_access_duration_minutes` is the "fixed/default return timing policy" ceiling the task
-- described — see the note on key_access_requests below for why it lives here (on regions) and
-- not per-site. Default 1440 (24h) matches the prior vendor_passkey_requests PASSKEY_TTL_MS for
-- continuity, not a newly guessed value.
CREATE TABLE IF NOT EXISTS regions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  max_key_access_duration_minutes INT NOT NULL DEFAULT 1440,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  UNIQUE KEY uq_regions_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nullable on purpose (task's own instruction): existing sites have no region assigned yet and
-- must not break. A NOT NULL + required-backfill approach was considered and rejected — there is
-- no sensible default region to backfill existing sites into (unlike site_office_hours' backfill,
-- which had one obvious default: Asia/Kuala_Lumpur for every site). An unassigned site's key-access
-- requests simply have no Region to route through yet — see keyAccessRequests.js's handling of a
-- null region_id (routes to nobody until a Super Admin assigns the site to a region).
ALTER TABLE sites
  ADD COLUMN region_id CHAR(36) NULL AFTER parent_site_id;

ALTER TABLE sites
  ADD CONSTRAINT fk_sites_region
  FOREIGN KEY (region_id) REFERENCES regions(id);

-- Same many-to-many shape as user_site_assignments (001_init.sql) — a Regional Admin may be
-- assigned to one or more Regions independently of their per-Site assignments. Deliberately no
-- consistency rule enforced between the two (e.g. a Regional Admin assigned to a Region need NOT
-- also be individually assigned to every Site inside it) — flagged simplification, not built as a
-- check here — revisit if drift between the two assignment sets turns out to cause real confusion.
CREATE TABLE IF NOT EXISTS user_region_assignments (
  user_id CHAR(36) NOT NULL,
  region_id CHAR(36) NOT NULL,
  PRIMARY KEY (user_id, region_id),
  CONSTRAINT fk_ura_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_ura_region FOREIGN KEY (region_id) REFERENCES regions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ADDS a more general mechanism ALONGSIDE vendor_passkey_requests (008) — this is NOT a rename
-- or replacement, corrected from an earlier draft of this migration that wrongly assumed the old
-- table was an unused placeholder. It is not: terminalApp's Phase 7 `TerminalVendorPasskeyScreen`
-- (Admin Menu item 16) is a real, deployed screen actively creating/approving/rejecting rows in
-- vendor_passkey_requests today. Renaming or dropping it would break that live, working feature
-- and touch terminalApp code, which is out of scope for this change. vendor_passkey_requests,
-- its route file (routes/vendorPasskeyRequests.js), and its `/v1/admin/vendor-passkey-requests`
-- mount are all left completely untouched. key_access_requests is a new, separate table for
-- mobileApp's (not-yet-built) request form going forward — Region-routed, multi-key,
-- Technician+Vendor — not a superset that migrates old data — no data migration copies
-- vendor_passkey_requests rows into it, since the two tables serve two different, coexisting
-- client paths (terminalApp's existing Vendor Passkey screen vs. mobileApp's future one).
--
-- Design choices, flagged:
-- - Naming for the NEW table only: requester_user_id (+ new requester_role) and
--   generated_passkey are this table's own column names, chosen to read clearly as a more
--   general concept than the old vendor_user_id/passkey_code — not a rename of those columns.
-- - `requester_role` reuses UserRole's existing TECHNICIAN/VENDOR values (VARCHAR(32), same
--   loosely-typed convention `users.role` already uses) — no new enum invented.
-- - Multi-key support: a linking table (key_access_request_keys, below) mirrors the existing
--   access_grant_keys (001_init.sql) shape rather than a single key_id column. This was the
--   explicit "make the schema decision that's easiest to extend either way" call — a linking
--   table trivially covers both "strictly one key per request" (one row) and Key-Menu-style
--   multi-key requests (N rows) with no future migration needed either way. Decision: multi-key
--   IS supported from the start, since the linking table costs nothing extra for the single-key
--   case and the mobile form (not yet built) may want to mirror Key Menu's multi-select exactly.
-- - `requested_duration_minutes`: the timing window the requester picked in the (not-yet-built)
--   mobile form. Bounded by the request's own site's Region's `max_key_access_duration_minutes`
--   — enforced at approval time in keyAccessRequests.js (clamped down, not rejected, if a request
--   asked for more than the Region currently allows).
-- - The fixed/default return-timing policy lives on `regions`, not per-site: the task's own
--   framing is that Region exists specifically to group Sites under one Regional Admin's
--   approval authority, so a single per-Region ceiling (one Regional Admin, one policy) was
--   judged more consistent with that purpose than per-Site ceilings duplicated across every Site
--   a Regional Admin covers. Flag if per-Site granularity turns out to be wanted after all — the
--   site_office_hours table already shows the alternative per-site pattern if this needs revisiting.
-- - `generated_passkey` is populated server-side only, at approval time (keyAccessRequests.js),
--   mirroring `passkey_code`'s existing 4-digit crypto.randomInt generation — never client-submitted.
-- - No `expectedRevision`/general PATCH column, matching the prior table's own reasoning: approve/
--   reject are the only two state transitions, each guarded by `status = 'PENDING'` in the
--   UPDATE's WHERE clause itself (double-checked, same spirit as expectedRevision elsewhere).
CREATE TABLE IF NOT EXISTS key_access_requests (
  id CHAR(36) NOT NULL PRIMARY KEY,
  requester_user_id CHAR(36) NOT NULL,
  -- TECHNICIAN | VENDOR (UserRole values; SUPER_ADMIN/REGIONAL_ADMIN never request their own access)
  requester_role VARCHAR(32) NOT NULL,
  site_id CHAR(36) NOT NULL,
  requested_at_epoch_ms BIGINT NOT NULL,
  requested_duration_minutes INT NOT NULL,
  -- PENDING | APPROVED | REJECTED
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  approved_by_user_id CHAR(36) NULL,
  approved_at_epoch_ms BIGINT NULL,
  generated_passkey CHAR(4) NULL,
  passkey_expires_at_epoch_ms BIGINT NULL,
  KEY idx_key_access_requests_site_status (site_id, status),
  KEY idx_key_access_requests_requester (requester_user_id),
  CONSTRAINT fk_key_access_requests_requester FOREIGN KEY (requester_user_id) REFERENCES users(id),
  CONSTRAINT fk_key_access_requests_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS key_access_request_keys (
  request_id CHAR(36) NOT NULL,
  key_id CHAR(36) NOT NULL,
  PRIMARY KEY (request_id, key_id),
  CONSTRAINT fk_kark_request FOREIGN KEY (request_id) REFERENCES key_access_requests(id),
  CONSTRAINT fk_kark_key FOREIGN KEY (key_id) REFERENCES managed_keys(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dedicated rate-limit ledger for POST /v1/terminal/passkey-login (unauthenticated by necessity,
-- same reasoning as pairing_attempts for pair-with-code — a 4-digit code is only 10,000 possible
-- values, materially brute-forceable). Deliberately a SEPARATE table from pairing_attempts rather
-- than reusing it: the two endpoints protect different secrets, and sharing one counter would
-- mean a burst of failed terminal pairings could also lock out passkey-login attempts (and vice
-- versa) from the same IP for an unrelated reason. Same shape as pairing_attempts by design, not
-- a coincidence — flagged spot for a future shared-rate-limiter-util extraction, not done here to
-- keep this change isolated.
CREATE TABLE IF NOT EXISTS key_access_login_attempts (
  id CHAR(36) NOT NULL PRIMARY KEY,
  ip_address VARCHAR(64) NOT NULL,
  succeeded TINYINT(1) NOT NULL DEFAULT 0,
  attempted_at_epoch_ms BIGINT NOT NULL,
  KEY idx_kala_ip_time (ip_address, attempted_at_epoch_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
