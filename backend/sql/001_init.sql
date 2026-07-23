-- eKMS phase-1 schema. Soft-delete uses lifecycle_state = 'RECYCLE_BIN'.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS users (
  id CHAR(36) NOT NULL PRIMARY KEY,
  display_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NULL,
  role VARCHAR(32) NOT NULL,
  account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sites (
  id CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  address VARCHAR(512) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_site_assignments (
  user_id CHAR(36) NOT NULL,
  site_id CHAR(36) NOT NULL,
  PRIMARY KEY (user_id, site_id),
  CONSTRAINT fk_usa_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_usa_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS terminals (
  id CHAR(36) NOT NULL PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  box_address INT NOT NULL,
  serial_number VARCHAR(255) NULL,
  configured_slot_count INT NOT NULL DEFAULT 0,
  cabinet_serial_port VARCHAR(128) NULL,
  cabinet_baud_rate INT NULL,
  connection_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_terminals_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS managed_keys (
  id CHAR(36) NOT NULL PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  fob_enrollment_reference VARCHAR(255) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_keys_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS key_slots (
  id CHAR(36) NOT NULL PRIMARY KEY,
  terminal_id CHAR(36) NOT NULL,
  node_address INT NOT NULL,
  managed_key_id CHAR(36) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  KEY idx_slots_terminal_node (terminal_id, node_address),
  CONSTRAINT fk_slots_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id),
  CONSTRAINT fk_slots_key FOREIGN KEY (managed_key_id) REFERENCES managed_keys(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS access_grants (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  site_id CHAR(36) NOT NULL,
  valid_from_epoch_ms BIGINT NULL,
  valid_until_epoch_ms BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_grants_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_grants_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS access_grant_keys (
  grant_id CHAR(36) NOT NULL,
  key_id CHAR(36) NOT NULL,
  PRIMARY KEY (grant_id, key_id),
  CONSTRAINT fk_agk_grant FOREIGN KEY (grant_id) REFERENCES access_grants(id),
  CONSTRAINT fk_agk_key FOREIGN KEY (key_id) REFERENCES managed_keys(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_events (
  id CHAR(36) NOT NULL PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  actor_user_id CHAR(36) NULL,
  terminal_id CHAR(36) NULL,
  site_id CHAR(36) NULL,
  entity_type VARCHAR(32) NULL,
  entity_id CHAR(36) NULL,
  occurred_at_epoch_ms BIGINT NOT NULL,
  detail TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at_epoch_ms BIGINT NOT NULL,
  revoked TINYINT(1) NOT NULL DEFAULT 0,
  created_at_epoch_ms BIGINT NOT NULL,
  UNIQUE KEY uq_refresh_hash (token_hash),
  CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS idempotency_keys (
  idempotency_key VARCHAR(128) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  status_code INT NOT NULL,
  response_body JSON NOT NULL,
  created_at_epoch_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
