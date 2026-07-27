-- Portal-managed cabinet behavioral settings (Take/Return warning times, certification, video toggles).
-- 1:1 with terminals. Safe to re-apply: migrate runner ignores duplicate-table / duplicate-key errors.

CREATE TABLE IF NOT EXISTS terminal_cabinet_settings (
  terminal_id CHAR(36) NOT NULL PRIMARY KEY,
  take_warning_time_seconds INT NOT NULL DEFAULT 15,
  door_close_warning_time_seconds INT NOT NULL DEFAULT 15,
  key_return_certification_enabled TINYINT(1) NOT NULL DEFAULT 0,
  return_key_video_enabled TINYINT(1) NOT NULL DEFAULT 0,
  key_retrieval_video_enabled TINYINT(1) NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  updated_at_epoch_ms BIGINT NOT NULL,
  CONSTRAINT fk_tcs_terminal FOREIGN KEY (terminal_id) REFERENCES terminals(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill defaults for terminals that already exist.
INSERT INTO terminal_cabinet_settings (
  terminal_id,
  take_warning_time_seconds,
  door_close_warning_time_seconds,
  key_return_certification_enabled,
  return_key_video_enabled,
  key_retrieval_video_enabled,
  revision,
  updated_at_epoch_ms
)
SELECT
  t.id,
  15,
  15,
  0,
  0,
  0,
  1,
  UNIX_TIMESTAMP() * 1000
FROM terminals t
WHERE NOT EXISTS (
  SELECT 1 FROM terminal_cabinet_settings s WHERE s.terminal_id = t.id
)
