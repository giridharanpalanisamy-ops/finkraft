# Finkraft

E-invoice submission for the UAE: accepts invoices over a REST API, validates them against PINT AE rules, submits them to an Accredited Service Provider (ASP A by polling, ASP B by webhooks) and tracks each invoice to COMPLETED.

Start with [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md) for the architecture, the invoice lifecycle and every flow. The build spec is the contract; decisions taken where it is silent are in [docs/ASSUMPTIONS.md](docs/ASSUMPTIONS.md), and phase-by-phase status is in [docs/PROGRESS.md](docs/PROGRESS.md).

## Prerequisites

- JDK 21 (the Gradle toolchain finds an installed JDK 21; any JDK 17+ can run Gradle itself)
- Docker with Compose v2 (Testcontainers and the local stack)

## Build and test

```bash
./gradlew build
```

This runs every test:

| Kind | Where | What it proves |
|---|---|---|
| Unit | `libs/*`, `services/validator-service` | Every (state, event) pair; rank rule; content hash stability; duplicate rule; each adapter against WireMock; each validator rule |
| Integration | `services/invoice-handler` | Real Postgres and Kafka: claim concurrency, outbox commit/rollback, dedup, dead-lettering, role grants (T-R3), JSON logs |
| Scenario | `test-support/scenario-tests` | S1 to S19: all services and both mock ASPs in one JVM, each service with its own DB role |

The scenario suite takes about 5 minutes. On a laptop, keep the machine awake while it runs (`caffeinate -i ./gradlew build` on macOS): a sleep stalls every timer mid-scenario.

Useful subsets:

```bash
./gradlew :libs:state-machine:test                        # state machine only
./gradlew :services:invoice-handler:test                  # handler integration tests
./gradlew :test-support:scenario-tests:test --tests '*AspBScenariosTest'
```

Scenario logs go to `test-support/scenario-tests/build/scenario.log`.

## Run locally

```bash
./gradlew bootJar
docker compose up --build --wait
```

Everything, including both mock ASPs, comes up healthy. Host ports:

| Service | URL |
|---|---|
| management-service (public API) | http://localhost:18080 |
| invoice-handler (internal API) | http://localhost:18081 |
| webhook-handler | http://localhost:18085 |
| mock-asp-a / mock-asp-b | http://localhost:19001 / http://localhost:19002 |
| Postgres | localhost:15432 (finkraft / finkraft) |
| Kafka | localhost:29092 |

Compose shortens the ASP A poll schedule for a quick demo (see the `x-demo-timings` block).

### Walk an invoice through

```bash
ADMIN='X-Admin-Key: dev-admin-key'
API=http://localhost:18080/api/v1

# Tenant (the API key is shown once), seller, and routing to ASP B
TENANT=$(curl -s -H "$ADMIN" -H 'Content-Type: application/json' -d '{"name":"Acme"}' $API/admin/tenants)
KEY=$(echo "$TENANT" | jq -r .apiKey); TID=$(echo "$TENANT" | jq -r .id)
SID=$(curl -s -H "$ADMIN" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TID\",\"trn\":\"100000000000003\",\"name\":\"Acme Trading\"}" $API/admin/sellers | jq -r .id)
curl -s -H "$ADMIN" -H 'Content-Type: application/json' \
  -d "{\"sellerId\":\"$SID\",\"aspId\":\"asp-b\",\"secretRef\":\"dev-asp-b\"}" $API/admin/asp-configs

# Upload
ID=$(curl -s -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' $API/invoices -d '{
  "sellerTrn":"100000000000003","buyerTrn":"100000000000011","invoiceNumber":"INV-0001",
  "issueDate":"2026-10-01","currency":"AED",
  "lines":[{"description":"Consulting","quantity":2,"unitPrice":50,"vatCategory":"S","vatRate":5,"lineNet":100,"lineVat":5}],
  "totals":{"netTotal":100,"vatTotal":5,"grossTotal":105}}' | jq -r .invoiceId)

# Watch it reach COMPLETED
curl -s -H "X-Api-Key: $KEY" $API/invoices/$ID | jq .state
```

Use `"aspId":"asp-a"` and `"secretRef":"dev-asp-a"` to route through the polling ASP instead.

## API documentation (Swagger / OpenAPI)

| API | Swagger UI (local stack) | OpenAPI 3.1 spec |
|---|---|---|
| Public API (management-service) | http://localhost:18080/swagger-ui.html | [docs/api/public-api.openapi.json](docs/api/public-api.openapi.json) |
| Internal API (invoice-handler) | http://localhost:18081/swagger-ui.html | [docs/api/internal-api.openapi.json](docs/api/internal-api.openapi.json) |
| Webhook API (webhook-handler) | http://localhost:18085/swagger-ui.html | [docs/api/webhook-api.openapi.json](docs/api/webhook-api.openapi.json) |

Each running service also serves its spec at `/v3/api-docs`. The files in `docs/api` are regenerated from the running services by `ApiDocsTest` on every build, so they always match the code. In Swagger UI, use **Authorize** to set `X-Api-Key` or `X-Admin-Key`.

## Public API (management-service)

Every request needs `X-Api-Key`; admin endpoints need `X-Admin-Key` (`ADMIN_API_KEY`).

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/invoices` | One canonical invoice. 201 created (with `warning: POSSIBLE_DUPLICATE` when the content matches another invoice), 200 replay, 409 `DUPLICATE_INVOICE_NUMBER` |
| POST | `/api/v1/invoices/bulk` | JSON array, or CSV (`text/csv`, one single-line invoice per row; header in `InvoiceParser.CSV_COLUMNS`). Up to 1,000 rows; a result per row |
| GET | `/api/v1/invoices/{id}`, `/api/v1/invoices?state=` | Tenant-scoped reads |
| GET | `/api/v1/invoices/{id}/fix-suggestions` | Pending suggestions |
| POST | `/api/v1/invoices/{id}/revisions` | `{"invoice": {...}}` or `{"suggestionId": "..."}`; only after VALIDATION_FAILED or REJECTED |
| POST/GET | `/api/v1/admin/tenants`, `/sellers`, `/asp-configs` | Management tables |

## Operations

- Health: `/actuator/health` (readiness at `/actuator/health/readiness`) on every service.
- Metrics: `/actuator/metrics` and `/actuator/prometheus`. Finkraft metrics: `finkraft.invoices{state}`, `finkraft.invoices.oldest.age{state}`, `finkraft.outbox.unpublished`, `finkraft.outbox.lag`, `finkraft.asp.calls{asp,operation,outcome}` (latency and errors), `finkraft.unmatched.open`, `finkraft.sla.breaches{sla}`; consumer lag as `kafka.consumer.fetch.manager.records.lag.max`.
- Logs: JSON (logstash format) on stdout with `invoice_id` from the MDC. Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=` for plain text.
- Dead letters: `<topic>.DLT` for every consumed topic (illegal transitions, unmapped ASP statuses, undecodable records, and records that keep failing after retries).
- Configuration: every tunable is under `finkraft.*` in each service's `<service-name>.yml` and can be overridden with environment variables (`FINKRAFT_SUBMIT_MAX_ATTEMPTS=6`, ...).

## Layout

```
libs/            canonical-model, state-machine, event-contracts, adapter-api, asp-adapters, service-support
services/        management-service, invoice-handler, validator-service, integration-service,
                 status-mapper, webhook-handler, scheduler-service, validation-agent (phase 8, disabled)
test-support/    mock-asp-a, mock-asp-b, scenario-tests
docs/            HOW_IT_WORKS.md, ASSUMPTIONS.md, PROGRESS.md, api/ (OpenAPI specs)
```

Only `invoice-handler` writes invoice data (R3, enforced by database roles); it owns the Flyway migrations. Only `asp-adapters` knows ASP formats (R6).
