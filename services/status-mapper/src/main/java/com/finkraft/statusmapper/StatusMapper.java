package com.finkraft.statusmapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.finkraft.adapter.UnmappedStatusException;
import com.finkraft.adapters.AspAdapters;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.events.DedupKeys;
import com.finkraft.events.PoisonEventException;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.AspStatus;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.support.EventPublisher;
import com.finkraft.support.Mdc;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Converts a raw ASP status into a canonical event for one invoice (spec 9.5). Publishing to
 * invoice.events keyed by invoice_id restores per-invoice ordering before the InvoiceHandler.
 */
@Component
public class StatusMapper {

    private static final Logger log = LoggerFactory.getLogger(StatusMapper.class);

    private final AspAdapters adapters;
    private final InvoiceResolver resolver;
    private final EventPublisher publisher;
    private final JdbcTemplate jdbc;

    public StatusMapper(AspAdapters adapters, InvoiceResolver resolver, EventPublisher publisher, JdbcTemplate jdbc) {
        this.adapters = adapters;
        this.resolver = resolver;
        this.publisher = publisher;
        this.jdbc = jdbc;
    }

    /** Maps and publishes, or parks the status as unmatched. */
    public void handle(AspStatus status) {
        CanonicalEvent event = toEvent(status);
        Optional<InvoiceResolver.Match> match = resolver.resolve(status.aspId(), status.aspRef(), status.clientRef());
        if (match.isEmpty()) {
            park(status);
            return;
        }
        publish(status, event, match.get());
    }

    /** Throws PoisonEventException for an unknown ASP or status: dead-lettered, never guessed. */
    public CanonicalEvent toEvent(AspStatus status) {
        try {
            return adapters.get(status.aspId()).toEvent(status.rawStatus());
        } catch (UnmappedStatusException | IllegalArgumentException e) {
            throw new PoisonEventException(e.getMessage(), e);
        }
    }

    public void publish(AspStatus status, CanonicalEvent event, InvoiceResolver.Match match) {
        try (var ignored = Mdc.invoice(match.invoiceId())) {
            String ref = status.aspRef() != null ? status.aspRef() : status.clientRef();
            InvoiceEventData data = new InvoiceEventData(status.errors(), status.aspRef(), status.rawStatus(), status.rawBody(),
                    status.receivedAt(), null, null, null);
            publisher.publish(Topics.INVOICE_EVENTS, match.invoiceId().toString(), publisher.envelope(event.name(),
                    match.invoiceId(), match.tenantId(), DedupKeys.aspStatus(status.aspId(), ref, status.rawStatus()),
                    new InvoiceEvent(event, data)));
            log.info("{} {} {} -> {}", status.source(), status.aspId(), status.rawStatus(), event);
        }
    }

    private void park(AspStatus status) {
        try {
            jdbc.update("""
                    INSERT INTO unmatched_asp_status (asp_id, asp_ref, client_ref, raw_status, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)""",
                    status.aspId(), status.aspRef(), status.clientRef(), status.rawStatus(),
                    CanonicalJson.mapper().writeValueAsString(status));
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("Unserialisable status", e);
        }
        log.info("Parked unmatched {} status {} (ref {}, client_ref {})", status.aspId(), status.rawStatus(), status.aspRef(),
                status.clientRef());
    }
}
