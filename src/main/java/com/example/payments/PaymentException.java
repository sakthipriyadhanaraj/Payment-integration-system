package com.example.payments;

class PaymentException extends RuntimeException {
    private final int status;

    PaymentException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
