CREATE TABLE IF NOT EXISTS shop_schema_version(version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE products (
 id BIGINT PRIMARY KEY CHECK(id>0), sku VARCHAR(64) UNIQUE NOT NULL, name VARCHAR(160) NOT NULL,
 price_vnd BIGINT NOT NULL CHECK(price_vnd>=0), stock_quantity INTEGER NOT NULL CHECK(stock_quantity>=0),
 status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE orders (
 id UUID PRIMARY KEY, customer_name VARCHAR(80) NOT NULL, total_vnd BIGINT NOT NULL CHECK(total_vnd>=0),
 status VARCHAR(16) NOT NULL DEFAULT 'CREATED' CHECK(status='CREATED'),
 idempotency_key VARCHAR(128) UNIQUE NOT NULL, request_hash CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE order_items (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id),
 product_id BIGINT NOT NULL REFERENCES products(id), quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 100),
 unit_price_vnd BIGINT NOT NULL CHECK(unit_price_vnd>=0), line_total_vnd BIGINT NOT NULL CHECK(line_total_vnd>=0),
 UNIQUE(order_id,product_id)
);
CREATE TABLE product_documents (
 id UUID PRIMARY KEY, product_id BIGINT NOT NULL REFERENCES products(id), display_name VARCHAR(100) NOT NULL,
 original_filename VARCHAR(100) NOT NULL, storage_key VARCHAR(40) UNIQUE NOT NULL,
 content_type VARCHAR(32) NOT NULL CHECK(content_type='application/pdf'), size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 1 AND 786432),
 sha256 CHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX product_documents_product_idx ON product_documents(product_id);
INSERT INTO shop_schema_version(version) VALUES(1);
