-- Move max_key_access_duration_minutes from Region to Site/Location ("regional confusion" rework
-- — the RA-authorization Tier 1 pass already moved approval-routing off Region; this migration
-- does the same for the one remaining real Region consumer, the site-policy duration-ceiling read
-- (GET /key-access-requests/site-policy/:siteId, keyAccessRequests.js). regions.max_key_access_
-- duration_minutes and the regions table itself are deliberately left in place — region survives
-- as a cosmetic map-grouping label only; a separate, later cleanup tier removes it, not this one.
--
-- Nullable — deliberately NOT "same NOT NULL DEFAULT 1440 as regions.max_key_access_duration_
-- minutes": a site's own ceiling is optional the same way sites.region_id is optional, and this
-- matches the existing SiteKeyAccessPolicyDto.maxKeyAccessDurationMinutes: Int? = null shared
-- contract already in ApiContracts.kt, plus the site-policy route's own existing fallback for "no
-- policy value" — null ("no ceiling to enforce"), not an invented numeric default. Flagged
-- explicitly since the literal instruction said "same type/nullable-ness as the region column,"
-- which is NOT NULL — followed the more specific backfill instruction (regionless sites stay
-- null) instead, since the two conflict and null-fallback is what the route already does today.
--
-- Safe to re-apply: migrate runner ignores duplicate-column errors.

ALTER TABLE sites
  ADD COLUMN max_key_access_duration_minutes INT NULL AFTER region_id;

-- Backfill: every site with a region assigned inherits that region's current ceiling value onto
-- the new site-level column, so this migration changes zero currently-observable API responses
-- the moment it lands (before any route code even reads the new column — that's the next, separate
-- statement in the app layer, not this file). Sites with no region_id get no backfill and stay
-- NULL, matching the existing "no region -> null ceiling" behavior in keyAccessRequests.js's
-- site-policy route exactly, not a new default.
UPDATE sites s
  INNER JOIN regions r ON r.id = s.region_id
  SET s.max_key_access_duration_minutes = r.max_key_access_duration_minutes
  WHERE s.region_id IS NOT NULL;
