package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.HANDLER;
import static com.finkraft.scenario.FinkraftStack.MANAGEMENT;
import static com.finkraft.scenario.FinkraftStack.WEBHOOK;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Swagger UI and the OpenAPI specs are served, and the specs are exported to docs/api so the
 * documentation in the repository always matches the code.
 */
class ApiDocsTest extends ScenarioTest {

    private static final Map<String, String> SPECS = Map.of(
            MANAGEMENT, "public-api.openapi.json",
            HANDLER, "internal-api.openapi.json",
            WEBHOOK, "webhook-api.openapi.json");

    @Test
    void specsAreServedAndExported() throws Exception {
        Path out = Path.of(System.getProperty("finkraft.openapi.out", "build/openapi"));
        Files.createDirectories(out);
        ObjectMapper pretty = new ObjectMapper();
        for (Map.Entry<String, String> e : SPECS.entrySet()) {
            Api.Response spec = Api.get(stack.url(e.getKey()) + "/v3/api-docs");
            assertThat(spec.status()).isEqualTo(200);
            assertThat(Api.get(stack.url(e.getKey()) + "/swagger-ui/index.html").status()).isEqualTo(200);
            JsonNode json = spec.body();
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("servers"); // random test ports
            Files.writeString(out.resolve(e.getValue()), pretty.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
        }

        JsonNode pub = Api.get(stack.url(MANAGEMENT) + "/v3/api-docs").body();
        assertThat(pub.at("/info/title").asText()).isEqualTo("Finkraft Public API");
        assertThat(pub.at("/paths").fieldNames()).toIterable().contains(
                "/api/v1/invoices", "/api/v1/invoices/bulk", "/api/v1/invoices/{id}", "/api/v1/invoices/{id}/revisions",
                "/api/v1/invoices/{id}/fix-suggestions", "/api/v1/admin/tenants", "/api/v1/admin/sellers", "/api/v1/admin/asp-configs");
        assertThat(pub.at("/components/securitySchemes/apiKey/name").asText()).isEqualTo("X-Api-Key");
        assertThat(pub.at("/components/schemas/CanonicalInvoice")).isNotEmpty();
        // On invoice endpoints the tenant comes from the API key, never from the caller.
        pub.at("/paths").fields().forEachRemaining(path -> {
            if (path.getKey().startsWith("/api/v1/invoices")) {
                assertThat(path.getValue().findValuesAsText("name")).as(path.getKey()).doesNotContain("tenantId");
            }
        });

        JsonNode internal = Api.get(stack.url(HANDLER) + "/v3/api-docs").body();
        assertThat(internal.at("/paths").fieldNames()).toIterable()
                .contains("/internal/invoices", "/internal/invoices/{id}/revisions", "/internal/invoices/{id}/claim");
        JsonNode webhook = Api.get(stack.url(WEBHOOK) + "/v3/api-docs").body();
        assertThat(webhook.at("/paths").fieldNames()).toIterable().contains("/webhooks/asp-b");
    }
}
