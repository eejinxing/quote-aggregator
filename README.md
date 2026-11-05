# quote-aggregator

A small Java 21 + Spring Boot 3.5 service that calls three downstream dependencies (price, stock, shipping) in parallel and aggregates them into a single quote.

The focus is concurrency control: non-blocking request threads, per-dependency bulkheads, load shedding, timeouts, graceful degradation, request-id logging and graceful shutdown. The downstream services are simulated, so no external setup is needed.

## Requirements

- JDK 21
- Maven 3.9+

## Configuration

All settings live in `src/main/resources/application.yml`.

| Property | Description |
|---|---|
| `app.concurrency.io-pool.*` | Worker pool for downstream calls (`max-size` must be >= the sum of all bulkheads) |
| `app.concurrency.bulkheads.*` | Max concurrent calls per dependency (`price`, `stock`, `shipping`) |
| `app.concurrency.quote.call-timeout` | Timeout for a single downstream call |
| `app.concurrency.quote.total-timeout` | Deadline for the whole request |
| `app.simulated-downstream.*` | Simulated latency of each downstream service |

Any property can be overridden on the command line, for example:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.concurrency.bulkheads.price=4
```

## Run

```bash
mvn test
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

## Try it

The SKU prefix controls the simulated behavior:

```bash
curl -i localhost:8080/api/quotes/ABC-123
curl -i localhost:8080/api/quotes/NOSTOCK-1
curl -i localhost:8080/api/quotes/FAIL-1
curl -i localhost:8080/api/quotes/SLOW-1
curl -i localhost:8080/api/quotes/ab
curl 'localhost:8080/actuator/metrics/bulkhead.available?tag=name:price'
```

| SKU | Result |
|---|---|
| `ABC-123` | 200, full quote |
| `NOSTOCK-1` | 200, `degraded=true` (stock missing) |
| `FAIL-1` | 502, price dependency failed |
| `SLOW-1` | 504, dependency timed out |
| `ab` | 400, invalid SKU |

When a bulkhead is full the service answers `503` with a `Retry-After` header.
