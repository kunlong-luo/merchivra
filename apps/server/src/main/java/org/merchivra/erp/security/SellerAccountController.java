package org.merchivra.erp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** First real-structure read endpoint: scope derived from authenticated session. */
@RestController
@RequestMapping("/api/v1/seller-accounts")
public class SellerAccountController {
    private final JdbcTemplate jdbc;
    private final TenantAccessService tenants;

    public SellerAccountController(JdbcTemplate jdbc, TenantAccessService tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record SellerAccountView(String id, String displayName, String region, String connectionStatus) {}

    @GetMapping
    public List<SellerAccountView> list(Authentication authentication, HttpServletRequest request) {
        UUID tenant = tenants.currentTenant(authentication, request);
        return jdbc.query("""
            SELECT id, display_name, region, connection_status FROM seller_account
            WHERE tenant_id = ? ORDER BY id
            """, (rs, row) -> new SellerAccountView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("display_name"), rs.getString("region"), rs.getString("connection_status")), tenant);
    }
}
