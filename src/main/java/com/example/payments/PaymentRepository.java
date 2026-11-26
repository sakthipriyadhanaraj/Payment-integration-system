package com.example.payments;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {
    private final JdbcClient db;

    PaymentRepository(JdbcClient db) {
        this.db = db;
    }

    void reserve(UUID id, BigDecimal amount, String currency) {
        db.sql("""
            INSERT INTO payment_orders (request_id, amount, currency)
            VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE request_id = request_id
            """).params(id.toString(), amount, currency).update();
    }

    Payment find(UUID id) {
        return db.sql("SELECT * FROM payment_orders WHERE request_id = ?")
            .param(id.toString()).query((row, index) -> {
                Timestamp captureStartedAt = row.getTimestamp("capture_started_at");
                return new Payment(id, row.getBigDecimal("amount"), row.getString("currency"),
                    row.getString("paypal_order_id"), row.getString("approval_url"),
                    row.getString("status"), row.getString("capture_id"),
                    row.getTimestamp("create_started_at").toInstant(),
                    captureStartedAt == null ? null : captureStartedAt.toInstant());
            }).optional().orElseThrow(() -> new PaymentException(404, "Payment request was not found"));
    }

    void saveOrder(UUID id, String orderId, String approvalUrl) {
        db.sql("""
            UPDATE payment_orders SET paypal_order_id = ?, approval_url = ?, status = 'AWAITING_APPROVAL'
            WHERE request_id = ? AND paypal_order_id IS NULL
            """).params(orderId, approvalUrl, id.toString()).update();
    }

    void startCapture(UUID id) {
        db.sql("""
            UPDATE payment_orders SET capture_started_at = CURRENT_TIMESTAMP(6)
            WHERE request_id = ? AND capture_started_at IS NULL
            """).param(id.toString()).update();
    }

    void saveCapture(UUID id, String captureId, String status) {
        db.sql("""
            UPDATE payment_orders SET capture_id = ?, status = ?
            WHERE request_id = ? AND status IN ('AWAITING_APPROVAL', 'PENDING')
            """).params(captureId, status, id.toString()).update();
    }
}
