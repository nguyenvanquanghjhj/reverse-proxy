-- Extend the shared settings without changing products, files or orders.
ALTER TABLE st_operations ADD COLUMN uploads_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE st_operations ADD COLUMN reason VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE st_operations ADD COLUMN updated_by UUID REFERENCES st_users(id);
ALTER TABLE st_operations_history ADD COLUMN uploads_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE st_operations_history ADD COLUMN reason VARCHAR(200) NOT NULL DEFAULT '';
INSERT INTO shop_schema_version(version) VALUES(4);
