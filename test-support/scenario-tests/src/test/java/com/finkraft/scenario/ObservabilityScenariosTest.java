package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.HANDLER;
import static com.finkraft.scenario.FinkraftStack.INTEGRATION;
import static com.finkraft.scenario.FinkraftStack.STATUS_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.adapters.AspCatalog;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 6: the operational metrics are visible on the Actuator endpoints. */
class ObservabilityScenariosTest extends ScenarioTest {

    @Test
    void metricsAreExposedOnActuator() {
        Tenant t = newTenant(AspCatalog.ASP_B);
        UUID id = uploadOk(t, validInvoice(t, number()));
        awaitState(id, "COMPLETED");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            JsonNode completed = Api.get(stack.url(HANDLER) + "/actuator/metrics/finkraft.invoices?tag=state:COMPLETED").body();
            assertThat(completed.at("/measurements/0/value").asDouble()).isGreaterThanOrEqualTo(1);
        });
        assertThat(Api.get(stack.url(HANDLER) + "/actuator/metrics/finkraft.invoices.oldest.age").status()).isEqualTo(200);
        assertThat(Api.get(stack.url(HANDLER) + "/actuator/metrics/finkraft.outbox.lag").status()).isEqualTo(200);
        assertThat(Api.get(stack.url(HANDLER) + "/actuator/metrics/finkraft.outbox.unpublished").status()).isEqualTo(200);

        JsonNode asp = Api.get(stack.url(INTEGRATION) + "/actuator/metrics/finkraft.asp.calls").body();
        assertThat(asp.findValuesAsText("tag")).contains("asp", "outcome", "operation");

        assertThat(Api.get(stack.url(HANDLER) + "/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max").status())
                .as("consumer lag").isEqualTo(200);
        assertThat(Api.get(stack.url(STATUS_MAPPER) + "/actuator/metrics/finkraft.unmatched.open").status()).isEqualTo(200);

        String prometheus = Api.get(stack.url(HANDLER) + "/actuator/prometheus").raw();
        assertThat(prometheus).contains("finkraft_invoices{").contains("finkraft_outbox_lag_seconds").contains("service=\"invoice-handler\"");
        assertThat(Api.get(stack.url(HANDLER) + "/actuator/health").body().get("status").asText()).isEqualTo("UP");
    }
}
