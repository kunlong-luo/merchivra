package org.merchivra.erp.purchasing;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V140: explicit tenant-configured warning, computed only from original
 * V52 payload lengths. Never a storage quota, disk-space monitor, cleanup,
 * authorization to purge files or scheduled push notification.
 */
@Service
public class LotProofCapacityMonitorService {
    private static final long MIN_WARNING=1024L*1024;
    private static final long MAX_WARNING=1024L*1024*1024*1024;
    private final JdbcTemplate jdbc;
    private final LotProofGovernanceService governance;
    public LotProofCapacityMonitorService(JdbcTemplate jdbc,
        LotProofGovernanceService governance){
        this.jdbc=jdbc;this.governance=governance;
    }
    public record Change(String action,Long warningBytes,String reason) {}
    public record Alert(String status,Long warningBytes,long originalBytes,
        long fileCount,boolean advisoryOnly) {}

    private static ResponseStatusException bad(String reason){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private static ResponseStatusException conflict(String reason){
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    private static String reason(String value) {
        if(value==null||value.strip().length()<10||value.strip().length()>500
            ||value.chars().anyMatch(Character::isISOControl))
            throw bad("Capacity threshold reason requires 10-500 printable characters");
        return value.strip();
    }
    private Long configured(UUID tenant){
        List<Long> found=jdbc.query("""
            SELECT warning_bytes FROM erp_lot_proof_file_capacity_threshold
            WHERE tenant_id=?
            """,(rs,i)->(Long)rs.getObject("warning_bytes",Long.class),tenant);
        return found.isEmpty()?null:found.getFirst();
    }
    public static String status(long bytes,Long threshold){
        if(threshold==null)return "UNCONFIGURED";
        return bytes>=threshold?"WARNING":"NORMAL";
    }
    private Alert current(UUID tenant){
        var usage=governance.capacity(tenant);
        Long warning=configured(tenant);
        return new Alert(status(usage.originalBytes(),warning),warning,
            usage.originalBytes(),usage.fileCount(),true);
    }
    @Transactional(readOnly=true)
    public Alert read(UUID tenant){return current(tenant);}

    /** Tenant row lock avoids lost updates; append-only audit shares transaction. */
    @Transactional
    public Alert configure(UUID tenant,UUID actor,Change input){
        if(actor==null||input==null)throw bad("Owner and change required");
        String rationale=reason(input.reason());
        Long bytes=input.warningBytes();
        if("SET".equals(input.action())){
            if(bytes==null||bytes<MIN_WARNING||bytes>MAX_WARNING)
                throw bad("Capacity warning must be between 1 MiB and 1 TiB");
        } else if("DISABLE".equals(input.action())){
            if(bytes!=null)throw bad("Disable must omit warningBytes");
        } else throw bad("Unknown capacity threshold action");
        List<UUID> allowed=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(allowed.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Tenant capacity monitor unavailable");
        Long previous=configured(tenant);
        if(java.util.Objects.equals(previous,bytes))
            throw conflict("Tenant warning threshold already has this value");
        int updated=jdbc.update("""
            INSERT INTO erp_lot_proof_file_capacity_threshold
                (tenant_id,warning_bytes,changed_by)
            VALUES (?,?,?)
            ON CONFLICT (tenant_id) DO UPDATE
              SET warning_bytes=EXCLUDED.warning_bytes,
                  changed_by=EXCLUDED.changed_by,changed_at=now()
            """,tenant,bytes,actor);
        if(updated!=1)throw conflict("Capacity configuration was not durable");
        int audited=jdbc.update("""
            INSERT INTO erp_lot_proof_file_capacity_threshold_audit
              (id,tenant_id,warning_bytes,actor_user_id,reason)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,bytes,actor,rationale);
        if(audited!=1)throw conflict("Capacity warning audit was not durable");
        return current(tenant);
    }
}
