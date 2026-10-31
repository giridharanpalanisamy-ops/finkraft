package com.finkraft.management.admin;

import com.finkraft.adapters.AspCatalog;
import com.finkraft.management.auth.ApiKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.finkraft.management.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD on the management-owned tables. A routing change affects only invoices not yet
 * READY_TO_SUBMIT, because the InvoiceHandler pins the ASP at that transition.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
@SecurityRequirement(name = OpenApiConfig.ADMIN_KEY)
@ApiResponse(responseCode = "401", description = "Missing or wrong X-Admin-Key", content = @Content)
public class AdminController {

    public record NewTenant(@NotBlank String name) {}

    public record CreatedTenant(UUID id, String name,
            @Schema(description = "The tenant's API key. Shown only here; Finkraft stores its SHA-256 hash.") String apiKey) {}

    public record Tenant(UUID id, String name, Instant createdAt) {}

    public record NewSeller(@NotNull UUID tenantId, @NotBlank @Pattern(regexp = "\\d{15}") String trn, @NotBlank String name) {}

    public record Seller(UUID id, UUID tenantId, String trn, String name) {}

    /** effectiveFrom defaults to now; secretRef names the credential in the secret store. */
    public record NewAspConfig(
            @NotNull UUID sellerId,
            @Schema(description = "asp-a or asp-b", example = "asp-b") @NotBlank String aspId,
            @Schema(description = "When the route takes effect; defaults to now") Instant effectiveFrom,
            @Schema(description = "Defaults to true") Boolean enabled,
            @Schema(description = "Name of the seller's ASP credential in the secret store, never the secret itself",
                    example = "dev-asp-b") String secretRef) {}

    public record AspConfig(UUID sellerId, String aspId, Instant effectiveFrom, boolean enabled) {}

    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Create a tenant", description = "Returns the tenant's API key once.")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/tenants")
    public ResponseEntity<CreatedTenant> createTenant(@Valid @RequestBody NewTenant body) {
        UUID id = UUID.randomUUID();
        String apiKey = ApiKeys.generate();
        jdbc.update("INSERT INTO tenants (id, name, api_key_hash) VALUES (?, ?, ?)", id, body.name(), ApiKeys.hash(apiKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedTenant(id, body.name(), apiKey));
    }

    @Operation(summary = "List tenants")
    @GetMapping("/tenants")
    public List<Tenant> tenants() {
        return jdbc.query("SELECT id, name, created_at FROM tenants ORDER BY created_at",
                (rs, i) -> new Tenant(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()));
    }

    @Operation(summary = "Create a seller", description = "A seller is a legal entity with a 15-digit UAE TRN; one tenant can have several.")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/sellers")
    public ResponseEntity<Seller> createSeller(@Valid @RequestBody NewSeller body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sellers (id, tenant_id, trn, name) VALUES (?, ?, ?, ?)", id, body.tenantId(), body.trn(), body.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Seller(id, body.tenantId(), body.trn(), body.name()));
    }

    @Operation(summary = "List a tenant's sellers")
    @GetMapping("/sellers")
    public List<Seller> sellers(@RequestParam UUID tenantId) {
        return jdbc.query("SELECT id, tenant_id, trn, name FROM sellers WHERE tenant_id = ? ORDER BY trn",
                (rs, i) -> new Seller(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)),
                tenantId);
    }

    @Operation(summary = "Route a seller to an ASP",
            description = """
                    Adds a routing row and, with secretRef, the seller's credential reference. The newest enabled                     row already in effect is the active route. It is pinned to an invoice when the invoice becomes                     READY_TO_SUBMIT, so a routing change only affects invoices not yet READY_TO_SUBMIT.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "422", description = "UNKNOWN_ASP")
    @PostMapping("/asp-configs")
    @Transactional
    public ResponseEntity<?> createAspConfig(@Valid @RequestBody NewAspConfig body) {
        if (!AspCatalog.isKnown(body.aspId())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "UNKNOWN_ASP", "aspId", body.aspId()));
        }
        Instant from = body.effectiveFrom() != null ? body.effectiveFrom() : Instant.now();
        boolean enabled = body.enabled() == null || body.enabled();
        jdbc.update("INSERT INTO asp_configs (seller_id, asp_id, effective_from, enabled) VALUES (?, ?, ?, ?)",
                body.sellerId(), body.aspId(), Timestamp.from(from), enabled);
        if (body.secretRef() != null) {
            jdbc.update("""
                    INSERT INTO asp_credentials (seller_id, asp_id, secret_ref) VALUES (?, ?, ?)
                    ON CONFLICT (seller_id, asp_id) DO UPDATE SET secret_ref = EXCLUDED.secret_ref""",
                    body.sellerId(), body.aspId(), body.secretRef());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new AspConfig(body.sellerId(), body.aspId(), from, enabled));
    }

    @Operation(summary = "List a seller's routing rows")
    @GetMapping("/asp-configs")
    public List<AspConfig> aspConfigs(@RequestParam UUID sellerId) {
        return jdbc.query("SELECT seller_id, asp_id, effective_from, enabled FROM asp_configs WHERE seller_id = ? ORDER BY effective_from",
                (rs, i) -> new AspConfig(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getBoolean(4)),
                sellerId);
    }
}
