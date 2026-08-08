-- Unlock former "Super Admin only" (and other out-of-ceiling) catalog keys for editable
-- roles so the Role permissions matrix can tick them. Default OFF — product behavior is
-- unchanged until a Super Admin enables a capability. Safe to re-apply (ignoreDuplicates).

INSERT INTO role_capabilities (role, capability_key, enabled, updated_at_epoch_ms) VALUES
  -- Regional Admin: former SA-only admin tools
  ('REGIONAL_ADMIN', 'portal.registration', 0, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'portal.user_management', 0, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'portal.deleted_items', 0, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'portal.erase_data', 0, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.identity', 0, UNIX_TIMESTAMP() * 1000),
  ('REGIONAL_ADMIN', 'cabinet.assign_user', 0, UNIX_TIMESTAMP() * 1000),
  -- Technician: full catalog beyond historical ceiling (all OFF except rows already seeded in 016)
  ('TECHNICIAN', 'portal.activity_report', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.logs', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.timers', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.keys', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.key_permission', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'api.cabinet_settings', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'api.office_hours', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'api.access_grants', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.registration', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.user_management', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.deleted_items', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'portal.erase_data', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.identity', 0, UNIX_TIMESTAMP() * 1000),
  ('TECHNICIAN', 'cabinet.assign_user', 0, UNIX_TIMESTAMP() * 1000),
  -- Vendor: same as Technician
  ('VENDOR', 'portal.activity_report', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.logs', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.timers', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.keys', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.key_permission', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'api.cabinet_settings', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'api.office_hours', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'api.access_grants', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.registration', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.user_management', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.deleted_items', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'portal.erase_data', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.identity', 0, UNIX_TIMESTAMP() * 1000),
  ('VENDOR', 'cabinet.assign_user', 0, UNIX_TIMESTAMP() * 1000);
