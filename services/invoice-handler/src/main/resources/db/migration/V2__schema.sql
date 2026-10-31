-- Schema (spec section 5). Ownership decides which role may write each table (R3).

-- ---------------------------------------------------------------------------------------------
-- Management tables (write: management)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenants (
    id           uuid PRIMARY KEY,
    name         text        NOT NULL,
    api_key_hash char(64)    NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sellers (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants (id),
    trn       varchar(15) NOT NULL,
    name      text NOT NULL,
    UNIQUE (tenant_id, trn)
);

CREATE TABLE asp_configs (
    seller_id      uuid        NOT NULL REFERENCES sellers (id),
    asp_id         text        NOT NULL,
    effective_from timestamptz NOT NULL,
    enabled        boolean     NOT NULL DEFAULT true,
    PRIMARY KEY (seller_id, effective_from)
);

CREATE TABLE asp_credentials (
    seller_id  uuid NOT NULL REFERENCES sellers (id),
    asp_id     text NOT NULL,
    secret_ref text NOT NULL,
    PRIMARY KEY (seller_id, asp_id)
);

-- ---------------------------------------------------------------------------------------------
-- Invoice-lifecycle tables (write: invoice_handler only)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE invoices (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenants (id),
    seller_id             uuid        NOT NULL REFERENCES sellers (id),
    invoice_number        text        NOT NULL,
    revision              int         NOT NULL,
    content_hash          char(64)    NOT NULL,
    canonical             jsonb       NOT NULL,
    state                 text        NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    asp_id                text,
    asp_ref               text,
    client_ref            uuid,
    attempt_count         int         NOT NULL DEFAULT 0,
    poll_count            int         NOT NULL DEFAULT 0,
    next_attempt_at       timestamptz,
    lease_expires_at      timestamptz,
    deadline_at           timestamptz,
    delivered_at          timestamptz,
    reported_at           timestamptz,
    possible_duplicate_of uuid REFERENCES invoices (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoices_seller_number UNIQUE (seller_id, invoice_number),
    CONSTRAINT ck_invoices_state CHECK (state IN (
        'RECEIVED', 'VALIDATING', 'VALIDATION_FAILED', 'READY_TO_SUBMIT', 'SUBMITTING',
        'SUBMISSION_PENDING', 'SUBMISSION_UNKNOWN', 'ACCEPTED_BY_ASP', 'REJECTED', 'DELIVERED',
        'REPORTED', 'COMPLETED', 'NEEDS_REVIEW'))
);
CREATE INDEX ix_invoices_seller_hash ON invoices (seller_id, content_hash);
CREATE INDEX ix_invoices_state_next_attempt ON invoices (state, next_attempt_at);
CREATE INDEX ix_invoices_state_lease ON invoices (state, lease_expires_at);
CREATE INDEX ix_invoices_state_deadline ON invoices (state, deadline_at);
CREATE INDEX ix_invoices_asp_ref ON invoices (asp_id, asp_ref);
CREATE INDEX ix_invoices_client_ref ON invoices (client_ref);
CREATE INDEX ix_invoices_tenant_state ON invoices (tenant_id, state);

CREATE TABLE invoice_revisions (
    invoice_id   uuid        NOT NULL REFERENCES invoices (id),
    revision     int         NOT NULL,
    content_hash char(64)    NOT NULL,
    canonical    jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (invoice_id, revision)
);

CREATE TABLE outbox (
    id           bigserial PRIMARY KEY,
    invoice_id   uuid        NOT NULL,
    topic        text        NOT NULL,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE audit_log (
    id          bigserial PRIMARY KEY,
    invoice_id  uuid        NOT NULL,
    from_state  text,
    to_state    text,
    event_type  text        NOT NULL,
    dedup_key   text,
    actor       text        NOT NULL,
    raw_payload jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_invoice ON audit_log (invoice_id, id);

-- Insert-only even for the owner: no grant gives UPDATE or DELETE, and this trigger refuses them.
CREATE FUNCTION audit_log_insert_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is insert-only';
END
$$;
CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_insert_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_insert_only();

CREATE TABLE processed_events (
    dedup_key    text PRIMARY KEY,
    invoice_id   uuid,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE fix_suggestions (
    id         uuid PRIMARY KEY,
    invoice_id uuid        NOT NULL REFERENCES invoices (id),
    revision   int         NOT NULL,
    suggestion jsonb       NOT NULL,
    status     text        NOT NULL CHECK (status IN ('PENDING_CUSTOMER', 'APPROVED', 'DISMISSED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_fix_suggestions_invoice ON fix_suggestions (invoice_id, status);

-- ---------------------------------------------------------------------------------------------
-- Service-owned support tables
-- ---------------------------------------------------------------------------------------------
CREATE TABLE unmatched_asp_status (
    id          bigserial PRIMARY KEY,
    asp_id      text        NOT NULL,
    asp_ref     text,
    client_ref  text,
    raw_status  text        NOT NULL,
    payload     jsonb       NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    attempts    int         NOT NULL DEFAULT 0,
    matched_at  timestamptz,
    invoice_id  uuid,
    alerted_at  timestamptz
);
CREATE INDEX ix_unmatched_open ON unmatched_asp_status (id) WHERE matched_at IS NULL AND alerted_at IS NULL;

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- Grants. Everyone reads lifecycle and management tables; writes follow ownership.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT ON tenants, sellers, asp_configs, asp_credentials,
                invoices, invoice_revisions, outbox, audit_log, processed_events, fix_suggestions
    TO invoice_handler, management, validator, integration, status_mapper, webhook_handler,
       scheduler, validation_agent;

GRANT INSERT, UPDATE ON invoices, invoice_revisions, outbox, fix_suggestions TO invoice_handler;
GRANT INSERT ON audit_log, processed_events TO invoice_handler;
GRANT USAGE ON SEQUENCE outbox_id_seq, audit_log_id_seq TO invoice_handler;

GRANT INSERT, UPDATE, DELETE ON tenants, sellers, asp_configs, asp_credentials TO management;

GRANT SELECT, INSERT, UPDATE ON unmatched_asp_status TO status_mapper;
GRANT USAGE ON SEQUENCE unmatched_asp_status_id_seq TO status_mapper;

GRANT SELECT, INSERT, UPDATE, DELETE ON shedlock TO scheduler;
