-- Vendor staged approval + document blobs + FCM device tokens.
-- Additive. Safe to re-apply with ignoreDuplicates.

ALTER TABLE key_access_requests
  ADD COLUMN pic_user_id CHAR(36) NULL AFTER requester_role;

ALTER TABLE key_access_requests
  ADD COLUMN pic_approved_at_epoch_ms BIGINT NULL AFTER approved_at_epoch_ms;

ALTER TABLE key_access_requests
  ADD CONSTRAINT fk_key_access_requests_pic
    FOREIGN KEY (pic_user_id) REFERENCES users(id);

CREATE TABLE IF NOT EXISTS key_access_request_documents (
  id CHAR(36) NOT NULL PRIMARY KEY,
  request_id CHAR(36) NOT NULL,
  doc_kind VARCHAR(32) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
  -- MEDIUMBLOB's 16 MB is the column's technical ceiling, not the enforced limit. The real,
  -- enforced ceiling is 5 MB, checked both client-side (KeyAccessRequestScreen.kt, UX-only) and
  -- server-side (keyAccessRequests.js's POST /, the actual gate — a client check alone can be
  -- bypassed). Do not treat MEDIUMBLOB's headroom as license to raise the enforced limit here
  -- without also raising it in both of those places.
  content MEDIUMBLOB NOT NULL,
  created_at_epoch_ms BIGINT NOT NULL,
  KEY idx_kard_request (request_id),
  CONSTRAINT fk_kard_request FOREIGN KEY (request_id) REFERENCES key_access_requests(id)
);

CREATE TABLE IF NOT EXISTS mobile_push_tokens (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  fcm_token VARCHAR(512) NOT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'ANDROID',
  updated_at_epoch_ms BIGINT NOT NULL,
  UNIQUE KEY uq_mobile_push_token (fcm_token),
  KEY idx_mobile_push_user (user_id),
  CONSTRAINT fk_mobile_push_user FOREIGN KEY (user_id) REFERENCES users(id)
);
