package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V138: policy-review metadata only. This class intentionally has no DELETE,
 * purge, background scheduler or job that can destroy private original bytes.
 * Tenant row locking serializes all transitions for a tenant under PG18.
 */
@Service
public class LotProofGovernanceService {
    private final JdbcTemplate jdbc;
    public LotProofGovernanceService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Command(String action,String reason,String retentionUntil) {}
    public record Governance(String proofId,String fileId,boolean legalHold,
        String holdSetBy,String holdReleaseRequestedBy,String retentionUntil,
        String retentionProposedUntil,String retentionProposedBy,
        String disposalState,String disposalRequestedBy,String disposalReviewedBy) {}
    public record Capacity(long fileCount,long originalBytes,long heldFiles,
        long retentionUnsetFiles,long pendingRetentionFiles,long reviewedDisposalFiles) {}
    /** No file bytes; immutable, tenant-and-proof-scoped review history. */
    public record HistoryEvent(String id,String action,String actorUserId,
        String reason,String retentionCandidate,String recordedAt) {}

    private record Original(UUID fileId,UUID uploadedBy) {}
    private record State(boolean held,UUID holdBy,UUID releaseBy,
        OffsetDateTime until,OffsetDateTime proposedUntil,UUID proposedBy,
        String disposal,UUID disposalBy,UUID reviewedBy) {}

    private static ResponseStatusException bad(String s){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);
    }
    private static ResponseStatusException conflict(String s){
        return new ResponseStatusException(HttpStatus.CONFLICT,s);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Private original governance unavailable");
    }
    private static String id(UUID u){return u==null?null:u.toString();}
    private static String date(OffsetDateTime d){return d==null?null:d.toInstant().toString();}
    private static String reason(String input){
        if(input==null||input.strip().length()<10||input.strip().length()>500
           ||input.chars().anyMatch(Character::isISOControl))
            throw bad("Governance reason requires 10-500 printable characters");
        return input.strip();
    }
    private static OffsetDateTime retentionDate(String input){
        if(input==null)throw bad("Retention expiry date required");
        try {
            OffsetDateTime d=OffsetDateTime.parse(input);
            if(!d.isAfter(OffsetDateTime.now().plusDays(1))
                ||d.isAfter(OffsetDateTime.now().plusYears(100)))
                throw bad("Retention date must be future and within 100 years");
            return d;
        } catch(DateTimeParseException e){
            throw bad("Invalid ISO-8601 retention date");
        }
    }
    private void tenantLock(UUID tenant){
        List<UUID> found=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(found.size()!=1)throw missing();
    }
    private Original original(UUID tenant,UUID proofId){
        return jdbc.query("""
            SELECT id,uploaded_by FROM erp_local_lot_disposition_proof_file
            WHERE tenant_id=? AND proof_id=?
            """,(rs,i)->new Original(rs.getObject("id",UUID.class),
                rs.getObject("uploaded_by",UUID.class)),tenant,proofId)
            .stream().findFirst().orElseThrow(LotProofGovernanceService::missing);
    }
    private static State map(ResultSet rs,int i)throws SQLException{
        return new State(rs.getBoolean("legal_hold"),
            rs.getObject("hold_set_by",UUID.class),
            rs.getObject("hold_release_requested_by",UUID.class),
            rs.getObject("retention_until",OffsetDateTime.class),
            rs.getObject("retention_proposed_until",OffsetDateTime.class),
            rs.getObject("retention_proposed_by",UUID.class),
            rs.getString("disposal_state"),
            rs.getObject("disposal_requested_by",UUID.class),
            rs.getObject("disposal_reviewed_by",UUID.class));
    }
    private State state(UUID tenant,UUID fileId){
        return jdbc.query("""
            SELECT legal_hold,hold_set_by,hold_release_requested_by,
              retention_until,retention_proposed_until,retention_proposed_by,
              disposal_state,disposal_requested_by,disposal_reviewed_by
            FROM erp_lot_proof_file_governance
            WHERE tenant_id=? AND file_id=?
            """,LotProofGovernanceService::map,tenant,fileId)
            .stream().findFirst().orElse(new State(false,null,null,
                null,null,null,"NONE",null,null));
    }
    private static Governance display(UUID proofId,UUID fileId,State s){
        return new Governance(id(proofId),id(fileId),s.held(),id(s.holdBy()),
            id(s.releaseBy()),date(s.until()),date(s.proposedUntil()),
            id(s.proposedBy()),s.disposal(),id(s.disposalBy()),id(s.reviewedBy()));
    }

    @Transactional(readOnly=true)
    public Governance status(UUID tenant,UUID proofId){
        if(proofId==null)throw bad("Proof required");
        Original file=original(tenant,proofId);
        return display(proofId,file.fileId(),state(tenant,file.fileId()));
    }

    /** Private per-tenant original sizes; no BYTEA is selected or returned. */
    @Transactional(readOnly=true)
    public Capacity capacity(UUID tenant){
        return jdbc.query("""
            SELECT COUNT(*) AS files,COALESCE(SUM(f.size_bytes),0)::bigint AS bytes,
              COUNT(*) FILTER (WHERE g.legal_hold) AS held,
              COUNT(*) FILTER (WHERE g.retention_until IS NULL) AS unset,
              COUNT(*) FILTER (WHERE g.retention_proposed_until IS NOT NULL) AS pending,
              COUNT(*) FILTER (WHERE g.disposal_state='REVIEWED_NO_DELETE') AS reviewed
            FROM erp_local_lot_disposition_proof_file f
            LEFT JOIN erp_lot_proof_file_governance g
              ON g.tenant_id=f.tenant_id AND g.file_id=f.id
            WHERE f.tenant_id=?
            """,(rs,i)->new Capacity(rs.getLong("files"),rs.getLong("bytes"),
                rs.getLong("held"),rs.getLong("unset"),
                rs.getLong("pending"),rs.getLong("reviewed")),tenant)
            .getFirst();
    }

    /**
     * V140: stable (recorded_at DESC,id DESC) cursor over V88's existing index.
     * The cursor is bound to tenant, proof and purpose; invalid or cross-tenant
     * cursors are HTTP 400 before constructing any SQL query.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<HistoryEvent> historyPage(
        UUID tenant,UUID proofId,int pageSize,String cursor) {
        int limit=LocalKeysetPage.limit(pageSize);
        if(proofId==null)throw bad("Proof required");
        String[] parts=LocalKeysetPage.decode(cursor,5);
        OffsetDateTime before=null;
        UUID lastId=null;
        if(parts!=null){
            if(!"V140_PROOF_GOV_AUDIT".equals(parts[0])
                ||!tenant.toString().equals(parts[1])
                ||!proofId.toString().equals(parts[2]))
                throw bad("Governance history cursor scope mismatch");
            try {
                before=OffsetDateTime.parse(parts[3]);
                lastId=UUID.fromString(parts[4]);
            } catch(DateTimeParseException | IllegalArgumentException e) {
                throw bad("Invalid governance history cursor timestamp or id");
            }
        }
        // Check proof existence with current Owner tenant even for empty pages.
        original(tenant,proofId);
        String query="""
            SELECT id,action,actor_user_id,reason,retention_candidate,recorded_at
            FROM erp_lot_proof_file_governance_audit
            WHERE tenant_id=? AND proof_id=?
            """+(parts==null?"":"""
              AND (recorded_at,id)<(?,?)
            """)+"""
            ORDER BY recorded_at DESC,id DESC LIMIT ?
            """;
        Object[] params=parts==null
            ?new Object[]{tenant,proofId,limit+1}
            :new Object[]{tenant,proofId,before,lastId,limit+1};
        List<HistoryEvent> rows=jdbc.query(query,(rs,i)->new HistoryEvent(
            id(rs.getObject("id",UUID.class)),rs.getString("action"),
            id(rs.getObject("actor_user_id",UUID.class)),rs.getString("reason"),
            date(rs.getObject("retention_candidate",OffsetDateTime.class)),
            date(rs.getObject("recorded_at",OffsetDateTime.class))),params);
        return LocalKeysetPage.pack(rows,limit,item->LocalKeysetPage.encode(
            "V140_PROOF_GOV_AUDIT",tenant.toString(),proofId.toString(),
            item.recordedAt(),item.id()));
    }

    private void persist(UUID tenant,UUID proofId,UUID fileId,State next){
        int updated=jdbc.update("""
            UPDATE erp_lot_proof_file_governance
            SET legal_hold=?,hold_set_by=?,hold_release_requested_by=?,
              retention_until=?,retention_proposed_until=?,retention_proposed_by=?,
              disposal_state=?,disposal_requested_by=?,disposal_reviewed_by=?,
              updated_at=now()
            WHERE tenant_id=? AND proof_id=? AND file_id=?
            """,next.held(),next.holdBy(),next.releaseBy(),next.until(),
            next.proposedUntil(),next.proposedBy(),next.disposal(),
            next.disposalBy(),next.reviewedBy(),tenant,proofId,fileId);
        if(updated!=1)throw conflict("Governance update was not durable");
    }
    private void audit(UUID tenant,UUID proofId,UUID fileId,UUID actor,
        String action,String rationale,OffsetDateTime candidate){
        int stored=jdbc.update("""
            INSERT INTO erp_lot_proof_file_governance_audit
              (id,tenant_id,proof_id,file_id,action,actor_user_id,reason,
               retention_candidate)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,proofId,fileId,action,actor,
            rationale,candidate);
        if(stored!=1)throw conflict("Governance audit write failed");
    }

    private boolean expired(UUID tenant,UUID fileId){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT retention_until IS NOT NULL
              AND retention_until<=now()
            FROM erp_lot_proof_file_governance
            WHERE tenant_id=? AND file_id=?
            """,Boolean.class,tenant,fileId));
    }

    /** All actions are independently audited, and no action deletes an original. */
    @Transactional
    public Governance command(UUID tenant,UUID actor,UUID proofId,Command command){
        if(actor==null || proofId==null || command==null)throw bad("Owner and command required");
        String action=command.action(),rationale=reason(command.reason());
        if(action==null||!List.of("PLACE_HOLD","REQUEST_HOLD_RELEASE",
            "APPROVE_HOLD_RELEASE","PROPOSE_RETENTION","APPROVE_RETENTION",
            "REQUEST_DISPOSAL","REVIEW_DISPOSAL").contains(action))
            throw bad("Unsupported evidence governance action");
        OffsetDateTime candidate="PROPOSE_RETENTION".equals(action)
            ?retentionDate(command.retentionUntil()):null;
        if(!"PROPOSE_RETENTION".equals(action)&&command.retentionUntil()!=null)
            throw bad("Only retention proposals accept an expiry");
        tenantLock(tenant);
        Original file=original(tenant,proofId);
        jdbc.update("""
            INSERT INTO erp_lot_proof_file_governance(tenant_id,proof_id,file_id)
            VALUES (?,?,?) ON CONFLICT (tenant_id,file_id) DO NOTHING
            """,tenant,proofId,file.fileId());
        State s=state(tenant,file.fileId());
        State next;
        switch(action){
            case "PLACE_HOLD" -> {
                if(s.held())throw conflict("Legal hold already active");
                next=new State(true,actor,null,s.until(),s.proposedUntil(),
                    s.proposedBy(),"NONE",null,null);
            }
            case "REQUEST_HOLD_RELEASE" -> {
                if(!s.held()||s.releaseBy()!=null)
                    throw conflict("Legal hold release unavailable");
                next=new State(true,s.holdBy(),actor,s.until(),s.proposedUntil(),
                    s.proposedBy(),s.disposal(),s.disposalBy(),s.reviewedBy());
            }
            case "APPROVE_HOLD_RELEASE" -> {
                if(!s.held()||s.releaseBy()==null||actor.equals(s.releaseBy()))
                    throw conflict("Independent Owner hold-release approval required");
                next=new State(false,s.holdBy(),null,s.until(),s.proposedUntil(),
                    s.proposedBy(),"NONE",null,null);
            }
            case "PROPOSE_RETENTION" -> {
                if(s.proposedBy()!=null ||(s.until()!=null
                    && candidate.isBefore(s.until())))
                    throw conflict("Pending proposal or prohibited retention shortening");
                next=new State(s.held(),s.holdBy(),s.releaseBy(),s.until(),
                    candidate,actor,"NONE",null,null);
            }
            case "APPROVE_RETENTION" -> {
                if(s.proposedBy()==null ||actor.equals(s.proposedBy())
                    ||actor.equals(file.uploadedBy()))
                    throw conflict("Independent Owner retention approval required");
                next=new State(s.held(),s.holdBy(),s.releaseBy(),
                    s.proposedUntil(),null,null,"NONE",null,null);
            }
            case "REQUEST_DISPOSAL" -> {
                if(s.held()||s.proposedBy()!=null||!"NONE".equals(s.disposal())
                    ||!expired(tenant,file.fileId()))
                    throw conflict("Unexpired/unapproved/held original cannot enter disposal review");
                next=new State(false,s.holdBy(),null,s.until(),null,null,
                    "REQUESTED",actor,null);
            }
            case "REVIEW_DISPOSAL" -> {
                if(s.held()||s.proposedBy()!=null||!"REQUESTED".equals(s.disposal())
                    ||actor.equals(s.disposalBy())||actor.equals(file.uploadedBy())
                    ||!expired(tenant,file.fileId()))
                    throw conflict("Independent Owner review or retention clearance required");
                // This is a NO-DELETE administrative review, NOT permission
                // for any automated job or SQL DELETE.
                next=new State(false,s.holdBy(),null,s.until(),null,null,
                    "REVIEWED_NO_DELETE",s.disposalBy(),actor);
            }
            default -> throw bad("Unknown governance action");
        }
        persist(tenant,proofId,file.fileId(),next);
        audit(tenant,proofId,file.fileId(),actor,action,rationale,candidate);
        return display(proofId,file.fileId(),next);
    }
}
