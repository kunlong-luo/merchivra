package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.merchivra.erp.purchasing.LotProofAvScanner;
import org.merchivra.erp.purchasing.LotProofBinaryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V206 private original bytes for V205 custody dossier references.
 *
 * Reuses V52 file sniffing + SHA256 and V137 fail-closed ClamAV. PostgreSQL 18
 * independently recomputes SHA-256 on BYTEA. A file matching the tenant's
 * asserted SHA does NOT prove the signer/issuer, operator identity, physical
 * carrier receipt, Amazon FBA/FBM fulfillment or actual COGS.
 *
 * No download/stream/public file URL. Private original files are append-only.
 */
@Service
public class DispatchCustodyOriginalServiceV206 {
    public record OriginalInfo(String id,String dossierId,String kind,
        String mediaType,int sizeBytes,String sha256,String uploadedBy,
        boolean externallyVerified) {}
    public record OriginalReadiness(String dossierId,String signedDispatchId,
        String dossierState,String originalState,int originalsPresent,
        boolean operatorOriginalPresent,boolean carrierOriginalPresent,
        boolean operatorIdentityVerified,boolean physicalHandoffVerified,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}
    public record Preview(String orderId,String marketplaceId,String scope,
        int dossierCount,int originalFilesMissing,List<OriginalReadiness> dossiers,
        List<String> blockerCodes,boolean operatorIdentityVerified,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}

    private record Row(UUID dossier,UUID dispatch,String dossierState,
        String originalState,UUID operatorFile,UUID carrierFile) {}

    private static final String SQL = """
        SELECT dossier_id,signed_dispatch_id,dossier_state,original_state,
          operator_file_id,carrier_file_id
        FROM finance_dispatch_custody_original_integrity_v109
        WHERE tenant_id=? AND marketplace_id=? AND sales_order_id=?
        ORDER BY dossier_id LIMIT 1001
        """;
    private final JdbcTemplate jdbc;
    private final DispatchCustodyDossierServiceV205 custody;
    private final LotProofAvScanner scanner;

    public DispatchCustodyOriginalServiceV206(JdbcTemplate jdbc,
        DispatchCustodyDossierServiceV205 custody,LotProofAvScanner scanner) {
        this.jdbc=jdbc;this.custody=custody;this.scanner=scanner;
    }
    static String kind(String raw) {
        if(!"OPERATOR_REGISTRY".equals(raw)&&!"CARRIER_HANDOFF".equals(raw))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Original kind must be OPERATOR_REGISTRY or CARRIER_HANDOFF");
        return raw;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public OriginalInfo upload(UUID tenant,UUID mp,UUID order,UUID dossier,
        UUID actor,String kind,byte[] bytes) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(dossier==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Custody dossier identifier required");
        kind(kind);
        // Reuse the exact 2 MiB / PDF,PNG,JPEG content-sniffing and digest
        // routines already used by private lot-proof original storage.
        String media=LotProofBinaryService.mediaType(bytes);
        String digest=LotProofBinaryService.digest(bytes);
        // Verify malware cleanliness BEFORE acquiring any tenant row locks.
        // On normal instances missing or inconclusive clamd fails closed.
        // Explicit synthetic Demo has the existing documented scanner bypass.
        scanner.scan(bytes);

        var current=custody.preview(tenant,mp,order);
        var dossierRow=current.dossiers().stream()
            .filter(d->d.dossierId().equals(dossier.toString()))
            .findFirst().orElseThrow(()->new ResponseStatusException(
                HttpStatus.NOT_FOUND,"Custody dossier unavailable in this tenant/order"));
        if(!List.of("EXTERNAL_EVIDENCE_UNREVIEWED",
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED")
            .contains(dossierRow.integrityState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current V205 dossier is stale, disputed or rejected");
        String asserted="OPERATOR_REGISTRY".equals(kind)
            ?dossierRow.operatorDocumentSha256()
            :dossierRow.carrierDocumentSha256();
        if(!digest.equals(asserted))throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Uploaded original bytes do not match asserted document SHA-256");

        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_dispatch_custody_original_v109
                  (id,tenant_id,dossier_id,document_kind,media_type,
                   file_bytes,uploaded_by)
                VALUES (?,?,?,?,?,?,?)
                """,id,tenant,dossier,kind,media,bytes,actor);
        } catch(DataIntegrityViolationException collision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate, disputed or mismatched private custody original");
        }
        return info(tenant,dossier,kind);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OriginalInfo info(UUID tenant,UUID dossier,String kind) {
        kind(kind);
        var results=jdbc.query("""
            SELECT id,dossier_id,document_kind,media_type,size_bytes,
              sha256,uploaded_by
            FROM finance_dispatch_custody_original_v109
            WHERE tenant_id=? AND dossier_id=? AND document_kind=?
            """,(r,i)->new OriginalInfo(r.getObject(1,UUID.class).toString(),
                r.getObject(2,UUID.class).toString(),
                r.getString(3),r.getString(4),r.getInt(5),
                r.getString(6),r.getObject(7,UUID.class).toString(),false),
            tenant,dossier,kind);
        return results.stream().findFirst().orElseThrow(
            ()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Private custody original metadata unavailable"));
    }

    static String effectiveState(String dbState,String custodyState) {
        if(custodyState==null || !List.of("EXTERNAL_EVIDENCE_UNREVIEWED",
            "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED").contains(custodyState))
            return "DOSSIER_OR_SIGNED_SOURCE_STALE";
        return dbState;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID mp,UUID order) {
        // V205 recomputes V204 Ed25519 + local sale/lot mapping and B2 blockers.
        // An old positive V205 internal document review cannot override them.
        var baseline=custody.preview(tenant,mp,order);
        var byId=new HashMap<String,DispatchCustodyDossierServiceV205.Dossier>();
        for(var d:baseline.dossiers()) {
            if(byId.putIfAbsent(d.dossierId(),d)!=null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate custody dossier identity");
        }
        var raw=jdbc.query(SQL,(r,i)->new Row(r.getObject(1,UUID.class),
            r.getObject(2,UUID.class),r.getString(3),r.getString(4),
            r.getObject(5,UUID.class),r.getObject(6,UUID.class)),
            tenant,mp,order);
        if(raw.size()>1000)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Unbounded original content proof count");

        var blockers=new ArrayList<String>();
        blockers.add("EXTERNAL_ISSUER_NOT_AUTHENTICATED_BY_TRUSTED_CHANNEL");
        blockers.add("CARRIER_OR_WMS_PHYSICAL_HANDOFF_NOT_INDEPENDENTLY_VERIFIED");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_UNVERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        int missing=0;
        boolean drift=false;
        var rows=new ArrayList<OriginalReadiness>();
        for(var r:raw) {
            var d=byId.get(r.dossier().toString());
            String state=effectiveState(r.originalState(),
                d==null?null:d.integrityState());
            if(!"ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED".equals(state))
                drift=true;
            if(r.operatorFile()==null)missing++;
            if(r.carrierFile()==null)missing++;
            rows.add(new OriginalReadiness(r.dossier().toString(),
                r.dispatch().toString(),r.dossierState(),state,
                (r.operatorFile()==null?0:1)+(r.carrierFile()==null?0:1),
                r.operatorFile()!=null,r.carrierFile()!=null,
                false,false,false,false,null));
        }
        // If V205 has a dossier that is not represented in the V109 view,
        // hide any apparent completeness (never silently drop the record).
        if(rows.size()!=byId.size()) {
            blockers.add("CUSTODY_DOSSIER_ORIGINAL_READ_INCOMPLETE");
            drift=true;
            missing+=Math.max(0,byId.size()-rows.size())*2;
        }
        if(missing>0)blockers.add("OPERATOR_OR_CARRIER_ORIGINAL_CONTENT_MISSING");
        if(drift)blockers.add("ORIGINAL_BYTES_OR_UPSTREAM_EVIDENCE_NOT_READY");
        return new Preview(order.toString(),mp.toString(),
            "PRIVATE_ORIGINAL_CONTENT_MATCHES_TENANT_CLAIM_ONLY",
            byId.size(),missing,List.copyOf(rows),List.copyOf(blockers),
            false,false,false,null);
    }
}
