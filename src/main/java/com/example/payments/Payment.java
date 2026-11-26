package com.example.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record Payment(UUID requestId, BigDecimal amount, String currency, String orderId,
               String approvalUrl, String status, String captureId,
               Instant createStartedAt, Instant captureStartedAt) {
    PaymentView view() {
        return new PaymentView(requestId, amount, currency, orderId, approvalUrl, status, captureId);
    }
}

record PaymentView(UUID requestId, BigDecimal amount, String currency, String orderId,
                   String approvalUrl, String status, String captureId) {}
