package org.merchivra.erp.fba;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * V65: local inbound intent and self-reported receipt evidence only. Never calls
 * SP-API, increments FBA fulfillable, posts local warehouse stock or books COGS.
 * Parent row FOR UPDATE serializes all state transitions, receipt and review.
 */
@Service
public class FbaInboundService {
    private final JdbcTemplate jdbc;
    public FbaInboundService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record LineInput(String sellerSku,int quantity){}
    public record Create(UUID requestKey,String reference,String origin,List<LineInput> lines){}
    public record Receipt(UUID requestKey,String sellerSku,int quantity,
                          String evidenceReference,String origin){}
    public record Resolve(String sellerSku,String note){}
    public record Shipment(String id,String reference,String origin,String state,
                           String createdAt,String dispatchedAt,String reviewStartedAt,
                           String reconciledAt){}
    public record Line(String id,String sellerSku,int plannedQuantity,int reportedReceived,
                       Integer estimatedInTransit,int delta,String decision,String reviewedBy,
                       String reviewNote,String reviewedAt){}
    public record TransitRow(String sellerSku,String origin,long planned,long reportedReceived,
                             long estimatedInTransit,boolean amazonVerified){}
    public record ReceiptEvent(String id,String sellerSku,int quantity,String evidenceReference,
                               String origin,String recordedBy,String recordedAt){}
    public record Detail(Shipment shipment,List<Line> lines,List<ReceiptEvent> receipts,
                         boolean amazonVerified,boolean fbaStockCredited){}
    private record Locked(UUID id,String state,String origin){}

    private static ResponseStatusException bad(String message){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found in this tenant and Marketplace");
    }
    private static String safe(String value,int maximum,String label){
        if(value==null||value.isBlank()||value.length()>maximum||
           value.chars().anyMatch(Character::isISOControl))
            throw bad("Invalid "+label);
        return value.strip();
    }
    private static String sku(String value){return safe(value,200,"Seller SKU");}
    private static String origin(String value){
        if(!"MOCK".equals(value)&&!"MANUAL_UNVERIFIED".equals(value))
            throw bad("Only MOCK or MANUAL_UNVERIFIED local evidence is supported");
        return value;
    }
    static Integer estimatedInTransit(String state,int planned,int received){
        return "IN_TRANSIT".equals(state)||"RECEIVING".equals(state)
            ?Math.max(0,planned-received):null;
    }
    private static String timestamp(ResultSet r,String name)throws SQLException{
        OffsetDateTime date=r.getObject(name,OffsetDateTime.class);
        return date==null?null:date.toInstant().toString();
    }
    private static String digest(String value){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    static void validateLines(List<LineInput> lines){
        if(lines==null||lines.isEmpty()||lines.size()>100)
            throw bad("A shipment requires 1..100 lines");
        Set<String> distinct=new HashSet<>();
        for(LineInput line:lines){
            if(line==null||!distinct.add(sku(line.sellerSku()))||
               line.quantity()<1||line.quantity()>1000000)
                throw bad("Invalid or repeated shipment SKU/quantity");
        }
    }
    private static String fingerprint(Create request,String reference,String evidenceOrigin){
        StringBuilder canonical=new StringBuilder(reference.length()+1000)
            .append(reference.length()).append(':').append(reference)
            .append('|').append(evidenceOrigin);
        request.lines().stream().sorted(Comparator.comparing(LineInput::sellerSku))
            .forEach(line->canonical.append('|').append(line.sellerSku().length())
                .append(':').append(line.sellerSku()).append(':').append(line.quantity()));
        return digest(canonical.toString());
    }
    private void requireMarketplace(UUID tenant,UUID marketplace){
        var ids=jdbc.queryForList("""
            SELECT m.id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,UUID.class,tenant,marketplace);
        if(ids.size()!=1)throw missing();
    }
    private Locked lock(UUID tenant,UUID marketplace,UUID shipment){
        var rows=jdbc.query("""
            SELECT id,state,evidence_origin FROM fba_inbound_shipment
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=? FOR UPDATE
            """,(r,i)->new Locked(r.getObject("id",UUID.class),
                r.getString("state"),r.getString("evidence_origin")),tenant,marketplace,shipment);
        if(rows.isEmpty())throw missing();
        return rows.getFirst();
    }
    private static Shipment mapShipment(ResultSet r,int row)throws SQLException{
        return new Shipment(r.getObject("id",UUID.class).toString(),
            r.getString("reference"),r.getString("evidence_origin"),r.getString("state"),
            timestamp(r,"created_at"),timestamp(r,"dispatched_at"),
            timestamp(r,"review_started_at"),timestamp(r,"reconciled_at"));
    }
    public List<Shipment> list(UUID tenant,UUID marketplace){
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT id,reference,evidence_origin,state,created_at,dispatched_at,
                   review_started_at,reconciled_at
            FROM fba_inbound_shipment
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,FbaInboundService::mapShipment,tenant,marketplace);
    }
    /**
     * Advisory in-transit, grouped BY SKU AND origin. This is not Amazon inbound
     * and MUST NOT be added to FBA available quantity or warehouse on-hand.
     */
    public List<TransitRow> transit(UUID tenant,UUID marketplace){
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            WITH per_line AS (
                SELECT l.seller_sku,s.evidence_origin,l.planned_quantity,
                       COALESCE(SUM(e.quantity),0)::bigint AS received
                FROM fba_inbound_shipment s
                JOIN fba_inbound_line l ON l.tenant_id=s.tenant_id AND l.shipment_id=s.id
                LEFT JOIN fba_inbound_receipt_event e ON e.tenant_id=l.tenant_id
                    AND e.shipment_id=l.shipment_id AND e.line_id=l.id
                WHERE s.tenant_id=? AND s.seller_marketplace_id=?
                  AND s.state IN ('IN_TRANSIT','RECEIVING')
                GROUP BY s.id,l.id,l.seller_sku,s.evidence_origin,l.planned_quantity
            )
            SELECT seller_sku,evidence_origin,SUM(planned_quantity)::bigint AS planned,
                   SUM(received)::bigint AS reported,
                   SUM(GREATEST(0,planned_quantity-received))::bigint AS transit
            FROM per_line
            GROUP BY seller_sku,evidence_origin
            ORDER BY seller_sku,evidence_origin LIMIT 500
            """,(r,i)->new TransitRow(r.getString("seller_sku"),
                r.getString("evidence_origin"),r.getLong("planned"),
                r.getLong("reported"),r.getLong("transit"),false),tenant,marketplace);
    }
    public Detail detail(UUID tenant,UUID marketplace,UUID shipment){
        List<Shipment> header=jdbc.query("""
            SELECT id,reference,evidence_origin,state,created_at,dispatched_at,
                   review_started_at,reconciled_at
            FROM fba_inbound_shipment WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,FbaInboundService::mapShipment,tenant,marketplace,shipment);
        if(header.isEmpty())throw missing();
        Shipment h=header.getFirst();
        List<Line> lines=jdbc.query("""
            SELECT l.id,l.seller_sku,l.planned_quantity,
              COALESCE(SUM(e.quantity),0)::integer AS received,
              v.decision,v.reviewed_by,v.note,v.reviewed_at
            FROM fba_inbound_line l
            LEFT JOIN fba_inbound_receipt_event e
              ON e.tenant_id=l.tenant_id AND e.shipment_id=l.shipment_id AND e.line_id=l.id
            LEFT JOIN fba_inbound_variance_resolution v
              ON v.tenant_id=l.tenant_id AND v.shipment_id=l.shipment_id AND v.line_id=l.id
            WHERE l.tenant_id=? AND l.shipment_id=?
            GROUP BY l.id,l.seller_sku,l.planned_quantity,v.decision,v.reviewed_by,v.note,v.reviewed_at
            ORDER BY l.seller_sku
            """,(r,i)->{
                int planned=r.getInt("planned_quantity"),received=r.getInt("received");
                UUID reviewed=r.getObject("reviewed_by",UUID.class);
                return new Line(r.getObject("id",UUID.class).toString(),
                    r.getString("seller_sku"),planned,received,
                    estimatedInTransit(h.state(),planned,received),
                    received-planned,r.getString("decision"),
                    reviewed==null?null:reviewed.toString(),
                    r.getString("note"),timestamp(r,"reviewed_at"));
            },tenant,shipment);
        List<ReceiptEvent> receipts=jdbc.query("""
            SELECT e.id,l.seller_sku,e.quantity,e.evidence_reference,e.evidence_origin,
                   e.recorded_by,e.recorded_at
            FROM fba_inbound_receipt_event e
            JOIN fba_inbound_line l ON l.tenant_id=e.tenant_id
                AND l.shipment_id=e.shipment_id AND l.id=e.line_id
            WHERE e.tenant_id=? AND e.shipment_id=?
            ORDER BY e.recorded_at,e.id LIMIT 1000
            """,(r,i)->new ReceiptEvent(r.getObject("id",UUID.class).toString(),
                r.getString("seller_sku"),r.getInt("quantity"),
                r.getString("evidence_reference"),r.getString("evidence_origin"),
                r.getObject("recorded_by",UUID.class).toString(),
                timestamp(r,"recorded_at")),tenant,shipment);
        return new Detail(h,lines,receipts,false,false);
    }

    @Transactional
    public Detail create(UUID tenant,UUID marketplace,UUID actor,Create input){
        if(input==null||input.requestKey()==null||actor==null)throw bad("Request key is required");
        String reference=safe(input.reference(),100,"shipment reference");
        String evidence=origin(input.origin());
        validateLines(input.lines());
        String hash=fingerprint(input,reference,evidence);
        requireMarketplace(tenant,marketplace);
        var existing=jdbc.query("""
            SELECT id,request_sha256 FROM fba_inbound_shipment
            WHERE tenant_id=? AND seller_marketplace_id=? AND request_key=?
            """,(r,i)->new String[]{r.getObject("id",UUID.class).toString(),
                    r.getString("request_sha256")},tenant,marketplace,input.requestKey());
        if(!existing.isEmpty()){
            if(!hash.equals(existing.getFirst()[1]))throw conflict("Request key reused with changed payload");
            return detail(tenant,marketplace,UUID.fromString(existing.getFirst()[0]));
        }
        for(LineInput line:input.lines()){
            var listings=jdbc.queryForList("""
                SELECT id FROM marketplace_listing
                WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
                """,UUID.class,tenant,marketplace,line.sellerSku());
            if(listings.size()!=1)throw bad("Seller SKU is not a listing in this Marketplace");
        }
        UUID id=UUID.randomUUID();
        try{
            jdbc.update("""
                INSERT INTO fba_inbound_shipment
                  (id,tenant_id,seller_marketplace_id,request_key,request_sha256,
                   reference,evidence_origin,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                """,id,tenant,marketplace,input.requestKey(),hash,reference,evidence,actor);
        }catch(DuplicateKeyException ex){
            throw conflict("Shipment reference or request key already exists; retry the original request");
        }
        for(LineInput line:input.lines()){
            jdbc.update("""
                INSERT INTO fba_inbound_line
                 (id,tenant_id,shipment_id,seller_sku,planned_quantity)
                VALUES(?,?,?,?,?)
                """,UUID.randomUUID(),tenant,id,line.sellerSku(),line.quantity());
        }
        return detail(tenant,marketplace,id);
    }
    @Transactional
    public Detail dispatch(UUID tenant,UUID marketplace,UUID shipment){
        Locked current=lock(tenant,marketplace,shipment);
        if(!"DRAFT".equals(current.state())){
            if("IN_TRANSIT".equals(current.state()))return detail(tenant,marketplace,shipment);
            throw conflict("Only DRAFT shipments can be dispatched");
        }
        jdbc.update("""
            UPDATE fba_inbound_shipment SET state='IN_TRANSIT',
                   dispatched_at=now(),updated_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,shipment);
        return detail(tenant,marketplace,shipment);
    }
    @Transactional
    public Detail receipt(UUID tenant,UUID marketplace,UUID shipment,UUID actor,Receipt body){
        if(body==null||body.requestKey()==null||actor==null)throw bad("Receipt request key is required");
        String sellerSku=sku(body.sellerSku());
        String evidenceReference=safe(body.evidenceReference(),200,"evidence reference");
        String evidenceOrigin=origin(body.origin());
        if(body.quantity()<1||body.quantity()>1000000)throw bad("Invalid received quantity");
        Locked header=lock(tenant,marketplace,shipment);
        var old=jdbc.query("""
            SELECT l.seller_sku,e.quantity,e.evidence_reference,e.evidence_origin
            FROM fba_inbound_receipt_event e
            JOIN fba_inbound_line l ON l.tenant_id=e.tenant_id
                 AND l.shipment_id=e.shipment_id AND l.id=e.line_id
            WHERE e.tenant_id=? AND e.shipment_id=? AND e.request_key=?
            """,(r,i)->new Object[]{r.getString(1),r.getInt(2),r.getString(3),r.getString(4)},
            tenant,shipment,body.requestKey());
        if(!old.isEmpty()){
            Object[] previous=old.getFirst();
            if(!sellerSku.equals(previous[0])||body.quantity()!=(int)previous[1]||
                !evidenceReference.equals(previous[2])||!evidenceOrigin.equals(previous[3]))
                throw conflict("Receipt key reused with changed evidence");
            return detail(tenant,marketplace,shipment);
        }
        if(!"IN_TRANSIT".equals(header.state())&&!"RECEIVING".equals(header.state()))
            throw conflict("Shipment does not accept new receipt evidence");
        if(!header.origin().equals(evidenceOrigin))
            throw bad("Receipt origin must match the shipment origin");
        var lines=jdbc.query("""
            SELECT id,planned_quantity FROM fba_inbound_line
            WHERE tenant_id=? AND shipment_id=? AND seller_sku=?
            """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getInt(2)},
            tenant,shipment,sellerSku);
        if(lines.isEmpty())throw bad("Seller SKU is not part of the shipment");
        UUID lineId=(UUID)lines.getFirst()[0];
        int planned=(int)lines.getFirst()[1];
        Long total=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint FROM fba_inbound_receipt_event
            WHERE tenant_id=? AND shipment_id=? AND line_id=?
            """,Long.class,tenant,shipment,lineId);
        if(total==null||total+body.quantity()>Math.min(2000000L,planned*2L))
            throw bad("Received quantity exceeds allowed review range (2x planned)");
        jdbc.update("""
            INSERT INTO fba_inbound_receipt_event
              (id,tenant_id,shipment_id,line_id,request_key,quantity,
               evidence_reference,evidence_origin,recorded_by)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,shipment,lineId,body.requestKey(),
            body.quantity(),evidenceReference,evidenceOrigin,actor);
        jdbc.update("""
            UPDATE fba_inbound_shipment SET state='RECEIVING',updated_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,shipment);
        return detail(tenant,marketplace,shipment);
    }
    @Transactional
    public Detail startReview(UUID tenant,UUID marketplace,UUID shipment){
        Locked current=lock(tenant,marketplace,shipment);
        if("REVIEWING".equals(current.state()))return detail(tenant,marketplace,shipment);
        if(!"RECEIVING".equals(current.state())&&!"IN_TRANSIT".equals(current.state()))
            throw conflict("Shipment must be dispatched before review");
        jdbc.update("""
            UPDATE fba_inbound_shipment SET state='REVIEWING',
                review_started_at=now(),updated_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,shipment);
        return detail(tenant,marketplace,shipment);
    }
    @Transactional
    public Detail resolve(UUID tenant,UUID marketplace,UUID shipment,UUID actor,Resolve body){
        if(body==null||actor==null)throw bad("Variance review requires a reviewer");
        String sellerSku=sku(body.sellerSku());
        String note=safe(body.note(),500,"variance explanation");
        if(note.length()<10)throw bad("Variance explanation must have at least 10 characters");
        Locked current=lock(tenant,marketplace,shipment);
        if(!"REVIEWING".equals(current.state()))throw conflict("Start review before resolving variance");
        Detail snapshot=detail(tenant,marketplace,shipment);
        Line line=snapshot.lines().stream().filter(l->l.sellerSku().equals(sellerSku))
            .findFirst().orElseThrow(()->bad("Unknown shipment SKU"));
        if(line.delta()==0)throw conflict("No variance exists for this line");
        if(line.decision()!=null)throw conflict("Variance already reviewed; resolution is immutable");
        Integer authored=jdbc.queryForObject("""
            SELECT COUNT(*) FROM fba_inbound_receipt_event
            WHERE tenant_id=? AND shipment_id=? AND line_id=? AND recorded_by=?
            """,Integer.class,tenant,shipment,UUID.fromString(line.id()),actor);
        if(authored!=null&&authored>0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Variance reviewer must differ from receipt evidence author");
        jdbc.update("""
            INSERT INTO fba_inbound_variance_resolution
              (id,tenant_id,shipment_id,line_id,observed_quantity,planned_quantity,
               decision,note,reviewed_by)
            VALUES(?,?,?,?,?,?,'ACCEPT_VARIANCE',?,?)
            """,UUID.randomUUID(),tenant,shipment,UUID.fromString(line.id()),
                line.reportedReceived(),line.plannedQuantity(),note,actor);
        return detail(tenant,marketplace,shipment);
    }
    @Transactional
    public Detail finalizeReview(UUID tenant,UUID marketplace,UUID shipment){
        Locked header=lock(tenant,marketplace,shipment);
        if("RECONCILED".equals(header.state()))return detail(tenant,marketplace,shipment);
        if(!"REVIEWING".equals(header.state()))throw conflict("Start review first");
        Detail snapshot=detail(tenant,marketplace,shipment);
        if(snapshot.lines().stream().anyMatch(l->l.delta()!=0&&!"ACCEPT_VARIANCE".equals(l.decision())))
            throw conflict("Every under/over receipt variance requires independent review");
        jdbc.update("""
            UPDATE fba_inbound_shipment SET state='RECONCILED',
               reconciled_at=now(),updated_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,shipment);
        return detail(tenant,marketplace,shipment);
    }
}
