package com.finkraft.scheduler;

import com.finkraft.adapter.StatusMode;
import com.finkraft.adapters.AspCatalog;
import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.canonical.InvoiceState;
import com.finkraft.events.DedupKeys;
import com.finkraft.events.Topics;
import com.finkraft.events.payload.InvoiceEvent;
import com.finkraft.events.payload.InvoiceEventData;
import com.finkraft.events.payload.PollDue;
import com.finkraft.events.payload.RetryDue;
import com.finkraft.support.EventPublisher;
import com.finkraft.support.Mdc;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns due timers into Kafka events (spec 9.7). Reads invoices, never writes them: the
 * InvoiceHandler moves the timers when it applies the events.
 */
@Component
public class TimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimerScheduler.class);

    private record Due(UUID id, UUID tenantId, String aspId, String aspRef, String state, Instant dueAt) {}

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final MeterRegistry meters;
    private final int batchSize;
    private final double maxPollsPerSecond;
    private final Duration interval;
    private final Duration aspBAcceptedSla;
    private final Duration aspBReportedSla;
    private final AtomicLong pendingBreaches = new AtomicLong();
    private final AtomicLong unreportedBreaches = new AtomicLong();

    public TimerScheduler(JdbcTemplate jdbc, EventPublisher publisher, MeterRegistry meters,
            @Value("${finkraft.scheduler.batch-size}") int batchSize,
            @Value("${finkraft.scheduler.max-polls-per-second}") double maxPollsPerSecond,
            @Value("${finkraft.scheduler.interval}") Duration interval,
            @Value("${finkraft.sla.asp-b.accepted}") Duration aspBAcceptedSla,
            @Value("${finkraft.sla.asp-b.reported}") Duration aspBReportedSla) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.meters = meters;
        this.batchSize = batchSize;
        this.maxPollsPerSecond = maxPollsPerSecond;
        this.interval = interval;
        this.aspBAcceptedSla = aspBAcceptedSla;
        this.aspBReportedSla = aspBReportedSla;
        meters.gauge("finkraft.sla.breaches", Tags.of("sla", "asp-b-accepted"), pendingBreaches);
        meters.gauge("finkraft.sla.breaches", Tags.of("sla", "asp-b-reported"), unreportedBreaches);
    }

    @Scheduled(fixedDelayString = "${finkraft.scheduler.interval}")
    @SchedulerLock(name = "scheduler-tick", lockAtMostFor = "PT1M")
    public void tick() {
        retries();
        leases();
        deadlines();
        polls();
        webhookSlas();
    }

    void retries() {
        for (Due d : due("state = 'READY_TO_SUBMIT' AND next_attempt_at <= now()", "next_attempt_at")) {
            try (var ignored = Mdc.invoice(d.id())) {
                publisher.publish(Topics.RETRY_DUE, d.id().toString(), publisher.envelope("RetryDue", d.id(), d.tenantId(),
                        DedupKeys.timer(d.id(), "RETRY", d.dueAt()), new RetryDue(d.id())));
            }
        }
    }

    void leases() {
        for (Due d : due("state = 'SUBMITTING' AND lease_expires_at <= now()", "lease_expires_at")) {
            timerEvent(d, CanonicalEvent.LEASE_EXPIRED, "LEASE", InvoiceState.SUBMITTING);
        }
    }

    void deadlines() {
        for (Due d : due("state = 'SUBMISSION_UNKNOWN' AND deadline_at <= now()", "deadline_at")) {
            timerEvent(d, CanonicalEvent.UNKNOWN_DEADLINE_PASSED, "DEADLINE", InvoiceState.SUBMISSION_UNKNOWN);
        }
    }

    /**
     * Dispatches at most what the ASP can be polled at per tick, oldest due first. Invoices left
     * over keep their due time and go first next tick, so every invoice is polled in turn instead
     * of a flood of poll.due that the rate limiter would mostly drop.
     */
    void polls() {
        int perTick = Math.max(1, (int) Math.ceil(maxPollsPerSecond * interval.toMillis() / 1000.0));
        List<String> pollAsps = AspCatalog.aspIds(StatusMode.POLL);
        List<Due> due = jdbc.query("""
                SELECT id, tenant_id, asp_id, asp_ref, state, next_attempt_at FROM invoices
                WHERE state IN ('SUBMISSION_PENDING', 'ACCEPTED_BY_ASP', 'DELIVERED', 'REPORTED')
                  AND next_attempt_at <= now() AND asp_ref IS NOT NULL AND asp_id = ANY(?)
                ORDER BY next_attempt_at LIMIT ?""",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()),
                pollAsps.toArray(String[]::new), Math.min(perTick, batchSize));
        for (Due d : due) {
            try (var ignored = Mdc.invoice(d.id())) {
                publisher.publish(Topics.POLL_DUE, d.id().toString(), publisher.envelope("PollDue", d.id(), d.tenantId(),
                        DedupKeys.timer(d.id(), "POLL", d.dueAt()), new PollDue(d.id(), d.aspId(), d.aspRef())));
                // Same dedup key for every dispatch of this due time: the handler moves the timer once.
                timerEvent(d, CanonicalEvent.POLL_DISPATCHED, "POLL", InvoiceState.valueOf(d.state()));
            }
        }
    }

    /** WEBHOOK ASPs: metric and alert log only; no state change in v1. */
    void webhookSlas() {
        List<String> webhookAsps = AspCatalog.aspIds(StatusMode.WEBHOOK);
        Long pending = jdbc.queryForObject("""
                SELECT count(*) FROM invoices WHERE state = 'SUBMISSION_PENDING' AND asp_id = ANY(?)
                  AND updated_at < now() - (? * interval '1 millisecond')""",
                Long.class, webhookAsps.toArray(String[]::new), aspBAcceptedSla.toMillis());
        Long unreported = jdbc.queryForObject("""
                SELECT count(*) FROM invoices WHERE state IN ('SUBMISSION_PENDING', 'ACCEPTED_BY_ASP', 'DELIVERED')
                  AND asp_id = ANY(?) AND created_at < now() - (? * interval '1 millisecond')""",
                Long.class, webhookAsps.toArray(String[]::new), aspBReportedSla.toMillis());
        pendingBreaches.set(pending == null ? 0 : pending);
        unreportedBreaches.set(unreported == null ? 0 : unreported);
        if (pending != null && pending > 0) {
            log.warn("ALERT {} ASP B invoice(s) SUBMISSION_PENDING longer than {}", pending, aspBAcceptedSla);
        }
        if (unreported != null && unreported > 0) {
            log.warn("ALERT {} ASP B invoice(s) not reported within {}", unreported, aspBReportedSla);
        }
    }

    private void timerEvent(Due d, CanonicalEvent event, String timerType, InvoiceState observed) {
        try (var ignored = Mdc.invoice(d.id())) {
            InvoiceEventData data = InvoiceEventData.EMPTY.withExpectedState(observed);
            publisher.publish(Topics.INVOICE_EVENTS, d.id().toString(), publisher.envelope(event.name(), d.id(), d.tenantId(),
                    DedupKeys.timer(d.id(), timerType, d.dueAt()), new InvoiceEvent(event, data)));
        }
    }

    private List<Due> due(String condition, String dueColumn) {
        return jdbc.query("SELECT id, tenant_id, asp_id, asp_ref, state, " + dueColumn + " FROM invoices WHERE " + condition
                        + " ORDER BY " + dueColumn + " LIMIT ?",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, Timestamp.class).toInstant()),
                batchSize);
    }
}
