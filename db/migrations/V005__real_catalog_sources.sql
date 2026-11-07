-- Additive only. Existing orders, snapshots, stocks, users and file bytes remain intact.
ALTER TABLE st_products ADD COLUMN source_metadata TEXT NOT NULL DEFAULT '';
INSERT INTO shop_schema_version(version) VALUES(5);
