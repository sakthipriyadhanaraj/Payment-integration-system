package com.example.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/orders")
    PaymentView create(@Valid @RequestBody CreateOrder request) {
        return payments.create(request.requestId(), request.amount(), request.currency());
    }

    @GetMapping("/orders/{requestId}")
    PaymentView find(@PathVariable UUID requestId) {
        return payments.find(requestId);
    }

    @PostMapping("/orders/{requestId}/capture")
    PaymentView capture(@PathVariable UUID requestId) {
        return payments.capture(requestId);
    }

    @GetMapping("/checkout/approved")
    String approved() {
        return "Buyer returned from PayPal. Send the capture request using the original request ID.";
    }

    @GetMapping("/checkout/cancelled")
    String cancelled() {
        return "Buyer cancelled checkout. No capture was requested.";
    }

    record CreateOrder(@NotNull UUID requestId,
                       @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                       @NotNull @Pattern(regexp = "USD") String currency) {}
}
