package org.merchivra.erp.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V15: explicit local OWNER invitations and separately granted reviewer role.
 * No self-service signup, default password, email delivery or general STAFF
 * privilege escalation. Tokens are never stored or logged in plaintext.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.team",name="enabled",havingValue="true")
public class LocalTeamService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final SecureRandom random=new SecureRandom();

    public LocalTeamService(JdbcTemplate jdbc,TransactionTemplate tx,PasswordEncoder encoder) {
        this.jdbc=jdbc;this.tx=tx;this.encoder=encoder;
    }

    public record Member(UUID userId,String loginName,String status,boolean reviewer,
                         String role,UUID sellerAccountId) {}
    public record Invitation(UUID id,String loginName,String status,String expiresAt,
                             String role,UUID sellerAccountId) {}
    public record Issued(UUID invitationId,String loginName,String token,String expiresAt) {}

    private void lockTenant(UUID tenant) {
        List<UUID> rows=jdbc.query("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private boolean owner(UUID tenant,UUID user) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM membership m
            JOIN app_user u ON u.id=m.user_id
            JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
            WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE'
              AND u.status='ACTIVE' AND rb.role_code='OWNER'
              AND rb.seller_account_id IS NULL
            """,Integer.class,tenant,user);
        return count!=null && count>0;
    }

    private void requireOwner(UUID tenant,UUID actor) {
        if(!owner(tenant,actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available");
        }
    }

    private void audit(UUID tenant,UUID actor,UUID subject,UUID invite,String event) {
        jdbc.update("""
            INSERT INTO local_team_audit
                (id,tenant_id,actor_user_id,subject_user_id,invitation_id,event_code)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,actor,subject,invite,event);
    }

    public List<Member> members(UUID tenant,UUID actor) {
        requireOwner(tenant,actor);
        return jdbc.query("""
            SELECT u.id,c.login_name,m.status,
               EXISTS (SELECT 1 FROM role_binding r
                 WHERE r.tenant_id=m.tenant_id AND r.user_id=m.user_id
                   AND r.role_code='SECURITY_REVIEWER'
                   AND r.seller_account_id IS NULL) reviewer,
               CASE WHEN EXISTS (SELECT 1 FROM role_binding rb
                 WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                   AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL)
                 THEN 'OWNER' ELSE 'OPERATOR_READ' END AS access_role,
               (SELECT rb.seller_account_id FROM role_binding rb
                 WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                   AND rb.role_code='OPERATOR_READ' LIMIT 1) AS assigned_account
            FROM membership m
            JOIN app_user u ON u.id=m.user_id
            JOIN local_credential c ON c.user_id=u.id
            WHERE m.tenant_id=?
            ORDER BY c.login_name
            """,(rs,n)->new Member(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getBoolean(4),rs.getString("access_role"),
                rs.getObject("assigned_account",UUID.class)),tenant);
    }

    public List<Invitation> invitations(UUID tenant,UUID actor) {
        requireOwner(tenant,actor);
        return jdbc.query("""
            SELECT id,login_name,status,expires_at,invite_role,seller_account_id
            FROM local_owner_invitation
            WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50
            """,(rs,n)->new Invitation(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getObject(4,OffsetDateTime.class).toInstant().toString(),
                rs.getString(5),rs.getObject(6,UUID.class)),tenant);
    }

    /** Token is returned only once in a no-store response. The inviter delivers it manually. */
    public Issued invite(UUID tenant,UUID actor,String login) {
        return invite(tenant,actor,login,"OWNER",null);
    }

    public Issued invite(UUID tenant,UUID actor,String login,String role,UUID sellerAccount) {
        if(!"OWNER".equals(role) && !"OPERATOR_READ".equals(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported invite role");
        if(("OWNER".equals(role) && sellerAccount!=null)
          || ("OPERATOR_READ".equals(role) && sellerAccount==null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid account role scope");
        if(login==null || !login.matches("[A-Za-z0-9._-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid login");
        byte[] secret=new byte[32];
        random.nextBytes(secret);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String digest=hash(token);
        return tx.execute(transaction->{
            lockTenant(tenant);
            requireOwner(tenant,actor);
            if("OPERATOR_READ".equals(role)) {
                Integer scoped=jdbc.queryForObject("""
                    SELECT count(*) FROM seller_account WHERE tenant_id=? AND id=?
                    """,Integer.class,tenant,sellerAccount);
                if(scoped==null || scoped!=1)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Seller scope unavailable");
            }
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM local_credential WHERE login_name=?
                """,Integer.class,login);
            if(existing!=null && existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Login unavailable");
            // Permit a new invitation after an earlier one expired, without
            // ever re-activating a revoked or already used token.
            jdbc.update("""
                UPDATE local_owner_invitation SET status='REVOKED'
                WHERE login_name=? AND status='PENDING' AND expires_at<=now()
                """,login);
            Integer pending=jdbc.queryForObject("""
                SELECT count(*) FROM local_owner_invitation
                WHERE login_name=? AND status='PENDING'
                """,Integer.class,login);
            if(pending!=null && pending>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Invitation pending");
            UUID id=UUID.randomUUID();
            Instant expiry=Instant.now().plusSeconds(24*3600);
            jdbc.update("""
                INSERT INTO local_owner_invitation
                  (id,tenant_id,invited_by,login_name,token_sha256,status,expires_at,invite_role,seller_account_id)
                VALUES (?,?,?,?,?,'PENDING',?,?,?)
                """,id,tenant,actor,login,digest,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC),
                role,sellerAccount);
            audit(tenant,actor,null,id,"INVITED");
            return new Issued(id,login,token,expiry.toString());
        });
    }

    public void revokeInvitation(UUID tenant,UUID actor,UUID invite) {
        tx.executeWithoutResult(transaction->{
            lockTenant(tenant);
            requireOwner(tenant,actor);
            int updated=jdbc.update("""
                UPDATE local_owner_invitation SET status='REVOKED'
                WHERE tenant_id=? AND id=? AND status='PENDING'
                """,tenant,invite);
            if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending invitation");
            audit(tenant,actor,null,invite,"INVITE_REVOKED");
        });
    }

    /** Anonymous CSRF-protected activation; user chooses their own password. */
    public void accept(String token,String password) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")
           || password==null || password.length()<16 || password.length()>128
           || password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid invitation or password");
        String digest=hash(token);
        tx.executeWithoutResult(transaction->{
            record Pending(UUID id,UUID tenant,UUID inviter,String login,
                           String role,UUID sellerAccount) {}
            List<Pending> matches=jdbc.query("""
                SELECT id,tenant_id,invited_by,login_name,invite_role,seller_account_id
                FROM local_owner_invitation
                WHERE token_sha256=? AND status='PENDING' AND expires_at>now()
                FOR UPDATE
                """,(rs,n)->new Pending(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),
                    rs.getString(5),rs.getObject(6,UUID.class)),digest);
            if(matches.size()!=1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid invitation or password");
            Pending invite=matches.getFirst();
            lockTenant(invite.tenant());
            requireOwner(invite.tenant(),invite.inviter());
            if("OPERATOR_READ".equals(invite.role())) {
                Integer scoped=jdbc.queryForObject("""
                    SELECT count(*) FROM seller_account WHERE tenant_id=? AND id=?
                    """,Integer.class,invite.tenant(),invite.sellerAccount());
                if(scoped==null || scoped!=1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Account scope unavailable");
            } else if(!"OWNER".equals(invite.role()) || invite.sellerAccount()!=null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Invitation role invalid");
            }
            Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM local_credential WHERE login_name=?
                """,Integer.class,invite.login());
            if(exists!=null && exists>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Login unavailable");
            UUID user=UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id,identity_ref) VALUES (?,?)",
                user,"local:"+invite.login());
            jdbc.update("INSERT INTO local_credential(user_id,login_name,password_hash) VALUES (?,?,?)",
                user,invite.login(),encoder.encode(password));
            jdbc.update("INSERT INTO membership(tenant_id,user_id) VALUES (?,?)",
                invite.tenant(),user);
            jdbc.update("""
                INSERT INTO role_binding(id,tenant_id,user_id,role_code,seller_account_id)
                VALUES (?,?,?,?,?)
                """,UUID.randomUUID(),invite.tenant(),user,
                invite.role(),invite.sellerAccount());
            jdbc.update("""
                UPDATE local_owner_invitation SET status='ACCEPTED',
                  consumed_at=now(),accepted_user_id=?
                WHERE tenant_id=? AND id=? AND status='PENDING'
                """,user,invite.tenant(),invite.id());
            audit(invite.tenant(),invite.inviter(),user,invite.id(),"INVITE_ACCEPTED");
        });
    }

    public void setReviewer(UUID tenant,UUID actor,UUID target,boolean enabled) {
        tx.executeWithoutResult(transaction->{
            lockTenant(tenant);
            requireOwner(tenant,actor);
            if(actor.equals(target) && enabled)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cannot self-grant reviewer role");
            if(!owner(tenant,target))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active Owner required");
            if(enabled) {
                jdbc.update("""
                    INSERT INTO role_binding(id,tenant_id,user_id,role_code,seller_account_id)
                    VALUES (?,?,?,'SECURITY_REVIEWER',NULL)
                    ON CONFLICT (tenant_id,user_id,role_code)
                      WHERE seller_account_id IS NULL DO NOTHING
                    """,UUID.randomUUID(),tenant,target);
            } else {
                jdbc.update("""
                    DELETE FROM role_binding WHERE tenant_id=? AND user_id=?
                    AND role_code='SECURITY_REVIEWER' AND seller_account_id IS NULL
                    """,tenant,target);
            }
            audit(tenant,actor,target,null,enabled?"REVIEWER_GRANTED":"REVIEWER_REVOKED");
        });
    }

    public void revokeOwner(UUID tenant,UUID actor,UUID target) {
        tx.executeWithoutResult(transaction->{
            lockTenant(tenant);
            requireOwner(tenant,actor);
            if(actor.equals(target))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot revoke yourself");
            Integer active=jdbc.queryForObject("""
                SELECT count(*) FROM membership WHERE tenant_id=? AND user_id=?
                  AND status='ACTIVE'
                """,Integer.class,tenant,target);
            if(active==null || active!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if(owner(tenant,target)) {
              Integer remaining=jdbc.queryForObject("""
                SELECT count(DISTINCT m.user_id) FROM membership m
                JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
                JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                WHERE m.tenant_id=? AND m.status='ACTIVE'
                  AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL
                """,Integer.class,tenant);
              if(remaining==null || remaining<2)
                  throw new ResponseStatusException(HttpStatus.CONFLICT,
                      "Last active Owner cannot be removed");
            }
            jdbc.update("""
                UPDATE membership SET status='REVOKED'
                WHERE tenant_id=? AND user_id=? AND status='ACTIVE'
                """,tenant,target);
            jdbc.update("""
                DELETE FROM role_binding WHERE tenant_id=? AND user_id=?
                  AND role_code='SECURITY_REVIEWER'
                """,tenant,target);
            audit(tenant,actor,target,null,"OWNER_REVOKED");
        });
    }
}
