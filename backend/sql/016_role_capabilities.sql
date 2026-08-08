-- Role capability matrix (Super Admin permission page). Capabilities can only narrow each
-- role's existing hardcoded allowlist ceiling in middleware/auth.js — never grant privileges
-- beyond that ceiling. SUPER_ADMIN is not stored (always unrestricted). TERMINAL_DEVICE /
-- GOD_ADMIN are out of scope for this table.

CREATE TABLE IF NOT EXISTS role_capabilities (
  role VARCHAR(32) NOT NULL,
  capability_key VARCHAR(64) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  updated_at_epoch_ms BIGINT NOT NULL,
  PRIMARY KEY (role, capability_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed defaults = current product behavior (all ceiling capabilities enabled).
-- Safe to re-apply: ignore duplicate-key on (role, capability_key).

INSERT INTO role_capabilities (role, capability_key, enabled, updated_at_epoch_ms) VALUES
  ('REGIONAL_ADMIN', 'portal.cabinet_management', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'portal.activity_report', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'portal.logs', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.timers', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.keys', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.key_permission', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.key_access', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'api.cabinet_settings', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'api.office_hours', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'api.access_grants', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'api.key_access_requests', 1, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'api.notifications', 1, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.cabinet_management', 1, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.key_access', 1, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'api.key_access_requests', 1, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'api.notifications', 1, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.cabinet_management', 1, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.key_access', 1, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'api.key_access_requests', 1, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'api.notifications', 1, UNIX_TIMESTAMP() * 1000);
