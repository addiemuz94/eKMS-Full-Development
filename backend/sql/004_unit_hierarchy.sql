-- Unit Settings hierarchy fields (province/state, city, superior unit).
-- Safe to re-apply: migrate runner ignores duplicate-column / duplicate-key errors.

ALTER TABLE sites
  ADD COLUMN province VARCHAR(128) NULL AFTER name;

ALTER TABLE sites
  ADD COLUMN city VARCHAR(128) NULL AFTER province;

ALTER TABLE sites
  ADD COLUMN parent_site_id CHAR(36) NULL AFTER city;

ALTER TABLE sites
  ADD CONSTRAINT fk_sites_parent
  FOREIGN KEY (parent_site_id) REFERENCES sites(id);
