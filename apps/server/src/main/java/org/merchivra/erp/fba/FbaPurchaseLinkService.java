package org.merchivra.erp.fba;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V75: a human-asserted link of local FBA shipment INTENT to an approved
 * local purchase line and (optionally) a shipped supplier ASN. Two different
 * Owners confirm identity. Neither identity proof nor reported FBA receipts
 * are an Amazon acknowledgment, carrier delivery, inventory movement or
 * quantity allocation against local receipts.
 */
@Service
public class FbaPurchaseLinkService {
    private final JdbcTemplate jdbc;
    public FbaPurchaseLinkService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Propose(UUID requestKey,UUID inboundLineId,UUID purchaseOrderLineId,
                          @Schema(types={"string","null"},format="uuid") UUID asnId,String proofReference) {}
    public record Candidate(String purchaseOrderId,String purchaseOrderLineId,
        String orderNumber,String supplierName,int orderedQuantity,
        long localReceivedQuantity,
        @Schema(types={"string","null"}) String asnId,
        @Schema(types={"string","null"}) String asnStatus,
        @Schema(types={"integer","null"},format="int32") Integer asnQuantity,long asnReceivedQuantity) {}
    public record Linked(String id,String shipmentId,String inboundLineId,
        String sellerSku,String shipmentState,String shipmentOrigin,int inboundPlannedQuantity,
        long inboundReportedReceived,String purchaseOrderId,String purchaseOrderLineId,
        String orderNumber,String purchaseState,int poOrderedQuantity,
        long localPoReceived,
        @Schema(types={"string","null"}) String asnId,
        @Schema(types={"string","null"}) String asnStatus,
        @Schema(types={"integer","null"},format="int32") Integer asnQuantity,
        @Schema(types={"integer","null"},format="int64") Long asnLocalReceived,
        String state,String proofReference,String proposedBy,
        @Schema(types={"string","null"}) String confirmedBy,String createdAt,
        @Schema(types={"string","null"}) String confirmedAt) {}
    public record Changed(String id,String state) {}
    public record LinkedPage(List<Linked> items,
        @Schema(types={"string","null"}) String nextCursor,boolean hasMore,int pageSize) {}
    public record CandidatePage(List<Candidate> items,
        @Schema(types={"string","null"}) String nextCursor,boolean hasMore,int pageSize) {}
    private static ResponseStatusException bad(String value){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,value);
    }
    private static ResponseStatusException conflict(String value){
        return new ResponseStatusException(HttpStatus.CONFLICT,value);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Local shipment/PO/ASN linkage not available for current Marketplace");
    }
    static String proof(String value) {
        if(value==null || value.strip().length()<10 || value.strip().length()>200
           || value.chars().anyMatch(Character::isISOControl))
            throw bad("Explicit proof reference must contain 10..200 printable characters");
        return value.strip();
    }
    private record Line(UUID line,UUID shipment,int planned,String sku,String state) {}
    private Line line(UUID tenant,UUID marketplace,UUID shipment,UUID line,boolean lock){
        String sql="""
            SELECT l.id,l.shipment_id,l.planned_quantity,l.seller_sku,s.state
            FROM fba_inbound_shipment s
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            JOIN fba_inbound_line l ON l.tenant_id=s.tenant_id AND l.shipment_id=s.id
            WHERE s.tenant_id=? AND s.seller_marketplace_id=?
              AND s.id=? AND l.id=?
            """+(lock?" FOR UPDATE OF s":"");
        var rows=jdbc.query(sql,(r,i)->new Line(
            r.getObject("id",UUID.class),r.getObject("shipment_id",UUID.class),
            r.getInt("planned_quantity"),r.getString("seller_sku"),
            r.getString("state")),tenant,marketplace,shipment,line);
        if(rows.size()!=1)throw missing();
        return rows.getFirst();
    }
    private record Purchase(UUID po,UUID line,String status,int ordered){}
    private Purchase eligible(UUID tenant,UUID marketplace,Line inbound,UUID poLine) {
        // A single ACTIVE listing+mapping for this EXACT marketplace Seller SKU
        // must resolve to the PO line's product SKU. Matching SKU strings alone
        // or matching quantities do not establish provenance.
        var rows=jdbc.query("""
            SELECT po.id,pl.id,po.status,pl.ordered_quantity
            FROM erp_purchase_order_line pl
            JOIN erp_purchase_order po ON po.tenant_id=pl.tenant_id
              AND po.id=pl.purchase_order_id
            JOIN marketplace_listing listing ON listing.tenant_id=pl.tenant_id
              AND listing.seller_marketplace_id=? AND listing.seller_sku=?
              AND listing.mapping_status='ACTIVE'
            JOIN listing_sku_mapping mapping ON mapping.tenant_id=listing.tenant_id
              AND mapping.listing_id=listing.id AND mapping.status='ACTIVE'
              AND mapping.valid_to IS NULL
              AND mapping.product_sku_id=pl.product_sku_id
            JOIN product_sku product ON product.tenant_id=pl.tenant_id
              AND product.id=pl.product_sku_id AND product.status='ACTIVE'
            WHERE pl.tenant_id=? AND pl.id=?
              AND po.status IN ('APPROVED','RECEIVING','RECEIVED')
            """,(r,i)->new Purchase(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getString(3),r.getInt(4)),marketplace,inbound.sku(),tenant,poLine);
        if(rows.size()!=1)throw conflict(
            "Purchase line must be approved and mapped to the exact active marketplace Seller SKU");
        return rows.getFirst();
    }
    private static String at(ResultSet r,String field)throws SQLException {
        OffsetDateTime v=r.getObject(field,OffsetDateTime.class);
        return v==null?null:v.toInstant().toString();
    }
    /** V147 pages are live local views; cursors are bound to the authenticated scope. */
    private static final UUID NO_ASN=UUID.fromString("00000000-0000-0000-0000-000000000000");
    private record LinkAfter(OffsetDateTime createdAt,UUID id) {}
    private record CandidateAfter(OffsetDateTime updatedAt,UUID po,UUID line,UUID asn) {}
    private record CandidateRow(Candidate candidate,CandidateAfter position) {}

    private static LinkAfter linkAfter(String cursor,UUID tenant,UUID marketplace,UUID shipment,
                                       UUID inboundLine,String state) {
        String[] parts=LocalKeysetPage.decode(cursor,7);
        if(parts==null)return null;
        String selected=inboundLine==null?"ALL":inboundLine.toString();
        if(!tenant.toString().equals(parts[0])||!marketplace.toString().equals(parts[1])
            ||!shipment.toString().equals(parts[2])||!selected.equals(parts[3])
            ||!state.equals(parts[4]))throw bad("FBA link cursor scope mismatch");
        try {
            return new LinkAfter(OffsetDateTime.ofInstant(Instant.parse(parts[5]),ZoneOffset.UTC),
                UUID.fromString(parts[6]));
        }catch(IllegalArgumentException | java.time.format.DateTimeParseException ex) {throw bad("Invalid FBA link cursor");}
    }
    private static CandidateAfter candidateAfter(String cursor,UUID tenant,UUID marketplace,
                                                  UUID shipment,UUID inboundLine,UUID poLine) {
        String[] parts=LocalKeysetPage.decode(cursor,9);
        if(parts==null)return null;
        if(!tenant.toString().equals(parts[0])||!marketplace.toString().equals(parts[1])
            ||!shipment.toString().equals(parts[2])||!inboundLine.toString().equals(parts[3])
            ||!(poLine==null?"ALL":poLine.toString()).equals(parts[4]))
            throw bad("FBA candidate cursor scope mismatch");
        try {
            return new CandidateAfter(OffsetDateTime.ofInstant(Instant.parse(parts[5]),ZoneOffset.UTC),
                UUID.fromString(parts[6]),UUID.fromString(parts[7]),UUID.fromString(parts[8]));
        }catch(IllegalArgumentException | java.time.format.DateTimeParseException ex){throw bad("Invalid FBA candidate cursor");}
    }

    private static final String LINK_QUERY = """
        SELECT link.id,link.shipment_id,link.inbound_line_id,il.seller_sku,
          sh.state AS shipment_state,sh.evidence_origin,il.planned_quantity,
          COALESCE((SELECT SUM(e.quantity) FROM fba_inbound_receipt_event e
            WHERE e.tenant_id=il.tenant_id AND e.line_id=il.id
              AND e.shipment_id=il.shipment_id),0)::bigint AS inbound_received,
          po.id AS po_id,pl.id AS po_line_id,po.order_number,
          po.status AS po_state,pl.ordered_quantity,
          COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
            WHERE r.tenant_id=pl.tenant_id AND r.purchase_order_line_id=pl.id),0)
            ::bigint AS local_received,
          asn.id AS asn_id,asn.status AS asn_status,asn.quantity AS asn_quantity,
          (SELECT COALESCE(SUM(r.quantity),0)::bigint FROM erp_purchase_receipt r
            WHERE r.tenant_id=link.tenant_id AND r.asn_id=asn.id) AS asn_local_received,
          link.state,link.proof_reference,link.proposed_by,link.confirmed_by,
          link.created_at,link.confirmed_at
        FROM fba_inbound_purchase_link link
        JOIN fba_inbound_shipment sh ON sh.tenant_id=link.tenant_id
          AND sh.id=link.shipment_id
        JOIN fba_inbound_line il ON il.tenant_id=link.tenant_id
          AND il.shipment_id=link.shipment_id AND il.id=link.inbound_line_id
        JOIN erp_purchase_order_line pl ON pl.tenant_id=link.tenant_id
          AND pl.id=link.purchase_order_line_id
        JOIN erp_purchase_order po ON po.tenant_id=link.tenant_id
          AND po.id=link.purchase_order_id
        LEFT JOIN erp_purchase_asn asn ON asn.tenant_id=link.tenant_id
          AND asn.id=link.asn_id
        WHERE link.tenant_id=? AND sh.seller_marketplace_id=?
        """;
    private static Linked map(ResultSet r,int i)throws SQLException{
        UUID asn=r.getObject("asn_id",UUID.class);
        UUID confirmed=r.getObject("confirmed_by",UUID.class);
        Integer asnQty=(Integer)r.getObject("asn_quantity");
        return new Linked(r.getObject("id",UUID.class).toString(),
            r.getObject("shipment_id",UUID.class).toString(),
            r.getObject("inbound_line_id",UUID.class).toString(),
            r.getString("seller_sku"),r.getString("shipment_state"),
            r.getString("evidence_origin"),r.getInt("planned_quantity"),
            r.getLong("inbound_received"),
            r.getObject("po_id",UUID.class).toString(),
            r.getObject("po_line_id",UUID.class).toString(),
            r.getString("order_number"),r.getString("po_state"),
            r.getInt("ordered_quantity"),r.getLong("local_received"),
            asn==null?null:asn.toString(),r.getString("asn_status"),asnQty,
            asn==null?null:r.getLong("asn_local_received"),
            r.getString("state"),r.getString("proof_reference"),
            r.getObject("proposed_by",UUID.class).toString(),
            confirmed==null?null:confirmed.toString(),
            at(r,"created_at"),at(r,"confirmed_at"));
    }
    @Transactional(readOnly=true)
    public List<Linked> links(UUID tenant,UUID marketplace,UUID shipment){
        // Check even when no links: never allow querying unrelated shipment IDs.
        var exists=jdbc.queryForList("""
            SELECT sh.id FROM fba_inbound_shipment sh JOIN tenant t
              ON t.id=sh.tenant_id AND t.status='ACTIVE'
            WHERE sh.tenant_id=? AND sh.seller_marketplace_id=? AND sh.id=?
            """,UUID.class,tenant,marketplace,shipment);
        if(exists.size()!=1)throw missing();
        return jdbc.query(LINK_QUERY+"""
            AND link.shipment_id=? ORDER BY link.created_at DESC,link.id DESC
            LIMIT 200
            """,FbaPurchaseLinkService::map,tenant,marketplace,shipment);
    }
    @Transactional(readOnly=true)
    public List<Candidate> candidates(UUID tenant,UUID marketplace,UUID shipment,UUID inboundLine){
        Line selected=line(tenant,marketplace,shipment,inboundLine,false);
        return jdbc.query("""
            SELECT po.id AS order_id,pl.id AS line_id,po.order_number,
              sup.display_name,pl.ordered_quantity,
              COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=pl.tenant_id AND r.purchase_order_line_id=pl.id),0)
                ::bigint AS received_quantity,
              a.id AS asn_id,a.status AS asn_status,a.quantity AS asn_quantity,
              COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id),0)
                ::bigint AS asn_received
            FROM marketplace_listing listing
            JOIN listing_sku_mapping mapping ON mapping.tenant_id=listing.tenant_id
              AND mapping.listing_id=listing.id AND mapping.status='ACTIVE'
              AND mapping.valid_to IS NULL
            JOIN product_sku p ON p.tenant_id=mapping.tenant_id
              AND p.id=mapping.product_sku_id AND p.status='ACTIVE'
            JOIN erp_purchase_order_line pl ON pl.tenant_id=p.tenant_id
              AND pl.product_sku_id=p.id
            JOIN erp_purchase_order po ON po.tenant_id=pl.tenant_id
              AND po.id=pl.purchase_order_id
              AND po.status IN ('APPROVED','RECEIVING','RECEIVED')
            JOIN erp_supplier sup ON sup.tenant_id=po.tenant_id AND sup.id=po.supplier_id
            LEFT JOIN erp_purchase_asn a ON a.tenant_id=pl.tenant_id
              AND a.purchase_order_line_id=pl.id AND a.status='SHIPPED'
            WHERE listing.tenant_id=? AND listing.seller_marketplace_id=?
              AND listing.seller_sku=? AND listing.mapping_status='ACTIVE'
            ORDER BY po.updated_at DESC,po.id DESC,a.id LIMIT 100
            """,(r,i)->new Candidate(r.getObject("order_id",UUID.class).toString(),
                r.getObject("line_id",UUID.class).toString(),
                r.getString("order_number"),r.getString("display_name"),
                r.getInt("ordered_quantity"),r.getLong("received_quantity"),
                r.getObject("asn_id",UUID.class)==null?null:
                  r.getObject("asn_id",UUID.class).toString(),
                r.getString("asn_status"),(Integer)r.getObject("asn_quantity"),
                r.getLong("asn_received")),
            tenant,marketplace,selected.sku());
    }

    /** Full V147 shipment-link history; legacy LIMIT 200 read is kept for old consumers. */
    @Transactional(readOnly=true)
    public LinkedPage linksPage(UUID tenant,UUID marketplace,UUID shipment,
                                                  UUID inboundLine,String state,int requested,
                                                  String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String checked=state==null?"ALL":state;
        if(!Set.of("ALL","PROPOSED","CONFIRMED","REVOKED").contains(checked))
            throw bad("Invalid FBA link state");
        LinkAfter after=linkAfter(cursor,tenant,marketplace,shipment,inboundLine,checked);
        var exists=jdbc.queryForList("""
            SELECT sh.id FROM fba_inbound_shipment sh JOIN tenant t
              ON t.id=sh.tenant_id AND t.status='ACTIVE'
            WHERE sh.tenant_id=? AND sh.seller_marketplace_id=? AND sh.id=?
            """,UUID.class,tenant,marketplace,shipment);
        if(exists.size()!=1)throw missing();
        if(inboundLine!=null)line(tenant,marketplace,shipment,inboundLine,false);
        List<Object> args=new ArrayList<>(List.of(tenant,marketplace,shipment));
        StringBuilder sql=new StringBuilder(LINK_QUERY).append(" AND link.shipment_id=?");
        if(inboundLine!=null){sql.append(" AND link.inbound_line_id=?");args.add(inboundLine);}
        if(!"ALL".equals(checked)){sql.append(" AND link.state=?");args.add(checked);}
        if(after!=null){
            sql.append(" AND (link.created_at,link.id)<(?::timestamptz,?::uuid)");
            args.add(after.createdAt());args.add(after.id());
        }
        sql.append(" ORDER BY link.created_at DESC,link.id DESC LIMIT ?");
        args.add(limit+1);
        List<Linked> fetched=jdbc.query(sql.toString(),FbaPurchaseLinkService::map,args.toArray());
        var page=LocalKeysetPage.pack(fetched,limit,last->LocalKeysetPage.encode(
            tenant.toString(),marketplace.toString(),shipment.toString(),
            inboundLine==null?"ALL":inboundLine.toString(),checked,last.createdAt(),last.id()));
        return new LinkedPage(page.items(),page.nextCursor(),page.hasMore(),page.pageSize());
    }

    private static CandidateRow mapCandidateRow(ResultSet r,int i)throws SQLException {
        UUID asn=r.getObject("asn_id",UUID.class);
        Candidate value=new Candidate(r.getObject("order_id",UUID.class).toString(),
            r.getObject("line_id",UUID.class).toString(),r.getString("order_number"),
            r.getString("display_name"),r.getInt("ordered_quantity"),
            r.getLong("received_quantity"),asn==null?null:asn.toString(),
            r.getString("asn_status"),(Integer)r.getObject("asn_quantity"),
            r.getLong("asn_received"));
        return new CandidateRow(value,new CandidateAfter(
            r.getObject("position_updated_at",OffsetDateTime.class),
            r.getObject("order_id",UUID.class),r.getObject("line_id",UUID.class),
            asn==null?NO_ASN:asn));
    }

    /** Full approved PO+optional SHIPPED ASN source candidates for one inbound line. */
    @Transactional(readOnly=true)
    public CandidatePage candidatesPage(UUID tenant,UUID marketplace,
                        UUID shipment,UUID inboundLine,UUID purchaseLine,int requested,
                        String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        CandidateAfter after=candidateAfter(cursor,tenant,marketplace,shipment,inboundLine,
            purchaseLine);
        Line selected=line(tenant,marketplace,shipment,inboundLine,false);
        List<Object> args=new ArrayList<>(List.of(tenant,marketplace,selected.sku()));
        StringBuilder sql=new StringBuilder("""
            SELECT po.id AS order_id,pl.id AS line_id,po.order_number,
              sup.display_name,pl.ordered_quantity,po.updated_at AS position_updated_at,
              COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=pl.tenant_id AND r.purchase_order_line_id=pl.id),0)
                ::bigint AS received_quantity,
              a.id AS asn_id,a.status AS asn_status,a.quantity AS asn_quantity,
              COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id),0)
                ::bigint AS asn_received
            FROM erp_purchase_order_line pl
            JOIN erp_purchase_order po ON po.tenant_id=pl.tenant_id
              AND po.id=pl.purchase_order_id
              AND po.status IN ('APPROVED','RECEIVING','RECEIVED')
            JOIN erp_supplier sup ON sup.tenant_id=po.tenant_id AND sup.id=po.supplier_id
            LEFT JOIN erp_purchase_asn a ON a.tenant_id=pl.tenant_id
              AND a.purchase_order_line_id=pl.id AND a.status='SHIPPED'
            WHERE pl.tenant_id=? AND EXISTS (
              SELECT 1 FROM marketplace_listing listing
              JOIN listing_sku_mapping mapping ON mapping.tenant_id=listing.tenant_id
                AND mapping.listing_id=listing.id AND mapping.status='ACTIVE'
                AND mapping.valid_to IS NULL
              JOIN product_sku product ON product.tenant_id=mapping.tenant_id
                AND product.id=mapping.product_sku_id AND product.status='ACTIVE'
              WHERE listing.tenant_id=pl.tenant_id AND listing.seller_marketplace_id=?
                AND listing.seller_sku=? AND listing.mapping_status='ACTIVE'
                AND mapping.product_sku_id=pl.product_sku_id
            )
            """);
        if(purchaseLine!=null){sql.append(" AND pl.id=?");args.add(purchaseLine);}
        if(after!=null){
            sql.append("""
             AND (po.updated_at,po.id,pl.id,COALESCE(a.id,
                     '00000000-0000-0000-0000-000000000000'::uuid))
                < (?::timestamptz,?::uuid,?::uuid,?::uuid)
            """);
            args.add(after.updatedAt());args.add(after.po());args.add(after.line());args.add(after.asn());
        }
        sql.append("""
            ORDER BY po.updated_at DESC,po.id DESC,pl.id DESC,
              COALESCE(a.id,'00000000-0000-0000-0000-000000000000'::uuid) DESC
            LIMIT ?
            """);
        args.add(limit+1);
        List<CandidateRow> fetched=jdbc.query(sql.toString(),FbaPurchaseLinkService::mapCandidateRow,
            args.toArray());
        boolean more=fetched.size()>limit;
        List<CandidateRow> page=fetched.subList(0,Math.min(fetched.size(),limit));
        String next=null;
        if(more){
            CandidateAfter last=page.getLast().position();
            next=LocalKeysetPage.encode(tenant.toString(),marketplace.toString(),
                shipment.toString(),inboundLine.toString(),
                purchaseLine==null?"ALL":purchaseLine.toString(),
                last.updatedAt().toInstant().toString(),last.po().toString(),
                last.line().toString(),last.asn().toString());
        }
        return new CandidatePage(
            page.stream().map(CandidateRow::candidate).toList(),next,more,limit);
    }

    private void audit(UUID tenant,UUID actor,UUID id,String action,String note){
        jdbc.update("""
            INSERT INTO fba_inbound_purchase_link_audit
              (id,tenant_id,link_id,actor_user_id,action,note)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,note);
    }
    @Transactional
    public Linked propose(UUID tenant,UUID marketplace,UUID shipment,UUID actor,Propose input){
        if(input==null||input.requestKey()==null||input.inboundLineId()==null
            ||input.purchaseOrderLineId()==null||actor==null)
            throw bad("Request key, inbound line and approved purchase line required");
        String note=proof(input.proofReference());
        Line target=line(tenant,marketplace,shipment,input.inboundLineId(),true);
        if(!"DRAFT".equals(target.state()))
            throw conflict("Identity links must be proposed before local shipment dispatch");
        // Request key replay must agree on every identity dimension.
        var past=jdbc.query("""
            SELECT id,inbound_line_id,purchase_order_line_id,asn_id,proof_reference,
              proposed_by,shipment_id FROM fba_inbound_purchase_link
            WHERE tenant_id=? AND request_key=? FOR UPDATE
            """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5),
                r.getObject(6,UUID.class),r.getObject(7,UUID.class)},tenant,input.requestKey());
        if(!past.isEmpty()){
            Object[] old=past.getFirst();
            if(!old[1].equals(input.inboundLineId())
                ||!old[2].equals(input.purchaseOrderLineId())
                ||!java.util.Objects.equals(old[3],input.asnId())
                ||!old[4].equals(note)||!old[5].equals(actor)||!old[6].equals(shipment))
                throw conflict("Identity proof request key already used");
            return get(tenant,marketplace,(UUID)old[0]);
        }
        Purchase po=eligible(tenant,marketplace,target,input.purchaseOrderLineId());
        var locks=jdbc.queryForList("""
            SELECT id FROM erp_purchase_order WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,po.po());
        if(locks.size()!=1)throw missing();
        if(input.asnId()!=null){
            var asn=jdbc.query("""
                SELECT quantity,status FROM erp_purchase_asn
                WHERE tenant_id=? AND id=? AND purchase_order_id=?
                  AND purchase_order_line_id=?
                """,(r,i)->new Object[]{r.getInt(1),r.getString(2)},
                tenant,input.asnId(),po.po(),po.line());
            if(asn.size()!=1 || !"SHIPPED".equals(asn.getFirst()[1]))
                throw conflict("Explicit ASN must be SHIPPED and belong to the same approved PO line");
            long linked=jdbc.queryForObject("""
                SELECT COALESCE(SUM(il.planned_quantity),0)::bigint
                FROM fba_inbound_purchase_link proof
                JOIN fba_inbound_line il ON il.tenant_id=proof.tenant_id
                  AND il.id=proof.inbound_line_id
                WHERE proof.tenant_id=? AND proof.asn_id=? AND proof.state<>'REVOKED'
                """,Long.class,tenant,input.asnId());
            if(linked+target.planned()>(Integer)asn.getFirst()[0])
                throw conflict("Planned inbound link exceeds explicit ASN quantity");
        }
        long poLinked=jdbc.queryForObject("""
            SELECT COALESCE(SUM(il.planned_quantity),0)::bigint
            FROM fba_inbound_purchase_link proof
            JOIN fba_inbound_line il ON il.tenant_id=proof.tenant_id
              AND il.id=proof.inbound_line_id
            WHERE proof.tenant_id=? AND proof.purchase_order_line_id=?
              AND proof.state<>'REVOKED'
            """,Long.class,tenant,po.line());
        if(poLinked+target.planned()>po.ordered())
            throw conflict("Planned inbound links exceed the approved PO line quantity");
        UUID id=UUID.randomUUID();
        try{
            jdbc.update("""
                INSERT INTO fba_inbound_purchase_link
                (id,tenant_id,shipment_id,inbound_line_id,purchase_order_id,
                  purchase_order_line_id,asn_id,request_key,proof_reference,proposed_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,shipment,target.line(),po.po(),po.line(),
                input.asnId(),input.requestKey(),note,actor);
        }catch(DuplicateKeyException e){
            throw conflict("Inbound line is already linked; review the existing proof");
        }
        audit(tenant,actor,id,"PROPOSED",note);
        return get(tenant,marketplace,id);
    }
    private Linked get(UUID tenant,UUID marketplace,UUID id){
        var rows=jdbc.query(LINK_QUERY+" AND link.id=?",
            FbaPurchaseLinkService::map,tenant,marketplace,id);
        if(rows.size()!=1)throw missing();
        return rows.getFirst();
    }
    /** Route-scoped transition: a link from another shipment is never actionable. */
    @Transactional
    public Linked changeForShipment(UUID tenant,UUID marketplace,UUID shipment,UUID actor,
                                  UUID linkId,String action,String reason){
        Linked existing=get(tenant,marketplace,linkId);
        if(!existing.shipmentId().equals(shipment.toString()))throw missing();
        return change(tenant,marketplace,actor,linkId,action,reason);
    }
    @Transactional
    public Linked change(UUID tenant,UUID marketplace,UUID actor,UUID id,
                         String action,String reason){
        if(!List.of("CONFIRMED","REVOKED").contains(action))
            throw bad("Invalid local link transition");
        String note=proof(reason);
        var rows=jdbc.query("""
            SELECT link.shipment_id,link.inbound_line_id,link.proposed_by,
              link.confirmed_by,link.state
            FROM fba_inbound_purchase_link link
            JOIN fba_inbound_shipment sh ON sh.tenant_id=link.tenant_id
              AND sh.id=link.shipment_id
            JOIN tenant t ON t.id=sh.tenant_id AND t.status='ACTIVE'
            WHERE link.tenant_id=? AND sh.seller_marketplace_id=? AND link.id=?
            """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getObject(4,UUID.class),
                r.getString(5)},tenant,marketplace,id);
        if(rows.size()!=1)throw missing();
        Object[] current=rows.getFirst();
        Line inbound=line(tenant,marketplace,(UUID)current[0],(UUID)current[1],true);
        if(!"DRAFT".equals(inbound.state()))
            throw conflict("Shipment already dispatched; local proof is immutable");
        // Re-read the proof AFTER acquiring the parent shipment lock.
        // Another Owner may have confirmed/revoked while this call waited.
        var locked=jdbc.query("""
            SELECT state FROM fba_inbound_purchase_link
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(r,i)->r.getString(1),tenant,id);
        if(locked.size()!=1)throw missing();
        String state=locked.getFirst();
        if(action.equals(state))return get(tenant,marketplace,id);
        if(!"PROPOSED".equals(state))
            throw conflict("Only a pending local identity proposal may transition");
        if("CONFIRMED".equals(action)){
            if(actor.equals(current[2]))
                throw conflict("Two different Owner identities must propose and confirm a link");
            jdbc.update("""
                UPDATE fba_inbound_purchase_link
                SET state='CONFIRMED',confirmed_by=?,confirmed_at=now()
                WHERE tenant_id=? AND id=? AND state='PROPOSED'
                """,actor,tenant,id);
        }else{
            jdbc.update("""
                UPDATE fba_inbound_purchase_link
                SET state='REVOKED',revoked_by=?,revoked_at=now()
                WHERE tenant_id=? AND id=? AND state='PROPOSED'
                """,actor,tenant,id);
        }
        audit(tenant,actor,id,action,note);
        return get(tenant,marketplace,id);
    }
}
