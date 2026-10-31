# Assumptions

Decisions taken where the spec is silent (spec section 1, rule 4). Each keeps every invariant.

## Build and tooling

- **ShedLock 6.10.0, not 7.x.** ShedLock 7 requires Spring Framework 7; the spec pins Spring Boot 3.x (Spring 6).
- **Structured logs use Spring Boot's built-in logstash JSON format** (`logging.structured.format.console`), which includes MDC; no extra encoder library. Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=` (empty) for plain text locally.
- **Resilience4j core rate limiter only.** The Spring Boot starter adds nothing we use in v1; circuit breakers are phase 7.
- **Extra library `libs/service-support`.** Holds the Kafka error handler, dead-letter topics, topic creation, the synchronous publisher and the shared client defaults, so seven services do not copy them.
- **Per-service config file names** (`invoice-handler.yml`, not `application.yml`). Every service sets `spring.config.name` in `main`, so all services can run in one JVM for the scenario tests without their config files colliding. Keys are still `finkraft.*`.
- **Host ports in docker-compose start at 18080 / 19001 / 15432** and are overridable by env vars, because 8080 and 5432 are commonly taken on developer machines.

## Data model

- **`invoices.poll_count`** added to drive "first at 30s, doubling to 15 min". It resets whenever the status moves.
- **`invoices.tenant_id` and `seller_id` reference `tenants` / `sellers`.** Foreign keys across ownership boundaries are read-only for the referencing side.
- **`asp_configs` primary key is (seller_id, effective_from).** The active row is the newest enabled one with `effective_from <= now()`.
- **`unmatched_asp_status`** keeps the full `asp.status` payload plus `matched_at`, `invoice_id`, `alerted_at`, `attempts`. Rows are marked, not deleted, so there is evidence of every parked status.
- **`audit_log` is also protected by a trigger** that rejects UPDATE, DELETE and TRUNCATE, so even the owner role cannot rewrite history.
- **Role passwords** in the migrations are Flyway placeholders with dev defaults; every deployed environment must override them.

## Canonical model and hashing

- **The invoice number is excluded from the content hash.** The duplicate rule's "new number, same hash" row is only reachable if the hash ignores the number.
- **Decimals are normalised to at least two decimal places without rounding** ("100" -> "100.00", "0.125" stays "0.125"). Rounding to scale 2 would give two different unit prices the same hash.
- **`vatRate` is a percentage** (5.00 for the UAE standard rate), as in PINT AE; `lineVat = lineNet x vatRate / 100`.

## Invoice handler

- **Producer-observed state (`expectedState`) on timer and submit-outcome events.** LEASE_EXPIRED, UNKNOWN_DEADLINE_PASSED, POLL_DISPATCHED, SUBMIT_TIMEOUT and SUBMIT_RETRYABLE carry the state the producer saw. If the invoice has already moved on, the event is recorded as a stale no-op (dedup key stored, audit row written) instead of being dead-lettered as illegal. Events without `expectedState` that are illegal still go to the DLT (S18).
- **Rank no-ops and stale events get an audit row** with `from_state = to_state` and an `outcome` field in `raw_payload`. Repeated identical statuses share a dedup key, so this does not grow without bound.
- **Implied states** of a ranked skip are recorded as `impliedStates` in the transition's `audit_log.raw_payload`.
- **READY_TO_SUBMIT from VALIDATION_PASSED also sets `next_attempt_at = now + lease`.** If `submission.requested` is never acted on (consumer failure, DLT), the Scheduler sends `retry.due`; the claim makes the duplicate harmless.
- **No active `asp_configs` row at VALIDATION_PASSED** fails the event (retried, then dead-lettered); the invoice stays VALIDATING. Routing must be configured before invoices arrive.
- **Revisions keep the invoice number.** A revision with a different number is rejected with 422.
- **Fix suggestions for an older revision are stored as DISMISSED**, and uploading a revision dismisses every other pending suggestion of that invoice.
- **OPERATOR_RESOLVED** may target any state except NEEDS_REVIEW and SUBMITTING. There is no operator endpoint in v1 (the ops console is phase 7).

## Adapters

- **Other HTTP 4xx on submit:** 401/403 are RETRYABLE (credentials problem, not the invoice's fault; ends in NEEDS_REVIEW after max attempts); any other 4xx is REJECTED with an `HTTP_<status>` error, so the customer can correct and resubmit.
- **ASP A status reads use Finkraft's own API key** (`finkraft.asp.a.api-key`), because `getStatus(aspRef)` carries no credentials. Submits use the seller's key from `asp_credentials`.
- **ASP A's submit response VALIDATED** gives outcome RECEIVED (spec) and the poll then picks up VALIDATED.
- **ASP B's 202 is recorded with rawStatus `QUEUED`**, the same status its first webhook carries.
- **Wire formats of the mocks** (field names, webhook body `{"events":[{job_id, client_reference, status, occurred_at, errors}]}`) are our invention, kept in one place per ASP (adapter plus mock).
- **`asp.status` carries an optional `errors` list** already converted by the adapter, so the status mapper never parses ASP error shapes (R6).

## Integration, polling and scheduling

- **Rate limiter hands out one permit per 1/rps seconds** instead of rps permits per second, so no one-second window sees a burst above the rate. It is in-process per worker; two workers each get the full rate until the Redis-backed limiter (phase 7). S10 gives its second worker 1 rps for that reason.
- **Poll dispatch is budgeted:** the Scheduler sends at most `finkraft.scheduler.max-polls-per-second` (default 5, under the 8 rps limiter) poll.due per tick, oldest due first. Without it, every due invoice gets a poll.due every tick and the limiter drops most of them at random, so some invoices go unpolled for long stretches.
- **Integration drops poll.due older than `finkraft.integration.poll-max-age`** (30 s); a newer one is already queued.
- **Polls skipped for lack of a permit, or failed reads, are not errors**: the next poll.due retries.

## Open question A9: ASP A reports REPORTED before Finkraft saw DELIVERED

ASP A is polled and reports only its current status. If a poll first sees REPORTED, the rank rule moves the invoice to REPORTED (spec), but ASP A never reports BUYER_DELIVERED afterwards, so the invoice never reaches COMPLETED and is polled for ever. With the production poll schedule (up to 15 min apart) this will happen whenever ASP A goes DELIVERED to REPORTED within one poll interval. Built as specified; scenarios keep each mock status visible longer than the poll interval. Options for a decision:
1. Treat ASP A's REPORTED as implying delivery (ASP A's lifecycle is linear), so it completes the invoice.
2. Ask ASP A for a status history and replay missed statuses in order.
3. Leave it, and alert or move to NEEDS_REVIEW after N unchanged polls in REPORTED.

## API documentation

- **springdoc-openapi 2.10.0** (the 2.x line built for Spring Boot 3.5) serves Swagger UI on management-service, invoice-handler and webhook-handler. The libraries carry no Swagger annotations; descriptions and examples live on the controllers.
- **The specs in `docs/api` are generated, not hand-written:** `ApiDocsTest` fetches `/v3/api-docs` from the running services and writes them (without the random test server URL).
