package org.merchivra.erp.fba;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** V84: Owner-only, per-marketplace explicit exception capability restrictions.
 * Absence of a profile preserves prior Owner behavior. This does NOT grant
 * OPERATOR_READ any new access, nor scope any unrelated ERP endpoints.
 */
@Service
public class FbaExceptionPermissionService {
    private final JdbcTemplate jdbc;
    public FbaExceptionPermissionService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public enum Capability { VIEW,ASSIGN,REVIEW,RESOLVE,CONFIGURE,MANAGE }
    public record Grants(boolean view,boolean assign,boolean review,boolean resolve,
                         boolean configure,boolean manage) {
        boolean allowed(Capability capability) {
            return switch(capability){
                case VIEW -> view;
                case ASSIGN -> assign;
                case REVIEW -> review;
                case RESOLVE -> resolve;
                case CONFIGURE -> configure;
                case MANAGE -> manage;
            };
        }
        String encoded(){return (view?"1":"0")+(assign?"1":"0")+
            (review?"1":"0")+(resolve?"1":"0")+
            (configure?"1":"0")+(manage?"1":"0");}
    }
    private static final Grants FULL=new Grants(true,true,true,true,true,true);
    public record Profile(String ownerId,String displayName,Grants grants,
                          long version,boolean customized) {}
    public record Update(Grants grants,long expectedVersion,String reason) {}
    public record Audit(String ownerId,String actorId,long oldVersion,long newVersion,
                        String oldGrants,String newGrants,String reason,String occurredAt) {}

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Current Owner lacks required exception capability");
    }
    private void requireSite(UUID tenant,UUID marketplace) {
        int count=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,marketplace);
        if(count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private boolean isActiveOwner(UUID tenant,UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM membership m
              JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
              JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
              JOIN role_binding r ON r.tenant_id=m.tenant_id
                AND r.user_id=m.user_id AND r.role_code='OWNER'
                AND r.seller_account_id IS NULL
              WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE')
            """,Boolean.class,tenant,user));
    }
    private Grants read(UUID tenant,UUID marketplace,UUID owner) {
        var list=jdbc.query("""
            SELECT can_view,can_assign,can_review,can_resolve,can_configure,can_manage
            FROM fba_exception_owner_permission
            WHERE tenant_id=? AND seller_marketplace_id=? AND owner_user_id=?
            """,(r,i)->new Grants(r.getBoolean(1),r.getBoolean(2),
                r.getBoolean(3),r.getBoolean(4),r.getBoolean(5),r.getBoolean(6)),
            tenant,marketplace,owner);
        return list.isEmpty()?FULL:list.getFirst();
    }
    public void require(UUID tenant,UUID marketplace,UUID actor,Capability capability) {
        requireSite(tenant,marketplace);
        // VIEW is the parent gate of every exception mutation. Otherwise an
        // Owner could act on a guessed ID even after losing evidence visibility.
        if(!isActiveOwner(tenant,actor))throw denied();
        Grants grants=read(tenant,marketplace,actor);
        if(!grants.view() || !grants.allowed(capability))throw denied();
    }
    @Transactional(readOnly=true)
    public Profile mine(UUID tenant,UUID marketplace,UUID actor) {
        require(tenant,marketplace,actor,Capability.VIEW);
        return new Profile(actor.toString(),"",read(tenant,marketplace,actor),
            version(tenant,marketplace,actor),
            version(tenant,marketplace,actor)>0);
    }
    private long version(UUID tenant,UUID marketplace,UUID owner) {
        Long value=jdbc.queryForObject("""
            SELECT COALESCE((SELECT version FROM fba_exception_owner_permission
              WHERE tenant_id=? AND seller_marketplace_id=? AND owner_user_id=?),0)
            """,Long.class,tenant,marketplace,owner);
        return value==null?0:value;
    }
    @Transactional(readOnly=true)
    public List<Profile> owners(UUID tenant,UUID marketplace,UUID actor) {
        require(tenant,marketplace,actor,Capability.MANAGE);
        return jdbc.query("""
            SELECT u.id,u.identity_ref,p.version,
              p.can_view,p.can_assign,p.can_review,p.can_resolve,p.can_configure,p.can_manage
            FROM membership m
            JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
            JOIN role_binding r ON r.tenant_id=m.tenant_id
              AND r.user_id=m.user_id AND r.role_code='OWNER'
              AND r.seller_account_id IS NULL
            LEFT JOIN fba_exception_owner_permission p ON p.tenant_id=m.tenant_id
              AND p.seller_marketplace_id=? AND p.owner_user_id=m.user_id
            WHERE m.tenant_id=? AND m.status='ACTIVE'
            ORDER BY u.identity_ref,u.id LIMIT 100
            """,(r,i)->{
                long v=r.getLong("version");
                boolean customized=!r.wasNull();
                return new Profile(r.getObject("id",UUID.class).toString(),
                    r.getString("identity_ref"),
                    customized?new Grants(r.getBoolean("can_view"),
                        r.getBoolean("can_assign"),r.getBoolean("can_review"),
                        r.getBoolean("can_resolve"),r.getBoolean("can_configure"),
                        r.getBoolean("can_manage")):FULL,
                    customized?v:0,customized);
            },marketplace,tenant);
    }
    @Transactional
    public Profile save(UUID tenant,UUID marketplace,UUID actor,
                        UUID subject,Update input) {
        require(tenant,marketplace,actor,Capability.MANAGE);
        if(actor.equals(subject))throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,"Owner cannot modify their own access");
        if(!isActiveOwner(tenant,subject))throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"Target must be an active tenant Owner");
        if(input==null||input.grants()==null||input.expectedVersion()<0
           ||input.reason()==null||input.reason().strip().length()<10
           ||input.reason().strip().length()>300
           ||input.reason().chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Expected version and printable 10..300 character reason required");
        Grants grant=input.grants();
        if(grant.manage()&&(!grant.view()||!grant.configure()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Managing grants requires view and configuration grants");
        // Lock the marketplace to serialize concurrent first-time writes.
        jdbc.queryForObject("""
            SELECT id FROM seller_marketplace WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,marketplace);
        // Recheck under shared site lock to prevent concurrent mutual manager lockout.
        require(tenant,marketplace,actor,Capability.MANAGE);
        var existing=jdbc.query("""
            SELECT version,can_view,can_assign,can_review,can_resolve,
                   can_configure,can_manage FROM fba_exception_owner_permission
            WHERE tenant_id=? AND seller_marketplace_id=? AND owner_user_id=? FOR UPDATE
            """,(r,i)->new Object[]{r.getLong(1),new Grants(r.getBoolean(2),
                r.getBoolean(3),r.getBoolean(4),r.getBoolean(5),
                r.getBoolean(6),r.getBoolean(7))},tenant,marketplace,subject);
        long v=existing.isEmpty()?0:(Long)existing.getFirst()[0];
        if(v!=input.expectedVersion())throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Owner capability profile was modified by another Owner");
        long next=v+1;
        if(existing.isEmpty()) {
            jdbc.update("""
                INSERT INTO fba_exception_owner_permission
                 (tenant_id,seller_marketplace_id,owner_user_id,
                  can_view,can_assign,can_review,can_resolve,can_configure,
                  can_manage,version,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,marketplace,subject,grant.view(),grant.assign(),
                grant.review(),grant.resolve(),grant.configure(),grant.manage(),next,actor);
        } else {
            jdbc.update("""
                UPDATE fba_exception_owner_permission
                SET can_view=?,can_assign=?,can_review=?,can_resolve=?,
                    can_configure=?,can_manage=?,version=?,updated_by=?,updated_at=now()
                WHERE tenant_id=? AND seller_marketplace_id=? AND owner_user_id=?
                """,grant.view(),grant.assign(),grant.review(),grant.resolve(),
                grant.configure(),grant.manage(),next,actor,tenant,marketplace,subject);
        }
        jdbc.update("""
            INSERT INTO fba_exception_owner_permission_audit
             (id,tenant_id,seller_marketplace_id,owner_user_id,actor_user_id,
              old_version,new_version,old_grants,new_grants,reason)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,subject,actor,
            v,next,existing.isEmpty()?null:((Grants)existing.getFirst()[1]).encoded(),
            grant.encoded(),input.reason().strip());
        String name=jdbc.queryForObject("SELECT identity_ref FROM app_user WHERE id=?",
            String.class,subject);
        return new Profile(subject.toString(),name,grant,next,true);
    }
    @Transactional(readOnly=true)
    public List<Audit> audit(UUID tenant,UUID marketplace,UUID actor) {
        require(tenant,marketplace,actor,Capability.MANAGE);
        return jdbc.query("""
            SELECT owner_user_id,actor_user_id,old_version,new_version,
                   old_grants,new_grants,reason,occurred_at
            FROM fba_exception_owner_permission_audit
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getObject(1,UUID.class).toString(),
                r.getObject(2,UUID.class).toString(),r.getLong(3),r.getLong(4),
                r.getString(5),r.getString(6),r.getString(7),
                r.getObject(8,java.time.OffsetDateTime.class).toInstant().toString()),
            tenant,marketplace);
    }
}
