package com.finkraft.handler.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description of the internal API; Swagger UI at /swagger-ui.html, spec at /v3/api-docs. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Finkraft InvoiceHandler Internal API",
                version = "v1",
                description = """
                        The only synchronous calls into the single writer of invoice data. Called only by \
                        management-service (create, revise) and integration-service (claim); never exposed publicly. \
                        Everything else reaches the InvoiceHandler through Kafka (invoice.events, fix.suggested)."""),
        tags = @Tag(name = "Internal", description = "Create, revise and claim invoices"))
public class OpenApiConfig {}
