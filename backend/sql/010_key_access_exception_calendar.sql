-- Only B (exception access) calendar window fields for key_access_requests.
-- Additive. Do NOT put semicolons inside -- comments (migratePhase2 splits on ;).
-- Product: allow any pickup/return range — region max_key_access_duration_minutes is NOT
-- enforced for this flow. requested_duration_minutes is still stored as derived minutes
-- (return - pickup) for back-compat with older rows and terminal due-time math.

ALTER TABLE key_access_requests
  ADD COLUMN reason TEXT NULL AFTER site_id;

ALTER TABLE key_access_requests
  ADD COLUMN pickup_at_epoch_ms BIGINT NULL AFTER requested_duration_minutes;

ALTER TABLE key_access_requests
  ADD COLUMN return_at_epoch_ms BIGINT NULL AFTER pickup_at_epoch_ms;
