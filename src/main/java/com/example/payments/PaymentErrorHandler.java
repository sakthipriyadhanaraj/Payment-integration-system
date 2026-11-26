package com.example.payments;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class PaymentErrorHandler {
    @ExceptionHandler(PaymentException.class)
    ResponseEntity<ApiError> paymentError(PaymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        var field = ex.getBindingResult().getFieldError();
        String message = field == null ? "Invalid order" : field.getField() + " " + field.getDefaultMessage();
        return ResponseEntity.badRequest().body(new ApiError(message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed() {
        return ResponseEntity.badRequest().body(new ApiError("Malformed request body or request ID"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> dataError() {
        return ResponseEntity.status(503).body(new ApiError("Payment storage or token cache is unavailable"));
    }

    record ApiError(String message) {}
}
