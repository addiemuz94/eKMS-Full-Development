-- Phase 4: portal workflow tables (appointments, events, schedules, groups, reports jobs)
CREATE TABLE IF NOT EXISTS event_definitions (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  event_number VARCHAR(64) NOT NULL,
  requirement VARCHAR(512) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_event_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS schedules (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  frequency VARCHAR(32) NOT NULL,
  time_window_label VARCHAR(255) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_schedule_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS personnel_groups (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  code VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  UNIQUE KEY uq_personnel_group_code (site_id, code),
  CONSTRAINT fk_pg_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS key_groups (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  code VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  UNIQUE KEY uq_key_group_code (site_id, code),
  CONSTRAINT fk_kg_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS multi_authentication_rules (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  primary_personnel_group_id CHAR(36) NOT NULL,
  assistant_group_one_id CHAR(36) NULL,
  assistant_group_two_id CHAR(36) NULL,
  key_group_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_mar_site FOREIGN KEY (site_id) REFERENCES sites(id),
  CONSTRAINT fk_mar_primary FOREIGN KEY (primary_personnel_group_id) REFERENCES personnel_groups(id),
  CONSTRAINT fk_mar_key_group FOREIGN KEY (key_group_id) REFERENCES key_groups(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS appointment_reasons (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_ar_site FOREIGN KEY (site_id) REFERENCES sites(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS appointments (
  id CHAR(36) PRIMARY KEY,
  site_id CHAR(36) NOT NULL,
  terminal_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  reason_id CHAR(36) NULL,
  reason_label VARCHAR(255) NULL,
  pickup_window_label VARCHAR(255) NOT NULL,
  valid_from_epoch_ms BIGINT NULL,
  valid_until_epoch_ms BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reviewer_user_id CHAR(36) NULL,
  review_detail VARCHAR(512) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at_epoch_ms BIGINT NOT NULL,
  updated_at_epoch_ms BIGINT NOT NULL,
  deleted_at_epoch_ms BIGINT NULL,
  deleted_by_user_id CHAR(36) NULL,
  CONSTRAINT fk_appt_site FOREIGN KEY (site_id) REFERENCES sites(id),
  CONSTRAINT fk_appt_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id),
  CONSTRAINT fk_appt_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS appointment_keys (
  appointment_id CHAR(36) NOT NULL,
  key_id CHAR(36) NOT NULL,
  PRIMARY KEY (appointment_id, key_id),
  CONSTRAINT fk_ak_appt FOREIGN KEY (appointment_id) REFERENCES appointments(id),
  CONSTRAINT fk_ak_key FOREIGN KEY (key_id) REFERENCES managed_keys(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS report_export_jobs (
  id CHAR(36) PRIMARY KEY,
  kind VARCHAR(64) NOT NULL,
  format VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  filter_json TEXT NOT NULL,
  row_count INT NOT NULL DEFAULT 0,
  download_path VARCHAR(512) NULL,
  created_by_user_id CHAR(36) NULL,
  created_at_epoch_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
