package com.example.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class PaypalGateway {
    private final RestClient client;
    private final StringRedisTemplate redis;
    private final String clientId;
    private final String secret;
    private final String returnUrl;
    private final String cancelUrl;
    private final String tokenKey;

    PaypalGateway(RestClient paypalClient, StringRedisTemplate redis,
                  @Value("${paypal.client-id}") String clientId,
                  @Value("${paypal.client-secret}") String secret,
                  @Value("${paypal.return-url}") String returnUrl,
                  @Value("${paypal.cancel-url}") String cancelUrl) {
        this.client = paypalClient;
        this.redis = redis;
        this.clientId = clientId;
        this.secret = secret;
        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
        this.tokenKey = "paypal:sandbox:oauth:" + fingerprint(clientId);
    }

    JsonNode create(Payment payment) {
        var amount = Map.of("currency_code", payment.currency(), "value", payment.amount().toPlainString());
        var context = Map.of("return_url", returnUrl, "cancel_url", cancelUrl,
            "shipping_preference", "NO_SHIPPING", "user_action", "PAY_NOW");
        var body = Map.of("intent", "CAPTURE", "purchase_units", List.of(Map.of("amount", amount)),
            "payment_source", Map.of("paypal", Map.of("experience_context", context)));
        return post("/v2/checkout/orders", payment.requestId(), body);
    }

    JsonNode capture(String orderId, UUID requestId) {
        return post("/v2/checkout/orders/" + orderId + "/capture", requestId, Map.of());
    }

    JsonNode order(String orderId) {
        String token = accessToken();
        return call(() -> client.get().uri("/v2/checkout/orders/{id}", orderId)
            .headers(headers -> headers.setBearerAuth(token)).retrieve().body(JsonNode.class), false);
    }

    private JsonNode post(String path, UUID requestId, Object body) {
        String token = accessToken();
        return call(() -> client.post().uri(path)
            .headers(headers -> headers.setBearerAuth(token))
            .header("PayPal-Request-Id", requestId.toString())
            .header("Prefer", "return=representation")
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class), false);
    }

    private String accessToken() {
        String cached = redis.opsForValue().get(tokenKey);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        JsonNode response = call(() -> client.post().uri("/v1/oauth2/token")
            .headers(headers -> headers.setBasicAuth(clientId, secret))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(JsonNode.class), true);
        String token = response.path("access_token").asText("");
        long seconds = response.path("expires_in").asLong();
        if (token.isBlank() || seconds <= 0) {
            throw new PaymentException(502, "PayPal returned an invalid OAuth response");
        }
        if (seconds > 60) {
            redis.opsForValue().set(tokenKey, token, Duration.ofSeconds(seconds - 60));
        }
        return token;
    }

    private JsonNode call(Supplier<JsonNode> request, boolean oauth) {
        try {
            JsonNode response = request.get();
            if (response == null || !response.isObject()) {
                throw new PaymentException(502, "PayPal returned an invalid response");
            }
            return response;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 401 && !oauth) {
                redis.delete(tokenKey);
                throw new PaymentException(503, "PayPal token was rejected. Retry the same payment request.");
            }
            if (code == 429 || code >= 500) {
                throw new PaymentException(503, "PayPal is temporarily unavailable. Retry the same payment request.");
            }
            if (!oauth && code == 422) {
                throw new PaymentException(409, "PayPal could not process the order in its current state");
            }
            throw new PaymentException(502, oauth ? "PayPal rejected the OAuth request" : "PayPal rejected the order request");
        } catch (ResourceAccessException ex) {
            throw new PaymentException(503, "PayPal connection failed. Retry the same payment request.");
        } catch (RestClientException ex) {
            throw new PaymentException(502, "Could not read the PayPal response");
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
