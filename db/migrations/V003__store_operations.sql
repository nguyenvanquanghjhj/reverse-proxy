-- Shared controls: no replica-local flags and no reset of catalog or orders.
CREATE TABLE st_operations (
 id INTEGER PRIMARY KEY CHECK(id=1),
 sales_enabled BOOLEAN NOT NULL DEFAULT true,
 downloads_enabled BOOLEAN NOT NULL DEFAULT true,
 version INTEGER NOT NULL DEFAULT 1,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO st_operations(id) VALUES(1);
CREATE TABLE st_operations_history (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 sales_enabled BOOLEAN NOT NULL,
 downloads_enabled BOOLEAN NOT NULL,
 version INTEGER UNIQUE NOT NULL,
 actor_id UUID NOT NULL REFERENCES st_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO shop_schema_version(version) VALUES(3);
