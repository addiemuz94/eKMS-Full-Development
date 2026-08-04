-- Checkout-deadline notification de-duplication (15-minute warning + overdue), keyed so each
-- fires exactly once per checkout regardless of how many deadlineMonitor.js ticks observe the
-- same due checkout. Additive. Safe to re-apply with ignoreDuplicates.

CREATE TABLE IF NOT EXISTS key_checkout_notifications (
  id CHAR(36) NOT NULL PRIMARY KEY,
  checkout_id CHAR(36) NOT NULL,
  notification_type ENUM('WARNING_15MIN', 'OVERDUE') NOT NULL,
  sent_at_epoch_ms BIGINT NOT NULL,
  UNIQUE KEY uq_checkout_notification (checkout_id, notification_type),
  CONSTRAINT fk_kcn_checkout FOREIGN KEY (checkout_id) REFERENCES key_checkouts(id)
);
