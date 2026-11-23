package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V207: tenant Owner governance for V109 private originals. This is INTERNAL
 * retention policy + metadata access audit, not external document trust.
 * Legal hold remains TRUE; NO original download, delete or purge capability.
 */
@Service
public class DispatchCustodyOriginalGovernanceServiceV207 {
    public record RetentionInput(String retentionUntil,String reason) {}
    public record DecisionInput(String decision,String reason) {}
    public record Metadata(String fileId,String dossierId,String kind,
        String sha256,int sizeBytes,String originalState,String governanceState,
        String proposedRetentionUntil,String proposalId,String reviewId,
        String reviewDecision,boolean legalHold,boolean deletionPermitted,
        boolean operatorIdentityVerified,boolean physicalHandoffVerified,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}
    public record Capacity(long originalFileCount,long storedBytes,
        long maxStoredBytes,long heldOriginals,long retentionReviewedFiles,
        long staleOrMissingOriginals,boolean anyDeletionAllowed,
        boolean externalIssuerVerified,boolean actualBatchCogsVerified) {}
    private final JdbcTemplate jdbc;

    public DispatchCustodyOriginalGovernanceServiceV207(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    private static final String META = """
        SELECT g.file_id,g.dossier_id,g.document_kind,g.sha256,g.size_bytes,
          g.original_state,g.governance_state,g.retention_until,
          g.proposal_id,g.review_id,g.decision
        FROM finance_custody_original_governance_v110 g
        JOIN finance_dispatch_custody_dossier_v108 d
          ON d.tenant_id=g.tenant_id AND d.id=g.dossier_id
        WHERE g.tenant_id=? AND d.marketplace_id=? AND d.sales_order_id=?
          AND g.dossier_id=? AND g.document_kind=?
        """;

    static String kind(String value) {
        return DispatchCustodyOriginalServiceV206.kind(value);
    }
    static String reason(String input) {
        if(input==null||input.strip().length()<10||input.strip().length()>500
            ||input.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Governance reason requires 10-500 printable characters");
        return input.strip();
    }
    static OffsetDateTime retentionDate(String value) {
        try {
            if(value==null)throw new IllegalArgumentException();
            var at=OffsetDateTime.parse(value);
            var now=OffsetDateTime.now();
            if(!at.isAfter(now.plusDays(1))||at.isAfter(now.plusYears(100)))
                throw new IllegalArgumentException();
            return at;
        } catch(RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Retention deadline must be valid, more than one day and within 100 years");
        }
    }
    static String decision(String value) {
        if(!"REVIEWED_NO_PURGE".equals(value)&&!"REJECTED".equals(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Retention review cannot release legal hold or permit deletion");
        return value;
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Tenant-scoped private original governance unavailable");
    }
    private Metadata row(UUID tenant,UUID mp,UUID order,UUID dossier,String kind) {
        var records=jdbc.query(META,(rs,i)->new Metadata(
            rs.getObject(1,UUID.class).toString(),
            rs.getObject(2,UUID.class).toString(),
            rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),
            rs.getString(7),rs.getObject(8,OffsetDateTime.class)==null
                ?null:rs.getObject(8,OffsetDateTime.class).toInstant().toString(),
            rs.getObject(9,UUID.class)==null
                ?null:rs.getObject(9,UUID.class).toString(),
            rs.getObject(10,UUID.class)==null
                ?null:rs.getObject(10,UUID.class).toString(),
            rs.getString(11),true,false,false,false,false,false,null),
            tenant,mp,order,dossier,kind);
        return records.stream().findFirst().orElseThrow(
            DispatchCustodyOriginalGovernanceServiceV207::unavailable);
    }

    /** Every successful disclosure of SHA/size/kind is durably audited. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Metadata viewAndAudit(UUID tenant,UUID mp,UUID order,UUID dossier,
        String documentKind,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String k=kind(documentKind);
        var item=row(tenant,mp,order,dossier,k);
        jdbc.update("""
            INSERT INTO finance_custody_original_metadata_access_v110
              (id,tenant_id,file_id,accessed_by,access_purpose,
               snapshot_sha256,snapshot_size_bytes)
            VALUES (?,?,?,?,'PRIVATE_METADATA_REVIEW',?,?)
            """,UUID.randomUUID(),tenant,UUID.fromString(item.fileId()),
            actor,item.sha256(),item.sizeBytes());
        return item;
    }

    /** Separate Owner may propose a future retention date; never disposal. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Metadata propose(UUID tenant,UUID mp,UUID order,UUID dossier,
        String documentKind,UUID actor,RetentionInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var date=retentionDate(input.retentionUntil());
        var note=reason(input.reason());
        String k=kind(documentKind);
        var item=row(tenant,mp,order,dossier,k);
        try {
            jdbc.update("""
                INSERT INTO finance_custody_original_retention_proposal_v110
                   (id,tenant_id,file_id,proposed_by,retention_until,reason)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,UUID.fromString(item.fileId()),
                actor,date,note);
        } catch(DataIntegrityViolationException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate, stale or non-independent retention proposal");
        }
        return row(tenant,mp,order,dossier,k);
    }

    /** Reviewer must differ from both the uploader and retention proposer. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Metadata review(UUID tenant,UUID mp,UUID order,UUID dossier,
        String documentKind,UUID proposal,UUID actor,DecisionInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||proposal==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var choice=decision(input.decision());
        var note=reason(input.reason());
        String k=kind(documentKind);
        var item=row(tenant,mp,order,dossier,k);
        var matches=jdbc.queryForList("""
            SELECT p.id FROM finance_custody_original_retention_proposal_v110 p
            WHERE p.tenant_id=? AND p.file_id=? AND p.id=?
            """,UUID.class,tenant,UUID.fromString(item.fileId()),proposal);
        if(matches.size()!=1)throw unavailable();
        try {
            jdbc.update("""
                INSERT INTO finance_custody_original_retention_review_v110
                  (id,tenant_id,proposal_id,reviewed_by,decision,reason)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,proposal,actor,choice,note);
        } catch(DataIntegrityViolationException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate, stale or non-independent retention review");
        }
        return row(tenant,mp,order,dossier,k);
    }

    /** No original bytes leave the server and no deletion is authorized. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Capacity capacity(UUID tenant) {
        return jdbc.queryForObject("""
            SELECT count(*) AS files,coalesce(sum(size_bytes),0) AS bytes,
              count(*) FILTER (WHERE governance_state='RETENTION_REVIEWED_HOLD_REMAINS')
                AS reviewed,
              count(*) FILTER (WHERE original_state
                IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED')
                AS stale
            FROM finance_custody_original_governance_v110
            WHERE tenant_id=?
            """,(rs,i)->new Capacity(rs.getLong("files"),rs.getLong("bytes"),
                268435456L,rs.getLong("files"),rs.getLong("reviewed"),
                rs.getLong("stale"),false,false,false),tenant);
    }
}
