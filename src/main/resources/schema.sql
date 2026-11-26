CREATE TABLE IF NOT EXISTS payment_orders (
    request_id VARCHAR(36) PRIMARY KEY,
    amount DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    create_started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    capture_started_at TIMESTAMP(6),
    paypal_order_id VARCHAR(64) UNIQUE,
    approval_url VARCHAR(2048),
    status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
    capture_id VARCHAR(64)
);
