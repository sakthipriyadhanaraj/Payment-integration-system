# PayPal Sandbox Payments

A local Java 17 and Spring Boot project that creates PayPal Sandbox orders, lets a test buyer approve them, captures funds, and records payment state in MySQL. Redis caches the OAuth token.

## Build the project in this order

| Step | Files | What to understand |
| --- | --- | --- |
| 1 | `pom.xml`, `PaymentsApplication.java`, `application.properties` | Maven dependencies, the Spring Boot entry point, and configuration injection |
| 2 | `compose.yaml`, `schema.sql`, `Payment.java`, `PaymentRepository.java` | Local MySQL and Redis, row mapping, and the request UUID as the database key |
| 3 | `PaypalGateway.java` | OAuth client credentials, Redis expiry, and the create, lookup, and capture requests |
| 4 | `PaymentService.java` | Payment state, retry keys, and checking the capture amount and status |
| 5 | `PaymentController.java`, `PaymentException.java`, `PaymentErrorHandler.java` | JSON request validation, routing, and HTTP error responses |

All Java sources are under `src/main/java/com/example/payments`. Read the controller first to follow an incoming request, then trace the service into the repository and gateway. The gateway uses the [PayPal Orders v2 API](https://developer.paypal.com/api/orders/v2).

## Run on Windows

Install JDK 17, Maven 3.6.3 or later, and Docker Desktop with Docker Compose. From the folder containing `pom.xml`:

```powershell
java -version
mvn -version
docker compose up -d --wait
mvn clean package
```

Create a Sandbox REST app tied to a Sandbox business account in [PayPal Developer Dashboard](https://developer.paypal.com/dashboard/). You also need a separate Sandbox personal account to approve checkout. In PowerShell, set the app's credentials for the current shell and run the service:

```powershell
$env:PAYPAL_CLIENT_ID = 'your-sandbox-client-id'
$env:PAYPAL_CLIENT_SECRET = 'your-sandbox-client-secret'
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. Compose exposes MySQL on port 3307 and Redis on 6380. If using services you already installed, adjust `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, and `REDIS_PORT` as needed. If the browser and API run on different machines, configure `PAYPAL_RETURN_URL` and `PAYPAL_CANCEL_URL` for the browser environment.

## Demo with PowerShell or Postman

Run the following in another PowerShell window. Keep the same request ID for retries.

```powershell
$base = 'http://localhost:8080'
$id = [guid]::NewGuid().ToString()
$body = @{ requestId = $id; amount = 12.50; currency = 'USD' } | ConvertTo-Json
$order = Invoke-RestMethod "$base/orders" -Method Post -ContentType 'application/json' -Body $body
$order
```

Expect an order ID, an approval URL, and `AWAITING_APPROVAL`. Before opening the approval URL, try the capture request once in Postman. Expect HTTP 409. The capture route uses the UUID you sent in `requestId`.

```powershell
Start-Process $order.approvalUrl
```

Sign in with the Sandbox personal buyer account, approve the order, and return to the local page. The return page confirms navigation. The server checks PayPal directly when you call capture:

```powershell
Invoke-RestMethod "$base/orders/$id/capture" -Method Post
Invoke-RestMethod "$base/orders/$id"
docker compose exec mysql mysql -u payments -p payments
```

Enter the local Compose password `local-payments` when prompted. In MySQL:

```sql
SELECT request_id, amount, currency, paypal_order_id, status, capture_id
FROM payment_orders;
```

Expect a capture ID and `COMPLETED` for a successful payment. A pending capture stays `PENDING`. Calling capture again checks PayPal for its current state. Repeat the original create request and verify the same PayPal order ID returns. Repeat capture and verify the same capture ID returns. Change the amount while reusing the UUID and verify HTTP 409. Send a negative amount or a currency other than USD and verify HTTP 400.

## Interview discussion

- **Why MySQL and Redis?** MySQL stores durable payment state. Redis keeps a short-lived OAuth token with expiry before PayPal's deadline.
- **What if PayPal captures successfully and MySQL fails?** A retry reads the existing capture from PayPal, checks its ID, amount, currency and status, and records it locally.
- **What if the create call times out?** Reuse the same client request UUID. The UUID is persisted before calling PayPal and sent as the create idempotency key. Capture uses a different deterministic key. After the local five-hour retry window, check the original payment manually before issuing a new request.

This demo is scoped to USD, one purchase unit per order, and a local Sandbox environment. API authentication, webhook processing, refunds, and automated reconciliation jobs are outside its scope. The manual checks above are the verification checklist.

## Verification

The Sandbox create, buyer approval and capture flow was exercised locally using Postman, Docker Desktop on Windows ARM64, and a US Sandbox personal buyer. The MySQL connection uses UTC to avoid a time-zone offset affecting the retry window. The retry and error cases are manual verification scenarios.
