-- Phase 2: credentials metadata + sync conflict storage
CREATE TABLE IF NOT EXISTS credential_statuses (
  id CHAR(36) PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  credential_kind VARCHAR(64) NOT NULL,
  enrollment_status VARCHAR(64) NOT NULL DEFAULT 'NOT_ASSIGNED',
  terminal_id CHAR(36) NULL,
  note VARCHAR(255) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  UNIQUE KEY uq_user_kind (user_id, credential_kind),
  CONSTRAINT fk_cred_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS terminal_sync_state (
  terminal_id CHAR(36) PRIMARY KEY,
  server_revision BIGINT NOT NULL DEFAULT 1,
  last_bootstrap_at_epoch_ms BIGINT NULL,
  last_push_at_epoch_ms BIGINT NULL,
  last_download_at_epoch_ms BIGINT NULL,
  CONSTRAINT fk_sync_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_conflicts (
  id CHAR(36) PRIMARY KEY,
  terminal_id CHAR(36) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id CHAR(36) NOT NULL,
  server_revision BIGINT NOT NULL,
  local_operation_id VARCHAR(64) NOT NULL,
  local_base_revision BIGINT NOT NULL,
  local_payload_json TEXT NOT NULL,
  submitted_by_user_id CHAR(36) NULL,
  submitted_at_epoch_ms BIGINT NOT NULL,
  created_at_epoch_ms BIGINT NOT NULL,
  resolved_at_epoch_ms BIGINT NULL,
  resolved_by_user_id CHAR(36) NULL,
  resolution_strategy VARCHAR(32) NULL,
  merged_payload_json TEXT NULL,
  CONSTRAINT fk_conflict_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
