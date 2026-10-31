package org.merchivra.erp.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class LocalUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public LocalUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        // Database-backed local identity: a disabled user or one with no active
        // tenant-wide OWNER membership cannot establish a usable new session.
        return jdbc.query("""
            SELECT u.id, c.password_hash
            FROM local_credential c
            JOIN app_user u ON u.id = c.user_id
            WHERE c.login_name = ? AND u.status = 'ACTIVE'
              AND EXISTS (
                SELECT 1 FROM membership m
                JOIN role_binding rb ON rb.tenant_id = m.tenant_id
                  AND rb.user_id = m.user_id
                WHERE m.user_id = u.id AND m.status = 'ACTIVE'
                  AND ((rb.role_code = 'OWNER' AND rb.seller_account_id IS NULL)
                    OR (rb.role_code='OPERATOR_READ' AND rb.seller_account_id IS NOT NULL
                       AND EXISTS (SELECT 1 FROM seller_account a WHERE a.tenant_id=rb.tenant_id
                           AND a.id=rb.seller_account_id))))
            """, (rs, row) -> User.withUsername(rs.getObject("id", UUID.class).toString())
                .password(rs.getString("password_hash"))
                .authorities("ROLE_USER")
                .build(), login)
            .stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
