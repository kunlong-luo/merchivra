package org.merchivra.erp.ads;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V66: local Ads identity / DAILY campaign spend evidence only. Never makes Ads
 * or Selling Partner calls, stores tokens, books finance events or promotes
 * manual/mock cost to verified LIVE. Review is a second Owner decision.
 */
@Service
public class AdsReportService {
    private final JdbcTemplate jdbc;
    public AdsReportService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Capability(String status,boolean sellerOauthReused,
        boolean adsTokenStored,boolean adsApiVerified,boolean profitPosted){}
    public record Profile(String id,String profileReference,String origin,
        String authorizationStatus,String createdAt){}
    public record ProfileInput(String profileReference,String origin){}
    public record CostInput(LocalDate businessDate,String campaignReference,String spendAmount){}
    public record ImportInput(UUID profileId,String reportReference,String origin,
        LocalDate fromDate,LocalDate throughDate,String currency,List<CostInput> rows){}
    public record Batch(String id,String profileId,String reportReference,String origin,
        String reportType,String currency,String fromDate,String throughDate,
        int rowCount,String state,String importedBy,String reviewedBy,
        String reviewNote,String reportSha256,String importedAt,String reviewedAt,
        boolean adsApiVerified,boolean profitPosted){}
    public record Cost(int rowNumber,String businessDate,String campaignReference,
        String spendAmount,String currency){}
    public record Detail(Batch batch,List<Cost> rows,String reportTotal){}
    public record Review(String decision,String note){}
    public record Daily(String profileId,String profileReference,String businessDate,
        String campaignReference,String spendAmount,String currency,String evidenceOrigin,
        String reportReference,boolean adsApiVerified,boolean profitPosted){}
    private record CanonicalRow(LocalDate date,String campaign,BigDecimal amount){}
    private record ProfileRow(UUID id,String origin,String currency){}
    private record Header(UUID id,String state,UUID importer){}

    private static ResponseStatusException bad(String reason){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private static ResponseStatusException conflict(String reason){
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Ads record not in current Marketplace");
    }
    private static String safe(String s,int min,int max,String label) {
        if(s==null||s.length()<min||s.length()>max||
          !s.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{"+(min-1)+","+(max-1)+"}"))
            throw bad("Invalid "+label);
        return s;
    }
    private static String timestamp(ResultSet rs,String field)throws SQLException{
        var time=rs.getObject(field,OffsetDateTime.class);
        return time==null?null:time.toInstant().toString();
    }
    private static String hash(String text){
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public Capability capability(){
        return new Capability("DISABLED_PENDING_LOCAL_OWNER_AUTHORIZATION",false,false,false,false);
    }
    private String marketplaceCurrency(UUID tenant,UUID marketplace){
        var result=jdbc.query("""
            SELECT m.currency_code FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getString(1).trim(),tenant,marketplace);
        if(result.size()!=1)throw missing();
        return result.getFirst();
    }
    private static Profile mapProfile(ResultSet r,int row)throws SQLException{
        return new Profile(r.getObject("id",UUID.class).toString(),
            r.getString("profile_reference"),r.getString("origin"),
            r.getString("authorization_status"),timestamp(r,"created_at"));
    }
    public List<Profile> profiles(UUID tenant,UUID marketplace){
        marketplaceCurrency(tenant,marketplace);
        return jdbc.query("""
            SELECT * FROM ads_profile_reference
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY created_at DESC,id DESC LIMIT 50
            """,AdsReportService::mapProfile,tenant,marketplace);
    }
    @Transactional
    public Profile register(UUID tenant,UUID marketplace,UUID actor,ProfileInput input){
        if(input==null||actor==null)throw bad("Ads profile reference required");
        String profile=safe(input.profileReference(),3,80,"Ads profile");
        String origin=origin(input.origin());
        marketplaceCurrency(tenant,marketplace);
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,marketplace);
        var existing=jdbc.query("""
            SELECT * FROM ads_profile_reference WHERE tenant_id=? AND profile_reference=?
            """,AdsReportService::mapProfile,tenant,profile);
        if(!existing.isEmpty()){
            Profile old=existing.getFirst();
            // Prevent silent cross-Marketplace re-association of Ads identities.
            var match=jdbc.queryForList("""
                SELECT id FROM ads_profile_reference
                WHERE tenant_id=? AND seller_marketplace_id=? AND profile_reference=?
                """,UUID.class,tenant,marketplace,profile);
            if(match.size()!=1||!old.origin().equals(origin))
                throw conflict("Ads profile reference already associated with another scope/origin");
            return old;
        }
        UUID id=UUID.randomUUID();
        try{
            jdbc.update("""
                INSERT INTO ads_profile_reference
                  (id,tenant_id,seller_marketplace_id,profile_reference,origin,created_by)
                VALUES(?,?,?,?,?,?)
                """,id,tenant,marketplace,profile,origin,actor);
        }catch(DuplicateKeyException e){throw conflict("Ads profile reference already exists");}
        return jdbc.query("""
            SELECT * FROM ads_profile_reference WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,AdsReportService::mapProfile,tenant,marketplace,id).getFirst();
    }
    static String origin(String candidate){
        if(!"MOCK".equals(candidate)&&!"MANUAL_UNVERIFIED".equals(candidate))
            throw bad("Only MOCK / MANUAL_UNVERIFIED Ads evidence is accepted");
        return candidate;
    }
    private ProfileRow profileScope(UUID tenant,UUID marketplace,UUID id){
        String currency=marketplaceCurrency(tenant,marketplace);
        var found=jdbc.query("""
            SELECT id,origin FROM ads_profile_reference
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,(r,i)->new ProfileRow(r.getObject("id",UUID.class),r.getString("origin"),currency),
            tenant,marketplace,id);
        if(found.size()!=1)throw missing();
        return found.getFirst();
    }
    private static BigDecimal amount(String raw){
        if(raw==null||!raw.matches("[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw bad("Invalid nonnegative original currency Ads amount");
        return new BigDecimal(raw).setScale(6);
    }
    static List<CanonicalRow> validated(ImportInput input,String expectedCurrency,String expectedOrigin){
        if(input==null||input.profileId()==null||input.fromDate()==null||
          input.throughDate()==null||input.rows()==null||input.rows().isEmpty()||
          input.rows().size()>200)throw bad("Invalid Ads report scope or row count");
        safe(input.reportReference(),8,160,"report reference");
        if(input.fromDate().isAfter(input.throughDate())||
          ChronoUnit.DAYS.between(input.fromDate(),input.throughDate())>30)
            throw bad("Ads report window must be at most 31 inclusive dates");
        if(!expectedCurrency.equals(input.currency())||!expectedOrigin.equals(origin(input.origin())))
            throw conflict("Ads report currency/origin must match its Marketplace/profile");
        Set<String> keys=new HashSet<>();
        var result=new java.util.ArrayList<CanonicalRow>();
        for(CostInput row:input.rows()){
            if(row==null||row.businessDate()==null||
               row.businessDate().isBefore(input.fromDate())||
               row.businessDate().isAfter(input.throughDate()))
                throw bad("Ads cost date outside import window");
            String campaign=safe(row.campaignReference(),1,160,"campaign reference");
            if(!keys.add(row.businessDate()+"|"+campaign))
                throw bad("Repeated campaign/date within one report");
            result.add(new CanonicalRow(row.businessDate(),campaign,amount(row.spendAmount())));
        }
        return result.stream()
            .sorted(Comparator.comparing(CanonicalRow::date)
                .thenComparing(CanonicalRow::campaign)).toList();
    }
    private static String fingerprint(ImportInput input,List<CanonicalRow> rows){
        StringBuilder b=new StringBuilder("ADS_OFFLINE_V66|")
          .append(input.profileId()).append('|').append(input.reportReference())
          .append('|').append(input.origin()).append('|').append(input.fromDate())
          .append('|').append(input.throughDate()).append('|').append(input.currency());
        for(CanonicalRow row:rows)
            b.append('|').append(row.date()).append('|')
              .append(row.campaign().length()).append(':').append(row.campaign())
              .append('|').append(row.amount().toPlainString());
        return hash(b.toString());
    }
    private static Batch mapBatch(ResultSet r,int row)throws SQLException{
        UUID reviewer=r.getObject("reviewed_by",UUID.class);
        return new Batch(r.getObject("id",UUID.class).toString(),
            r.getObject("profile_id",UUID.class).toString(),
            r.getString("report_reference"),r.getString("evidence_origin"),
            r.getString("report_type"),r.getString("currency_code").trim(),
            r.getObject("from_date",LocalDate.class).toString(),
            r.getObject("through_date",LocalDate.class).toString(),
            r.getInt("row_count"),r.getString("state"),
            r.getObject("imported_by",UUID.class).toString(),
            reviewer==null?null:reviewer.toString(),r.getString("review_note"),
            r.getString("report_sha256"),timestamp(r,"imported_at"),
            timestamp(r,"reviewed_at"),false,false);
    }
    public List<Batch> reports(UUID tenant,UUID marketplace){
        marketplaceCurrency(tenant,marketplace);
        return jdbc.query("""
            SELECT * FROM ads_report_import
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY imported_at DESC,id DESC LIMIT 100
            """,AdsReportService::mapBatch,tenant,marketplace);
    }
    public Detail report(UUID tenant,UUID marketplace,UUID id){
        var rows=jdbc.query("""
            SELECT * FROM ads_report_import WHERE tenant_id=?
              AND seller_marketplace_id=? AND id=?
            """,AdsReportService::mapBatch,tenant,marketplace,id);
        if(rows.size()!=1)throw missing();
        Batch batch=rows.getFirst();
        var items=jdbc.query("""
            SELECT row_number,business_date,campaign_reference,spend_amount,currency_code
            FROM ads_report_cost_row WHERE tenant_id=? AND batch_id=?
            ORDER BY row_number LIMIT 200
            """,(r,n)->new Cost(r.getInt(1),r.getObject(2,LocalDate.class).toString(),
                    r.getString(3),r.getBigDecimal(4).toPlainString(),r.getString(5).trim()),
            tenant,id);
        BigDecimal total=items.stream().map(x->new BigDecimal(x.spendAmount()))
            .reduce(BigDecimal.ZERO,BigDecimal::add).setScale(6);
        return new Detail(batch,items,total.toPlainString());
    }
    @Transactional
    public Detail importReport(UUID tenant,UUID marketplace,UUID actor,ImportInput input){
        if(actor==null||input==null||input.profileId()==null)throw bad("Report identity required");
        ProfileRow profile=profileScope(tenant,marketplace,input.profileId());
        List<CanonicalRow> items=validated(input,profile.currency(),profile.origin());
        String fingerprint=fingerprint(input,items);
        // Lock the Marketplace so identical concurrent requests can't produce different imports.
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,marketplace);
        var existing=jdbc.query("""
            SELECT id,report_sha256 FROM ads_report_import
            WHERE tenant_id=? AND seller_marketplace_id=? AND profile_id=?
              AND report_reference=?
            """,(r,n)->new String[]{r.getObject(1,UUID.class).toString(),r.getString(2)},
            tenant,marketplace,input.profileId(),input.reportReference());
        if(!existing.isEmpty()){
            if(!fingerprint.equals(existing.getFirst()[1]))
                throw conflict("Same report reference has different source content");
            return report(tenant,marketplace,UUID.fromString(existing.getFirst()[0]));
        }
        UUID batchId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ads_report_import
              (id,tenant_id,seller_marketplace_id,profile_id,report_reference,report_sha256,
               evidence_origin,currency_code,from_date,through_date,row_count,imported_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,batchId,tenant,marketplace,input.profileId(),input.reportReference(),
            fingerprint,profile.origin(),profile.currency(),input.fromDate(),
            input.throughDate(),items.size(),actor);
        for(int i=0;i<items.size();i++){
            CanonicalRow row=items.get(i);
            jdbc.update("""
                INSERT INTO ads_report_cost_row
                (tenant_id,batch_id,row_number,business_date,campaign_reference,
                 spend_amount,currency_code)
                VALUES(?,?,?,?,?,?,?)
                """,tenant,batchId,i+1,row.date(),row.campaign(),row.amount(),profile.currency());
        }
        return report(tenant,marketplace,batchId);
    }
    @Transactional
    public Detail review(UUID tenant,UUID marketplace,UUID actor,UUID batchId,Review action){
        if(actor==null||action==null||action.decision()==null)throw bad("Review decision required");
        if(!"APPROVE".equals(action.decision())&&!"REJECT".equals(action.decision()))
            throw bad("Unsupported review action");
        if(action.note()==null||action.note().strip().length()<10||
          action.note().length()>500||action.note().chars().anyMatch(Character::isISOControl))
            throw bad("Review evidence note must be 10..500 printable characters");
        var found=jdbc.query("""
            SELECT id,state,imported_by FROM ads_report_import
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=? FOR UPDATE
            """,(r,n)->new Header(r.getObject(1,UUID.class),
                r.getString(2),r.getObject(3,UUID.class)),tenant,marketplace,batchId);
        if(found.size()!=1)throw missing();
        Header batch=found.getFirst();
        if(!"STAGED".equals(batch.state()))throw conflict("Ads report already reviewed; immutable");
        if(actor.equals(batch.importer()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Ads report importer cannot approve/reject their own evidence");
        if("APPROVE".equals(action.decision())){
            // No unchecked SUM across overlapping files: unique index rejects ANY
            // duplicate advertiser/day/campaign, even with the same amount.
            try{
                jdbc.update("""
                    INSERT INTO ads_report_reviewed_cost
                     (tenant_id,seller_marketplace_id,profile_id,business_date,
                      campaign_reference,batch_id,row_number,spend_amount,currency_code)
                    SELECT r.tenant_id,b.seller_marketplace_id,b.profile_id,
                           r.business_date,r.campaign_reference,r.batch_id,r.row_number,
                           r.spend_amount,r.currency_code
                    FROM ads_report_cost_row r
                    JOIN ads_report_import b ON b.tenant_id=r.tenant_id AND b.id=r.batch_id
                    WHERE b.tenant_id=? AND b.seller_marketplace_id=? AND b.id=?
                    """,tenant,marketplace,batchId);
            }catch(DuplicateKeyException duplicate){
                throw conflict("Overlapping Ads profile/day/campaign already reviewed; do not double-count");
            }
        }
        jdbc.update("""
            UPDATE ads_report_import SET state=?,reviewed_by=?,reviewed_at=now(),review_note=?
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            ""","APPROVE".equals(action.decision())?"REVIEWED_UNVERIFIED":"REJECTED",
            actor,action.note().strip(),tenant,marketplace,batchId);
        return report(tenant,marketplace,batchId);
    }
    public List<Daily> daily(UUID tenant,UUID marketplace){
        marketplaceCurrency(tenant,marketplace);
        return jdbc.query("""
            SELECT c.profile_id,p.profile_reference,c.business_date,c.campaign_reference,
                   c.spend_amount,c.currency_code,b.evidence_origin,b.report_reference
            FROM ads_report_reviewed_cost c
            JOIN ads_profile_reference p ON p.tenant_id=c.tenant_id AND p.id=c.profile_id
            JOIN ads_report_import b ON b.tenant_id=c.tenant_id AND b.id=c.batch_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
            ORDER BY c.business_date DESC,c.profile_id,c.campaign_reference LIMIT 500
            """,(r,i)->new Daily(r.getObject(1,UUID.class).toString(),
                r.getString(2),r.getObject(3,LocalDate.class).toString(),
                r.getString(4),r.getBigDecimal(5).toPlainString(),r.getString(6).trim(),
                r.getString(7),r.getString(8),false,false),tenant,marketplace);
    }
}
