package com.finkraft.webhook;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description of the ASP webhook endpoint; Swagger UI at /swagger-ui.html. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Finkraft Webhook API",
                version = "v1",
                description = "Status callbacks from ASP B. Called by ASP B only; signed with HMAC-SHA256."),
        tags = @Tag(name = "Webhooks", description = "ASP status callbacks"))
public class OpenApiConfig {}
