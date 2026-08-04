-- PIN-expiry recurring auto-extend for key_access_requests (Only B Technician + Vendor
-- two-stage requests). Additive only. Safe to re-apply: migrate runner ignores duplicate-column
-- errors (see migratePhase2.js's IGNORE_ERRNOS).
--
-- Design: an APPROVED request whose PIN window (passkey_expires_at_epoch_ms /
-- return_at_epoch_ms) lapses without being used is auto-extended by 1 hour, repeating
-- indefinitely, by a dedicated tick job (backend/src/keyAccessAutoExtend.js) rather than being
-- lazily flipped to EXPIRED the way expireOverdueRequests() used to (that lazy-expire clause is
-- narrowed in the same pass this migration ships with, so the two mechanisms don't race).
--
-- first_used_at_epoch_ms is the auto-extend cycle's stop condition: stamped once, on the first
-- successful terminal passkey-login for this request (passkeyLogin() in keyAccessRequests.js),
-- and never overwritten on subsequent logins within the same window. The PIN itself is NOT
-- invalidated on first use -- it stays valid for the rest of its current (possibly
-- already-extended) window; only future auto-extension stops. A request that reaches
-- first_used_at_epoch_ms still leaves APPROVED normally via its own eventual real expiry, revoke,
-- or (new) requester-initiated cancel -- this column only gates the extend job's own query.
ALTER TABLE key_access_requests
  ADD COLUMN first_used_at_epoch_ms BIGINT NULL AFTER passkey_expires_at_epoch_ms;

-- status remains VARCHAR(32), not a SQL ENUM type, consistent with every other lifecycle/status
-- column in this schema (sites.lifecycle_state, key_checkouts.status, etc.) -- no column-type
-- change needed to add a new valid value. Documenting the now-current full value set here since
-- CANCELLED is new: PENDING | PENDING_PIC | PENDING_RA | APPROVED | REJECTED | REVOKED | EXPIRED
-- | CANCELLED. CANCELLED is requester-initiated (new POST .../:id/cancel, Vendor/Technician-only,
-- self-service) -- distinct from REVOKED (admin-initiated, POST .../:id/revoke) purely for audit
-- trail clarity about who ended the request; both are terminal, no-revive states, same as EXPIRED.
