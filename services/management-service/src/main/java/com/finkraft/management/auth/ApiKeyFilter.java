package com.finkraft.management.auth;

import com.finkraft.management.ManagementProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * v1 authentication: X-Api-Key resolves to a tenant by hash; admin endpoints take the operator's
 * X-Admin-Key. Controllers read the tenant from the request attribute and filter every query by it.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "finkraft.tenantId";

    private final JdbcTemplate jdbc;
    private final ManagementProperties props;

    public ApiKeyFilter(JdbcTemplate jdbc, ManagementProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/admin/")) {
            if (!ApiKeys.constantTimeEquals(props.admin().apiKey(), request.getHeader("X-Admin-Key"))) {
                unauthorized(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader("X-Api-Key");
        Optional<UUID> tenant = key == null ? Optional.empty() : Optional.ofNullable(jdbc.query(
                "SELECT id FROM tenants WHERE api_key_hash = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                ApiKeys.hash(key)));
        if (tenant.isEmpty()) {
            unauthorized(response);
            return;
        }
        request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
    }
}
