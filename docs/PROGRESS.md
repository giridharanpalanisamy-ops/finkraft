# Progress

## Phase 0: Scaffold (done)

- Built: Gradle 9.8.2 multi-module build (Kotlin DSL, version catalog, Java 21 toolchain); Docker Compose with Postgres 16, Kafka 3.9.2 (KRaft) and all services plus both mocks; one Dockerfile for every service; GitHub Actions running `./gradlew build`.
- Verified: build green; `docker compose up --wait` brings all 12 containers to healthy; Flyway runs; topics are created with 12 partitions.
- Skipped: nothing.

## Phase 1: Domain libraries (done)

- Built: `canonical-model` (invoice, states, events, canonicaliser, content hash, duplicate rule), `state-machine` (table-driven, rank rule, implied states), `event-contracts` (envelope, topics, payloads, dedup keys, codec, internal API contracts), `adapter-api`.
- Verified: 224 unit tests: every (state, event) pair, rank skips and no-ops, hash stability (key order, whitespace, "100" vs "100.00"), duplicate decision table.
- Skipped: nothing.

## Phase 2: InvoiceHandler (done)

- Built: Flyway schema with per-service roles and grants (V1 roles, V2 tables); internal create, revision and claim endpoints; `invoice.events` and `fix.suggested` consumers with dedup via `processed_events`; audit log; outbox relay (500 ms, 100 rows, `FOR UPDATE SKIP LOCKED`, stops at the first failed send); consumer error handler with `<topic>.DLT` dead-letter topics.
- Verified (Testcontainers Postgres + Kafka): claim concurrency (20 races, exactly one winner each), outbox publishes every committed row and none from a rolled-back transaction, a repeated dedup key applies once, an illegal event changes nothing and is dead-lettered, a stale timer is a recorded no-op, `fix.suggested` never transitions (R7), T-R3 for all seven non-owner roles, audit log insert-only even for the owner.
- Skipped: nothing.

## Phase 3: Intake and validation (done)

- Built: management-service public API (single and bulk upload as JSON array or CSV, reads, fix suggestions, revisions), X-Api-Key tenant auth with hashed keys, admin CRUD for tenants, sellers and ASP configs (X-Admin-Key); validator-service with rules FK-R001 to FK-R008.
- Verified: 11 validator unit tests (one per rule, determinism, HALF_UP edges, UAE date). Scenario harness that runs every service and both mocks in one JVM, each with its own DB role. S13 passes; S14 passes up to VALIDATION_FAILED and a revision reaching READY_TO_SUBMIT; bulk per-row results; tenant scoping.
- Skipped: nothing.

## Phase 4: ASP A path (done)

- Built: `AspAAdapter` and `AspBAdapter` (only code that knows ASP formats); mock-asp-a (10 rps sliding window with 429, status schedule, faults scoped per invoice, stats); integration-service (claim, shared rate limiter, ASP A timeout resends, never resend ASP B, polls); status-mapper (client_ref then asp_ref resolution, unmatched parking and re-matching job, DLT for unmapped statuses); scheduler-service (ShedLock; retry, lease, deadline and poll timers; ASP B SLA alerts).
- Verified: 29 adapter tests against WireMock (every outcome and status mapping, signatures). Scenarios S1, S3, S8, S9, S10, S11, S12, S18 pass.
- Skipped: nothing. Open question raised: ASP A REPORTED observed before DELIVERED (see ASSUMPTIONS, "Open question A9").
- Note: run the scenario suite with the machine kept awake (`caffeinate -i ./gradlew build` on macOS); a laptop sleep mid-run stalls every timer.

## Phase 5: ASP B path (done)

- Built: mock-asp-b (202 with job id, no dedup, signed webhooks with retries on 5xx; timeout-after-accept, delay, reorder, duplicate, drop, early webhook, FAILED after ACCEPTED); webhook-handler (signature check, publish and wait for the ack, 401 / 503); unmatched-status matching and reconcile deadline were built in phase 4 and are exercised here.
- Verified: S2, S4, S5, S6, S7, S15, S16, S17, S19 and the full S14 (revision through to COMPLETED) pass.
- Skipped: nothing.

## Phase 6: Release readiness (done)

- Built: structured JSON logs (Spring Boot logstash format) with `invoice_id` from the MDC; metrics `finkraft.invoices{state}`, `finkraft.invoices.oldest.age{state}`, `finkraft.outbox.unpublished`, `finkraft.outbox.lag`, `finkraft.asp.calls{asp,operation,outcome}`, `finkraft.unmatched.open`, `finkraft.sla.breaches`, Kafka consumer lag; Prometheus endpoint; README with run and test instructions; demo timings in docker-compose.
- Verified: `./gradlew clean build` green with 305 tests (all 19 scenarios included); metrics visible on Actuator (scenario test); JSON log lines carry invoice_id (integration test); `docker compose up` walk-through takes an ASP A and an ASP B invoice to COMPLETED.
- Skipped: nothing.

## Next

- Phase 7 (production hardening) and phase 8 (validation agent) are not started; `validation-agent` exists as an empty app with `agent.enabled=false`.
- Decision needed on open question A9 (ASP A REPORTED before DELIVERED) before going live with ASP A.
