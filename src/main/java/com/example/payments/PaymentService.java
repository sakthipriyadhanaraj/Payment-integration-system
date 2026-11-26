package com.example.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PaymentService {
    private static final Duration RETRY_WINDOW = Duration.ofHours(5);
    private final PaymentRepository repository;
    private final PaypalGateway paypal;

    PaymentService(PaymentRepository repository, PaypalGateway paypal) {
        this.repository = repository;
        this.paypal = paypal;
    }

    PaymentView create(UUID id, BigDecimal amount, String currency) {
        repository.reserve(id, amount.setScale(2), currency);
        Payment payment = repository.find(id);
        if (payment.amount().compareTo(amount) != 0 || !payment.currency().equals(currency)) {
            throw new PaymentException(409, "This request ID already belongs to a different amount or currency");
        }
        if (payment.orderId() != null) {
            return payment.view();
        }
        checkRetryWindow(payment.createStartedAt());
        JsonNode response = paypal.create(payment);
        String orderId = response.path("id").asText("");
        if (!orderId.matches("[A-Za-z0-9]{1,64}")) {
            throw new PaymentException(502, "PayPal returned an invalid order ID");
        }
        for (JsonNode link : response.path("links")) {
            String relation = link.path("rel").asText();
            String url = link.path("href").asText();
            if (("payer-action".equals(relation) || "approve".equals(relation))
                    && url.startsWith("https://www.sandbox.paypal.com/")) {
                repository.saveOrder(id, orderId, url);
                return repository.find(id).view();
            }
        }
        throw new PaymentException(502, "PayPal returned no Sandbox approval link");
    }

    PaymentView find(UUID id) {
        return repository.find(id).view();
    }

    PaymentView capture(UUID id) {
        Payment payment = repository.find(id);
        if (payment.orderId() == null) {
            throw new PaymentException(409, "Create the PayPal order before capturing it");
        }
        if ("COMPLETED".equals(payment.status()) || "DECLINED".equals(payment.status())) {
            return payment.view();
        }
        JsonNode order = paypal.order(payment.orderId());
        JsonNode existingCapture = captureDetails(order);
        if (!existingCapture.isMissingNode()) {
            return recordCapture(payment, order);
        }
        if (!"APPROVED".equals(order.path("status").asText())) {
            throw new PaymentException(409, "Approve the order in PayPal before capturing it");
        }

        repository.startCapture(id);
        checkRetryWindow(repository.find(id).captureStartedAt());
        UUID captureKey = UUID.nameUUIDFromBytes(("capture:" + id).getBytes(StandardCharsets.UTF_8));
        return recordCapture(payment, paypal.capture(payment.orderId(), captureKey));
    }

    private PaymentView recordCapture(Payment payment, JsonNode order) {
        JsonNode capture = captureDetails(order);
        String captureId = capture.path("id").asText("");
        String status = capture.path("status").asText("");
        JsonNode amount = capture.path("amount");
        BigDecimal capturedAmount;
        try {
            capturedAmount = new BigDecimal(amount.path("value").asText());
        } catch (NumberFormatException ex) {
            throw new PaymentException(502, "PayPal returned a capture without a valid amount");
        }
        if (!payment.orderId().equals(order.path("id").asText()) || captureId.isBlank()
                || payment.amount().compareTo(capturedAmount) != 0
                || !payment.currency().equals(amount.path("currency_code").asText())) {
            throw new PaymentException(502, "PayPal capture details did not match the payment");
        }
        if (!"COMPLETED".equals(status) && !"PENDING".equals(status) && !"DECLINED".equals(status)) {
            throw new PaymentException(502, "PayPal returned an unexpected capture status");
        }
        repository.saveCapture(payment.requestId(), captureId, status);
        return repository.find(payment.requestId()).view();
    }

    private JsonNode captureDetails(JsonNode order) {
        return order.path("purchase_units").path(0).path("payments").path("captures").path(0);
    }

    private void checkRetryWindow(Instant startedAt) {
        if (Instant.now().isAfter(startedAt.plus(RETRY_WINDOW))) {
            throw new PaymentException(409, "Retry window expired. Check the original payment in PayPal before retrying.");
        }
    }
}
