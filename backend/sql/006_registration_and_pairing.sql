-- Key Cabinet + Personnel registration fields, plus the one-time terminal pairing-code mechanism.
-- Safe to re-apply: migrate runner ignores duplicate-column errors.

ALTER TABLE terminals
  ADD COLUMN vendor_device_id VARCHAR(255) NULL AFTER serial_number,
  ADD COLUMN node_rows INT NULL AFTER configured_slot_count,
  ADD COLUMN nodes_per_row INT NULL AFTER node_rows,
  ADD COLUMN latitude DECIMAL(10, 7) NULL AFTER cabinet_baud_rate,
  ADD COLUMN longitude DECIMAL(10, 7) NULL AFTER latitude,
  ADD COLUMN paired TINYINT(1) NOT NULL DEFAULT 0 AFTER connection_state,
  ADD COLUMN paired_at_epoch_ms BIGINT NULL AFTER paired,
  -- Only the hash is ever stored; the plaintext 6-digit code is returned exactly once,
  -- at generation time, in the create/regenerate response.
  ADD COLUMN pairing_code_hash CHAR(64) NULL AFTER paired_at_epoch_ms,
  ADD COLUMN pairing_code_expires_at_epoch_ms BIGINT NULL AFTER pairing_code_hash,
  -- NULL = still valid/unused. Non-null = already consumed; single-use enforced by checking this.
  ADD COLUMN pairing_code_consumed_at_epoch_ms BIGINT NULL AFTER pairing_code_expires_at_epoch_ms;

-- Refresh tokens for TERMINAL_DEVICE-scoped sessions. Kept separate from the existing
-- `refresh_tokens` table (FK'd to users) rather than relaxing that table's FK, since a
-- terminal-scoped token's `sub` is a terminals.id, not a users.id.
CREATE TABLE IF NOT EXISTS terminal_refresh_tokens (
  id CHAR(36) NOT NULL PRIMARY KEY,
  terminal_id CHAR(36) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at_epoch_ms BIGINT NOT NULL,
  revoked TINYINT(1) NOT NULL DEFAULT 0,
  created_at_epoch_ms BIGINT NOT NULL,
  UNIQUE KEY uq_terminal_refresh_hash (token_hash),
  KEY idx_terminal_refresh_terminal (terminal_id),
  CONSTRAINT fk_terminal_refresh_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Rate-limiting/lockout ledger for POST /v1/terminal/pair-with-code. Scoped by source IP,
-- since a brute-force attempt against this endpoint has no other identifier available
-- (the whole point of the attack is guessing which terminal a code belongs to).
CREATE TABLE IF NOT EXISTS pairing_attempts (
  id CHAR(36) NOT NULL PRIMARY KEY,
  ip_address VARCHAR(64) NOT NULL,
  succeeded TINYINT(1) NOT NULL DEFAULT 0,
  attempted_at_epoch_ms BIGINT NOT NULL,
  KEY idx_pairing_attempts_ip_time (ip_address, attempted_at_epoch_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE users
  ADD COLUMN staff_id VARCHAR(128) NULL AFTER email;
