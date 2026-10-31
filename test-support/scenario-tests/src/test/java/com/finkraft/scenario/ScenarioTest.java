package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.ADMIN_KEY;
import static com.finkraft.scenario.FinkraftStack.MANAGEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared fixtures: a fresh tenant and seller per test, valid invoices, state and audit helpers. */
public abstract class ScenarioTest {

    protected static final FinkraftStack stack = FinkraftStack.get();
    protected static final Duration TIMEOUT = Duration.ofSeconds(60);

    protected record Tenant(UUID tenantId, String apiKey, UUID sellerId, String trn) {}

    protected static Tenant newTenant(String aspId) {
        String mgmt = stack.url(MANAGEMENT);
        Api.Response t = Api.post(mgmt + "/api/v1/admin/tenants", Map.of("name", "tenant-" + UUID.randomUUID()), "X-Admin-Key", ADMIN_KEY);
        assertThat(t.status()).isEqualTo(201);
        UUID tenantId = UUID.fromString(t.body().get("id").asText());
        String trn = "1" + String.format("%014d", ThreadLocalRandom.current().nextLong(100_000_000_000_000L));
        Api.Response s = Api.post(mgmt + "/api/v1/admin/sellers", Map.of("tenantId", tenantId, "trn", trn, "name", "Seller"),
                "X-Admin-Key", ADMIN_KEY);
        assertThat(s.status()).isEqualTo(201);
        UUID sellerId = UUID.fromString(s.body().get("id").asText());
        Api.Response c = Api.post(mgmt + "/api/v1/admin/asp-configs",
                Map.of("sellerId", sellerId, "aspId", aspId, "effectiveFrom", "2020-01-01T00:00:00Z", "secretRef", "dev-" + aspId),
                "X-Admin-Key", ADMIN_KEY);
        assertThat(c.status()).isEqualTo(201);
        return new Tenant(tenantId, t.body().get("apiKey").asText(), sellerId, trn);
    }

    /** A valid invoice: one standard-rated line with a random price. */
    protected static CanonicalInvoice validInvoice(Tenant tenant, String number) {
        BigDecimal price = BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(100, 100_000), 2);
        return invoiceWithPrice(tenant, number, price);
    }

    protected static CanonicalInvoice invoiceWithPrice(Tenant tenant, String number, BigDecimal price) {
        BigDecimal qty = new BigDecimal("2");
        BigDecimal net = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = net.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
        return new CanonicalInvoice(tenant.trn(), "100000000000011", number, LocalDate.of(2026, 10, 1), "AED",
                List.of(new InvoiceLine("Consulting", qty, price, "S", new BigDecimal("5"), net, vat)),
                new InvoiceTotals(net, vat, net.add(vat)));
    }

    protected static String number() {
        return "INV-" + UUID.randomUUID().toString().substring(0, 13);
    }

    protected static Api.Response upload(Tenant tenant, Object invoice) {
        return Api.post(stack.url(MANAGEMENT) + "/api/v1/invoices", invoice, "X-Api-Key", tenant.apiKey());
    }

    protected static UUID uploadOk(Tenant tenant, CanonicalInvoice invoice) {
        Api.Response r = upload(tenant, invoice);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return UUID.fromString(r.body().get("invoiceId").asText());
    }

    protected static JdbcTemplate db() {
        return stack.db();
    }

    protected static String state(UUID invoiceId) {
        return db().queryForObject("SELECT state FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    protected static void awaitState(UUID invoiceId, String expected) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(state(invoiceId)).isEqualTo(expected));
    }

    /** The to_state of every audit row, in order: the invoice's full path. */
    protected static List<String> path(UUID invoiceId) {
        return db().queryForList("SELECT to_state FROM audit_log WHERE invoice_id = ? AND (from_state IS DISTINCT FROM to_state) ORDER BY id",
                String.class, invoiceId);
    }

    protected static List<Map<String, Object>> audit(UUID invoiceId) {
        return db().queryForList("SELECT from_state, to_state, event_type, dedup_key, actor, raw_payload::text AS raw FROM audit_log WHERE invoice_id = ? ORDER BY id",
                invoiceId);
    }
}
