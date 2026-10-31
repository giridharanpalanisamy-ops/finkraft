package com.finkraft.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.finkraft.canonical.CanonicalEvent;
import com.finkraft.events.internal.ClaimedInvoice;
import com.finkraft.handler.events.InvoiceEventService;
import com.finkraft.handler.invoice.InvoiceLifecycleService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** R2: two workers race for the same READY_TO_SUBMIT invoice; exactly one claims it. */
class ClaimConcurrencyIT extends HandlerIntegrationTest {

    @Autowired InvoiceLifecycleService lifecycle;
    @Autowired InvoiceEventService events;

    @Test
    void exactlyOneOfTwoConcurrentClaimsWins() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID id = readyToSubmit();
            long version = owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Optional<ClaimedInvoice>> claim = () -> {
                start.await();
                return lifecycle.claim(id, version, Thread.currentThread().getName());
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Optional<ClaimedInvoice>>> results = List.of(pool.submit(claim), pool.submit(claim));
                start.countDown();
                long winners = 0;
                for (Future<Optional<ClaimedInvoice>> f : results) {
                    winners += f.get().isPresent() ? 1 : 0;
                }
                assertThat(winners).as("round %d", round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(state(id)).isEqualTo("SUBMITTING");
            assertThat(owner().queryForObject("SELECT attempt_count FROM invoices WHERE id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(owner().queryForObject(
                    "SELECT count(*) FROM audit_log WHERE invoice_id = ? AND event_type = 'CLAIMED'", Integer.class, id)).isEqualTo(1);
        }
    }

    @Test
    void claimWithAStaleVersionOrInTheWrongStateIsNotClaimed() {
        UUID id = readyToSubmit();
        long version = owner().queryForObject("SELECT version FROM invoices WHERE id = ?", Long.class, id);
        assertThat(lifecycle.claim(id, version - 1, "w1")).isEmpty();
        Optional<ClaimedInvoice> claimed = lifecycle.claim(id, version, "w1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().aspId()).isEqualTo("asp-a");
        assertThat(claimed.get().clientRef()).isNotNull();
        assertThat(claimed.get().invoice().invoiceNumber()).isNotBlank();
        assertThat(lifecycle.claim(id, claimed.get().version(), "w2")).as("already SUBMITTING").isEmpty();
    }

    private UUID readyToSubmit() {
        Seller s = newSeller();
        UUID id = lifecycle.create(s.tenantId(), s.sellerId(), invoice(s.trn(), "INV-" + UUID.randomUUID(), "100")).invoiceId();
        events.apply(event(id, CanonicalEvent.VALIDATION_PASSED));
        assertThat(state(id)).isEqualTo("READY_TO_SUBMIT");
        return id;
    }
}
