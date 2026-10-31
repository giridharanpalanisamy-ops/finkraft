package com.finkraft.management;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description of the public API; Swagger UI at /swagger-ui.html, spec at /v3/api-docs. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Finkraft Public API",
                version = "v1",
                description = """
                        Upload UAE e-invoices, follow them through validation and ASP submission, and correct \
                        invoices that fail. Every invoice endpoint is scoped to the tenant that owns the API key.

                        Invoice states: RECEIVED, VALIDATING, VALIDATION_FAILED, READY_TO_SUBMIT, SUBMITTING, \
                        SUBMISSION_PENDING, SUBMISSION_UNKNOWN, ACCEPTED_BY_ASP, REJECTED, DELIVERED, REPORTED, \
                        COMPLETED, NEEDS_REVIEW."""),
        tags = {
            @Tag(name = "Invoices", description = "Upload, read and correct invoices (X-Api-Key)"),
            @Tag(name = "Admin", description = "Tenants, sellers and ASP routing (X-Admin-Key)")
        })
@SecurityScheme(name = OpenApiConfig.API_KEY, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Api-Key", description = "Tenant API key, shown once when the tenant is created")
@SecurityScheme(name = OpenApiConfig.ADMIN_KEY, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Admin-Key", description = "Operator key for the admin endpoints (ADMIN_API_KEY)")
public class OpenApiConfig {

    public static final String API_KEY = "apiKey";
    public static final String ADMIN_KEY = "adminKey";
}
