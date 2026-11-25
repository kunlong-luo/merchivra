package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Actual installed PG18 tables, views, FKs and transactions; no external data. */
class ReceiptCostCompositionPg18V250Test {
    private final UUID tenant=UUID.randomUUID(), maker=UUID.randomUUID(),
        reviewer=UUID.randomUUID(), account=UUID.randomUUID(), market=UUID.randomUUID(),
        market2=UUID.randomUUID(), supplier=UUID.randomUUID(), sku=UUID.randomUUID(),
        warehouse=UUID.randomUUID(), bin=UUID.randomUUID(), po=UUID.randomUUID(),
        pol=UUID.randomUUID(), receipt=UUID.randomUUID(), qc=UUID.randomUUID(),
        position=UUID.randomUUID(), op=UUID.randomUUID(), pick=UUID.randomUUID(),
        pickLine=UUID.randomUUID(), sale=UUID.randomUUID(), itemA=UUID.randomUUID(),
        itemB=UUID.randomUUID(), claimA=UUID.randomUUID(), claimB=UUID.randomUUID();
    private JdbcTemplate db;
    private TransactionTemplate write;
    private ReceiptCostCompositionServiceV249 service;

    @Test
    @EnabledIfEnvironmentVariable(named="MERCHIVRA_V250_DISPOSABLE_ONLY",matches="true")
    void actualPg18ReceiptCostsAndSaleRangesConserveAmounts() throws Exception {
        Map.ofEntries(
            Map.entry("GITHUB_ACTIONS","true"),
            Map.entry("GITHUB_REPOSITORY","kunlong-luo/merchivra"),
            Map.entry("GITHUB_REF","refs/heads/main"),
            Map.entry("MERCHIVRA_SPAPI_ENABLED","false"),
            Map.entry("MERCHIVRA_SPAPI_OAUTH_ENABLED","false"),
            Map.entry("DB_HOST","127.0.0.1"),Map.entry("DB_PORT","5432"),
            Map.entry("DB_NAME","merchivra"),Map.entry("DB_USER","merchivra"),
            Map.entry("DB_PASSWORD","ci_disposable_database_password"),
            Map.entry("MERCHIVRA_BOOTSTRAP_USERNAME","v65-ci-owner"),
            Map.entry("MERCHIVRA_BOOTSTRAP_PASSWORD","ci-disposable-v65-only-secret-0000")
        ).forEach((k,v)->assertEquals(v,System.getenv(k),"Disposable CI required: "+k));
        assertTrue(System.getenv().getOrDefault("GITHUB_RUN_ID","").matches("[0-9]+"));
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:5432/merchivra",
            "merchivra","ci_disposable_database_password");
        db=new JdbcTemplate(ds);
        var manager=new DataSourceTransactionManager(ds);
        write=new TransactionTemplate(manager);
        int version=Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class));
        assertTrue(version>=180000&&version<190000);
        assertEquals(4,db.queryForObject("""
            SELECT count(*) FROM flyway_schema_history
            WHERE version IN ('106','112','117','118') AND success
            """,Integer.class));
        service=new ReceiptCostCompositionServiceV249(db,
            new ReceiptCostIntegrityServiceV216(db,new CostIdentityReconciliationService(db),manager),
            manager);
        write.executeWithoutResult(ignored->seed());
        var client=loginFixtureOwner();
        expense(market,"INBOUND_FREIGHT","20","10","a");
        bind(claimA,0,1);
        hold("ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE");
        bind(claimB,2,3);
        hold("FREIGHT_OR_DUTY_EVIDENCE_UNKNOWN_NOT_ZERO");
        assertHttpHold(client,"FREIGHT_OR_DUTY_EVIDENCE_UNKNOWN_NOT_ZERO");
        expense(market,"IMPORT_DUTY","10","5","b");
        var result=service.preview(tenant,market,receipt);
        assertEquals("LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED",result.state());
        assertEquals("USD",result.currency());
        assertEquals("45.000000",result.receiptCandidateCost());
        assertEquals("30.000000",result.allocatedCandidateCost());
        assertEquals("15.000000",result.unallocatedCandidateCost());
        assertEquals(List.of(claimA.toString(),claimB.toString()),
            result.saleShares().stream().map(ReceiptCostCompositionServiceV249.SaleShare::claimId).toList());
        assertEquals(List.of("14.999999","15.000001"),
            result.saleShares().stream().map(ReceiptCostCompositionServiceV249.SaleShare::candidateCost).toList());
        assertEquals(3,result.components().size());
        assertEquals("10.000000",result.components().stream()
            .filter(c->c.category().equals("INBOUND_FREIGHT")).findFirst().orElseThrow()
            .partition().sourceAmount());
        assertEquals("5.000000",result.components().stream()
            .filter(c->c.category().equals("IMPORT_DUTY")).findFirst().orElseThrow()
            .partition().sourceAmount());
        assertFalse(result.actualBatchCogsVerified());
        assertNull(result.actualBatchCogs());
        assertNull(result.realizedNetProfit());
        assertHttpCandidate(client);
        var basis=service.prepareRefundBasis(tenant,market,receipt,claimA);
        assertEquals(itemA,basis.identity().orderItemId());
        assertEquals(sale,basis.identity().salesOrderId());
        assertEquals("14.999999",basis.originalCandidateAmount());
        var proposed=List.of(new RefundOriginalCostSliceEngineV255.Slice(
            UUID.randomUUID(),itemA,0,1,1,"RESTOCKED_CLAIMED"));
        var replay=RefundOriginalCostSliceEngineV255.replay(basis.identity(),basis.sha256(),basis,proposed);
        assertEquals("14.999999",replay.refundCandidateAmount());
        assertNull(replay.actualRefundCogsReversal());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('119','120') AND success",Integer.class));
        var stored=service.persistRefundBasis(tenant,market,receipt,claimA,maker);
        assertEquals(basis,stored);
        assertEquals(basis.sha256(),db.queryForObject("""
            SELECT btrim(basis_sha256) FROM finance_original_cost_basis_v119 WHERE tenant_id=? AND claim_id=?
            """,String.class,tenant,claimA));
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->service.persistRefundBasis(tenant,market,receipt,claimA,maker));
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->db.update("DELETE FROM finance_original_cost_basis_v119 WHERE tenant_id=? AND claim_id=?",tenant,claimA));
        UUID event=seedRefund(itemA),rival=seedRefund(itemA);
        raceReservation(claimA,stored.sha256(),event,0,1,claimA,stored.sha256(),rival,0,1,false,null);
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->reserveRefund(claimA,stored.sha256(),rival,0,1));
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->reserveRefund(claimA,stored.sha256(),event,0,1));
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->reserveRefund(claimA,stored.sha256(),rival,1,2));
        assertThrows(org.springframework.dao.DataAccessException.class,
            ()->db.update("DELETE FROM finance_refund_cost_slice_v119 WHERE tenant_id=? AND claim_id=?",tenant,claimA));
        assertEquals(1,db.queryForObject("""
            SELECT count(*) FROM finance_refund_cost_slice_v119
            WHERE tenant_id=? AND claim_id=? AND order_item_id=? AND review_revision=1 AND review_quantity=1
            """,Integer.class,tenant,claimA,itemA));
        var current=service.readRefundReservations(tenant,market,receipt,claimA);
        assertEquals("LOCAL_RESERVATIONS_CURRENT_UNVERIFIED",current.state());
        assertEquals("14.999999",current.replay().refundCandidateAmount());
        assertNull(current.replay().actualRefundCogsReversal());
        db.update("UPDATE finance_refund_item_review SET revision=2,reviewed_at=clock_timestamp() WHERE tenant_id=? AND event_id=?",tenant,rival);
        assertReservationHold("REFUND_REVIEW_OR_LINK_STALE");
        // Restore only in synthetic test; equality alone is not ABA protection.
        db.update("""
            UPDATE finance_refund_item_review r SET revision=s.review_revision,reviewed_at=s.review_at
            FROM finance_refund_cost_slice_v119 s WHERE r.tenant_id=s.tenant_id
              AND r.event_id=s.refund_event_id AND s.tenant_id=? AND s.claim_id=?
            """,tenant,claimA);
        assertEquals(current,service.readRefundReservations(tenant,market,receipt,claimA));
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->service.prepareRefundBasis(tenant,market,receipt,UUID.randomUUID())).getStatusCode().value());
        assertEquals(401,http(HttpClient.newHttpClient(),candidatePath(),null,null).statusCode());
        // An independent current read cannot borrow foreign tenant receipts.
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->service.preview(UUID.randomUUID(),market,receipt)).getStatusCode().value());
        db.update("UPDATE erp_purchase_order_line SET unit_cost=11 WHERE tenant_id=? AND id=?",tenant,pol);
        hold("ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE");
        assertReservationHold("REFUND_REVIEW_OR_LINK_STALE");
        assertHttpHold(client,"ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE");
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->service.prepareRefundBasis(tenant,market,receipt,claimA)).getStatusCode().value());
        // A prior local observation can still be arithmetically replayed,
        // but must never become a fresh or verified refund cost.
        assertEquals(replay,RefundOriginalCostSliceEngineV255.replay(
            basis.identity(),basis.sha256(),basis,proposed));
        db.update("UPDATE erp_purchase_order_line SET unit_cost=10 WHERE tenant_id=? AND id=?",tenant,pol);
        assertEquals(result,service.preview(tenant,market,receipt));
        assertHttpCandidate(client);
        // A real local retraction leaves the persisted range reserved and
        // prevents a misleading total. The foreign-source hold has priority,
        // so it can be tested after the committed append-only retraction.
        db.update("""
            INSERT INTO finance_sale_lot_retraction_v106
            (id,tenant_id,claim_id,retracted_by,reason) VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,claimB,reviewer,"V250 isolated retraction only");
        hold("ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE");
        assertEquals(2,db.queryForObject("""
            SELECT count(*) FROM finance_sale_lot_cost_range_v117 WHERE tenant_id=? AND receipt_id=?
            """,Integer.class,tenant,receipt));
        expense(market2,"INBOUND_FREIGHT","7","2","c");
        hold("CROSS_MARKETPLACE_RECEIPT_SCOPE_UNRESOLVED");
        assertHttpHold(client,"CROSS_MARKETPLACE_RECEIPT_SCOPE_UNRESOLVED");
        var csrf=readJson(http(client,"/api/v1/auth/csrf",null,null));
        assertEquals(204,http(client,"/api/v1/auth/logout","",csrf.path("token").asText()).statusCode());
        assertEquals(401,http(client,candidatePath(),null,null).statusCode());
        var second=new ReceiptCostCompositionPg18V250Test();
        second.db=db;second.write=write;second.service=service;
        second.crossClaimRace();
        System.out.println("PASS V258 actual PG18 whole-order original cost and refund reservation coverage, 30/14.999999/15.000001, full refund and stale hold");
        System.out.println("PASS V257 actual PG18 refund races: rollback release, committed cross-claim quantity rejection, review and cost drift hold");
        System.out.println("PASS V256 actual PG18 immutable local cost basis and refund reservation duplicate, overlap and bounds rejected");
        System.out.println("PASS V255 actual PG18 scoped candidate capture and original refund slice replay, price drift hold");
        System.out.println("PASS V254 actual Owner HTTP nonempty receipt candidate 45/30/15, exact shares, drift/recovery, hold and logout");
        System.out.println("PASS V250 actual PG18 V249 composition: PO freight duty 45/30/15, original source shares, missing range/duty, drift, retraction and cross-marketplace hold");
    }

    // Reuse the actual running Spring server and a dedicated synthetic Owner.
    // The bootstrap hash is copied ONLY into the disposable CI database.
    private final JsonMapper json=JsonMapper.builder().build();
    private String loginName(){return "v254-"+maker;}
    private String candidatePath(){
        return "/api/v1/finance/marketplaces/"+market+"/purchase-receipts/"+receipt
            +"/cost-composition-candidate";
    }
    private HttpResponse<String> http(HttpClient client,String path,String body,String csrf)
            throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+path))
            .timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if(body==null)request.GET();
        else request.header("Content-Type","application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode readJson(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),"actual HTTP status");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return json.readTree(response.body());
    }
    private HttpClient loginFixtureOwner() throws Exception {
        var client=HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
        var csrf=readJson(http(client,"/api/v1/auth/csrf",null,null));
        assertEquals("X-CSRF-TOKEN",csrf.path("headerName").asText());
        String body="username="+URLEncoder.encode(loginName(),StandardCharsets.UTF_8)
            +"&password="+URLEncoder.encode(System.getenv("MERCHIVRA_BOOTSTRAP_PASSWORD"),StandardCharsets.UTF_8);
        assertEquals(200,http(client,"/api/v1/auth/login",body,csrf.path("token").asText()).statusCode());
        var viewer=readJson(http(client,"/api/v1/auth/me",null,null));
        assertTrue(viewer.path("isOwner").asBoolean());
        assertEquals(tenant.toString(),viewer.path("activeTenantId").asText());
        return client;
    }
    private JsonNode httpCandidate(HttpClient client) throws Exception {
        var d=readJson(http(client,candidatePath(),null,null));
        assertEquals(market.toString(),d.path("marketplaceId").asText());
        assertEquals(receipt.toString(),d.path("purchaseReceiptId").asText());
        for(String key:List.of("completeExternalCostCoverage","actualBatchCogsVerified","postingAllowed"))
            assertTrue(d.path(key).isBoolean()&&!d.path(key).asBoolean(),key);
        for(String key:List.of("actualBatchCogs","realizedNetProfit"))
            assertTrue(d.path(key).isNull(),key);
        return d;
    }
    private void assertHttpCandidate(HttpClient client) throws Exception {
        var d=httpCandidate(client);
        assertEquals("LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED",d.path("state").asText());
        assertEquals("USD",d.path("originalCurrency").asText());
        assertEquals("45.000000",d.path("receiptCandidateCost").asText());
        assertEquals("30.000000",d.path("allocatedCandidateCost").asText());
        assertEquals("15.000000",d.path("unallocatedCandidateCost").asText());
        assertEquals(3,d.path("components").size());
        var shares=d.path("saleShares");
        assertEquals(2,shares.size());
        assertEquals(claimA.toString(),shares.get(0).path("claimId").asText());
        assertEquals("14.999999",shares.get(0).path("candidateCost").asText());
        assertEquals(claimB.toString(),shares.get(1).path("claimId").asText());
        assertEquals("15.000001",shares.get(1).path("candidateCost").asText());
        for(var c:d.path("components")){
            assertEquals("USD",c.path("originalCurrency").asText());
            assertEquals(3,c.path("basisQuantity").asInt());
            assertEquals(2,c.path("allocatedQuantity").asInt());
            assertEquals(1,c.path("unallocatedQuantity").asInt());
            assertFalse(c.has("shares"),"HTTP must not repeat the expanded per-source ranges");
        }
    }
    private void assertHttpHold(HttpClient client,String reason) throws Exception {
        var d=httpCandidate(client);
        assertEquals("LOCAL_COST_COMPOSITION_HOLD",d.path("state").asText());
        assertTrue(d.path("blockerCodes").toString().contains(reason));
        for(String key:List.of("originalCurrency","receiptCandidateCost","allocatedCandidateCost","unallocatedCandidateCost"))
            assertTrue(d.path(key).isNull(),key);
        for(String key:List.of("components","saleShares"))
            assertTrue(d.path(key).isArray()&&d.path(key).isEmpty(),key);
    }

    private void assertReservationHold(String reason) {
        var held=service.readRefundReservations(tenant,market,receipt,claimA);
        assertEquals("LOCAL_RESERVATIONS_HOLD",held.state());
        assertNull(held.replay());
        assertTrue(held.blockers().contains(reason),held.blockers().toString());
        assertEquals(1,held.reservedRows());
    }
    private void crossClaimRace() throws Exception {
        write.executeWithoutResult(ignored->seed(true));
        expense(market,"INBOUND_FREIGHT","20","10","a");
        expense(market,"IMPORT_DUTY","10","5","b");
        bind(claimA,0,1);bind(claimB,2,3);
        var a=service.persistRefundBasis(tenant,market,receipt,claimA,maker);
        var b=service.persistRefundBasis(tenant,market,receipt,claimB,maker);
        UUID event=seedRefund(itemA);
        raceReservation(claimA,a.sha256(),event,0,1,claimB,b.sha256(),event,2,3,true,
            "V119: refund item quantity exceeded");
        assertEquals(1,db.queryForObject("""
            SELECT count(*) FROM finance_refund_cost_slice_v119 WHERE tenant_id=? AND refund_event_id=?
            """,Integer.class,tenant,event));
        assertEquals("LOCAL_RESERVATIONS_CURRENT_UNVERIFIED",
            service.readRefundReservations(tenant,market,receipt,claimA).state());
        var first=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertEquals("LOCAL_ORDER_REFUND_COST_CANDIDATE_UNVERIFIED",first.state());
        assertEquals(2,first.originalClaimRows());
        assertEquals(1,first.reviewedRefundUnits());
        assertEquals(List.of(new ReceiptCostCompositionServiceV249.OrderRefundCurrency(
            "USD","30.000000","14.999999","15.000001")),first.currencies());
        assertFalse(first.refundCogsReversalAllowed());
        assertNull(first.actualRefundCogsReversal());
        assertNull(first.realizedNetProfit());
        // V259: quantity and reservation coverage are not enough without V35.
        db.update("UPDATE finance_refund_amount_allocation SET signed_amount=-19 WHERE tenant_id=? AND event_id=?",tenant,event);
        var partial=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertTrue(partial.blockers().contains("REFUND_AMOUNT_NOT_BALANCED_IN_ORIGINAL_CURRENCY"));
        assertTrue(partial.currencies().isEmpty());
        assertNull(partial.actualRefundCogsReversal());
        db.update("UPDATE finance_refund_amount_allocation SET signed_amount=-20 WHERE tenant_id=? AND event_id=?",tenant,event);
        assertEquals(first.currencies(),service.readWholeOrderRefundReservations(tenant,market,sale).currencies());
        UUID next=seedRefund(itemA);
        var held=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertEquals("LOCAL_ORDER_REFUND_COST_HOLD",held.state());
        assertTrue(held.blockers().contains("REFUND_EVENT_ITEM_RESERVATION_COVERAGE_INCOMPLETE"));
        assertTrue(held.currencies().isEmpty());
        reserveRefund(claimB,b.sha256(),next,2,3);
        db.update("DELETE FROM finance_refund_amount_allocation WHERE tenant_id=? AND event_id=?",tenant,next);
        var missing=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertTrue(missing.blockers().contains("REFUND_AMOUNT_ITEM_COVERAGE_INCOMPLETE"));
        assertTrue(missing.currencies().isEmpty());
        db.update("""
            INSERT INTO finance_refund_amount_allocation
              (tenant_id,event_id,external_item_id,signed_amount,currency_code,
               reason_reference,revision,reviewed_by)
            SELECT ?,?,external_item_id,-20,'USD','V259-SYNTHETIC',1,?
            FROM order_item WHERE tenant_id=? AND id=?
            """,tenant,next,reviewer,tenant,itemA);
        var full=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertEquals(List.of(new ReceiptCostCompositionServiceV249.OrderRefundCurrency(
            "USD","30.000000","30.000000","0.000000")),full.currencies());
        assertEquals(2,full.reservedRefundUnits());
        db.update("UPDATE finance_refund_item_review SET revision=2,reviewed_at=clock_timestamp() WHERE tenant_id=? AND event_id=?",tenant,next);
        var stale=service.readWholeOrderRefundReservations(tenant,market,sale);
        assertEquals("LOCAL_ORDER_REFUND_COST_HOLD",stale.state());
        assertTrue(stale.currencies().isEmpty());
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->service.readWholeOrderRefundReservations(UUID.randomUUID(),market,sale)).getStatusCode().value());
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/merchivra",
            "merchivra","ci_disposable_database_password");
    }
    private void insertReservation(Connection c,UUID claim,String digest,UUID event,int from,int to)
            throws SQLException {
        try(var p=c.prepareStatement("""
            INSERT INTO finance_refund_cost_slice_v119
              (tenant_id,claim_id,basis_sha256,refund_event_id,unit_from,unit_to,recorded_by)
            VALUES (?,?,?,?,?,?,?)
            """)){
            p.setObject(1,tenant);p.setObject(2,claim);p.setString(3,digest);p.setObject(4,event);
            p.setInt(5,from);p.setInt(6,to);p.setObject(7,maker);p.executeUpdate();
        }
    }
    private void raceReservation(UUID a,String da,UUID ea,int fa,int ta,
        UUID b,String dbasis,UUID eb,int fb,int tb,boolean commit,String expected) throws Exception {
        try(var first=connection();var rival=connection();var pool=Executors.newSingleThreadExecutor()){
            first.setAutoCommit(false);
            first.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            rival.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try(var p=rival.createStatement()){p.execute("SET lock_timeout='15s'");}
            int pid;
            try(var p=rival.createStatement();var rs=p.executeQuery("SELECT pg_backend_pid()")){
                rs.next();pid=rs.getInt(1);
            }
            insertReservation(first,a,da,ea,fa,ta);
            var future=pool.submit(()->{
                try{insertReservation(rival,b,dbasis,eb,fb,tb);return "SUCCESS";}
                catch(SQLException e){return e.getMessage();}
            });
            boolean waiting=false;
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(System.nanoTime()<deadline){
                    if(Boolean.TRUE.equals(db.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid))){
                        waiting=true;break;
                    }
                    Thread.sleep(40);
                }
                assertTrue(waiting,"V257 rival must wait on a real PostgreSQL lock");
            } finally {
                if(commit&&waiting)first.commit();else first.rollback();
            }
            String outcome=future.get(20,TimeUnit.SECONDS);
            if(commit)assertTrue(outcome.contains(expected),outcome);
            else assertEquals("SUCCESS",outcome);
        }
    }

    private UUID seedRefund(UUID item) {
        UUID event=UUID.randomUUID();
        write.executeWithoutResult(ignored->{
            db.update("""
                INSERT INTO finance_source_event
                  (id,tenant_id,seller_marketplace_id,source_family,source_event_key,data_origin,
                   category,amount,currency_code,posted_at,external_order_id,source_type)
                VALUES (?,?,?,'SPAPI_FINANCES',?,'LIVE','PRODUCT_REFUND',-20,'USD',now(),
                   'V250-SYNTHETIC-ORDER','V256_SYNTHETIC_ONLY')
                """,event,tenant,market,"V256-"+event);
            db.update("""
                INSERT INTO finance_event_link_review(tenant_id,event_id,order_id,state,reviewed_by)
                VALUES (?,?,?,'MATCHED',?)
                """,tenant,event,sale,reviewer);
            db.update("""
                INSERT INTO finance_refund_item_review
                  (tenant_id,event_id,sales_order_id,external_item_id,reviewed_quantity,
                   disposition,evidence_reference,reviewed_by)
                SELECT ?,?,?,external_item_id,1,'RESTOCKED_CLAIMED','V256-SYNTHETIC',?
                FROM order_item WHERE tenant_id=? AND id=?
                """,tenant,event,sale,reviewer,tenant,item);
            db.update("""
                INSERT INTO finance_refund_amount_allocation
                  (tenant_id,event_id,external_item_id,signed_amount,currency_code,
                   reason_reference,revision,reviewed_by)
                SELECT ?,?,external_item_id,-20,'USD','V259-SYNTHETIC',1,?
                FROM order_item WHERE tenant_id=? AND id=?
                """,tenant,event,reviewer,tenant,item);
        });
        return event;
    }
    private void reserveRefund(UUID claim,String digest,UUID event,int from,int to) {
        db.update("""
            INSERT INTO finance_refund_cost_slice_v119
              (tenant_id,claim_id,basis_sha256,refund_event_id,unit_from,unit_to,recorded_by)
            VALUES (?,?,?,?,?,?,?)
            """,tenant,claim,digest,event,from,to,maker);
    }

    private void hold(String reason) {
        var c=service.preview(tenant,market,receipt);
        assertEquals("LOCAL_COST_COMPOSITION_HOLD",c.state());
        assertTrue(c.blockerCodes().contains(reason),c.blockerCodes().toString());
        assertNull(c.receiptCandidateCost());
        assertNull(c.allocatedCandidateCost());
        assertNull(c.unallocatedCandidateCost());
        assertTrue(c.components().isEmpty());
        assertTrue(c.saleShares().isEmpty());
        assertNull(c.actualBatchCogs());
        assertNull(c.realizedNetProfit());
    }

    private void bind(UUID claim,int start,int end) {
        db.update("""
            INSERT INTO finance_sale_lot_cost_range_v117
            (tenant_id,claim_id,unit_from,unit_to,recorded_by) VALUES (?,?,?,?,?)
            """,tenant,claim,start,end,maker);
    }

    private void expense(UUID marketplace,String category,String original,String allocated,String digest) {
        write.executeWithoutResult(ignored->{
            UUID source=UUID.randomUUID(),plan=UUID.randomUUID();
            db.update("""
                INSERT INTO finance_cost_source_v91
                  (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
                   source_document_sha256,business_date,currency_code,amount,created_by)
                VALUES (?,?,?,?,'MANUAL_INVOICE',?,?,DATE '2026-11-24','USD',?,?)
                """,source,tenant,marketplace,category,"V250-SYNTHETIC-"+source,
                digest.repeat(64),new BigDecimal(original),maker);
            db.update("""
                UPDATE finance_cost_source_v91 SET review_state='REVIEWED_UNVERIFIED',
                  reviewed_by=?,reviewed_at=now(),review_reason='V250 synthetic review only'
                WHERE tenant_id=? AND id=?
                """,reviewer,tenant,source);
            for(String action:List.of("STAGED","REVIEWED_UNVERIFIED"))
                db.update("""
                    INSERT INTO finance_cost_source_audit_v91
                      (id,tenant_id,source_id,action,actor_id) VALUES (?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,source,action,
                    action.equals("STAGED")?maker:reviewer);
            db.update("""
                INSERT INTO finance_receipt_cost_plan_v112
                  (id,tenant_id,source_id,proposed_by) VALUES (?,?,?,?)
                """,plan,tenant,source,maker);
            db.update("""
                INSERT INTO finance_receipt_cost_line_v112
                  (tenant_id,plan_id,receipt_id,amount) VALUES (?,?,?,?)
                """,tenant,plan,receipt,new BigDecimal(allocated));
            db.update("""
                UPDATE finance_receipt_cost_plan_v112 SET state='APPROVED_UNVERIFIED',
                  reviewed_by=?,reviewed_at=now(),review_reason='V250 synthetic independent allocation'
                WHERE tenant_id=? AND id=?
                """,reviewer,tenant,plan);
        });
    }

    private void seed(){seed(false);}
    private void seed(boolean sameItem) {
        UUID position2=UUID.randomUUID(),pickLine2=UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?,?)",tenant,"V250 DISPOSABLE");
        for(UUID user:List.of(maker,reviewer)) {
            db.update("INSERT INTO app_user(id,identity_ref) VALUES (?,?)",user,"local:v250:"+user);
            db.update("INSERT INTO membership(tenant_id,user_id) VALUES (?,?)",tenant,user);
        }
        assertEquals(1,db.update("""
            INSERT INTO local_credential(user_id,login_name,password_hash)
            SELECT ?,?,password_hash FROM local_credential WHERE login_name='v65-ci-owner'
            """,maker,loginName()));
        db.update("INSERT INTO role_binding(id,tenant_id,user_id,role_code) VALUES (?,?,?,'OWNER')",
            UUID.randomUUID(),tenant,maker);
        db.update("INSERT INTO seller_account(id,tenant_id,seller_id,region) VALUES (?,?,?,'NA')",
            account,tenant,"V250-SYNTHETIC-"+account);
        for(UUID m:List.of(market,market2))
            db.update("""
                INSERT INTO seller_marketplace
                  (id,tenant_id,seller_account_id,marketplace_id,currency_code,timezone)
                VALUES (?,?,?,?,'USD','Etc/UTC')
                """,m,tenant,account,"V250-"+m);
        db.update("""
            INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name)
            VALUES (?,?,'V250-SUP','Synthetic supplier')
            """,supplier,tenant);
        db.update("""
            INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
            VALUES (?,?,'V250-SKU','Synthetic SKU')
            """,sku,tenant);
        db.update("""
            INSERT INTO erp_local_warehouse(id,tenant_id,warehouse_code,display_name,created_by)
            VALUES (?,?,'V250-WH','Synthetic warehouse',?)
            """,warehouse,tenant,maker);
        db.update("""
            INSERT INTO erp_local_stock_bin(id,tenant_id,warehouse_id,bin_code,purpose,created_by)
            VALUES (?,?,?,'V250-BIN','STORAGE',?)
            """,bin,tenant,warehouse,maker);
        db.update("""
            INSERT INTO erp_purchase_order(id,tenant_id,supplier_id,order_number,currency_code,status,created_by)
            VALUES (?,?,?,'V250-PO','USD','RECEIVING',?)
            """,po,tenant,supplier,maker);
        db.update("""
            INSERT INTO erp_purchase_order_line(id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
            VALUES (?,?,?,?,3,10)
            """,pol,tenant,po,sku);
        db.update("""
            INSERT INTO erp_purchase_receipt
              (id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
            VALUES (?,?,?,?,3,?,?)
            """,receipt,tenant,po,pol,maker,warehouse);
        db.update("""
            INSERT INTO erp_purchase_receipt_qc
              (id,tenant_id,request_key,purchase_receipt_id,warehouse_id,product_sku_id,
               inspected_quantity,accepted_quantity,rejected_quantity,reason,inspected_by)
            VALUES (?,?,?,?,?,?,3,3,0,'V250 synthetic acceptance',?)
            """,qc,tenant,UUID.randomUUID(),receipt,warehouse,sku,reviewer);
        db.update("""
            INSERT INTO erp_receipt_quality_batch
              (tenant_id,receipt_id,warehouse_id,product_sku_id,received_quantity,lot_code,
               status,inspection_id,released_by,released_at,received_by)
            VALUES (?,?,?,?,3,'V250-LOT','RELEASED',?,?,now(),?)
            """,tenant,receipt,warehouse,sku,qc,reviewer,maker);
        db.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,inspection_id,movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,'QC_RELEASE',3,?)
            """,UUID.randomUUID(),tenant,warehouse,sku,qc,reviewer);
        db.update("""
            INSERT INTO erp_local_lot_position
              (id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,bin_id,
               placed_quantity,remaining_quantity,reason,created_by)
            VALUES (?,?,?,?,?,?,?,?,1,'V250 synthetic placement',?)
            """,position,tenant,UUID.randomUUID(),receipt,warehouse,sku,bin,sameItem?2:3,maker);
        if(sameItem)db.update("""
            INSERT INTO erp_local_lot_position(id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,bin_id,placed_quantity,remaining_quantity,reason,created_by)
            VALUES (?,?,?,?,?,?,?,1,0,'V257 split placement',?)
            """,position2,tenant,UUID.randomUUID(),receipt,warehouse,sku,bin,maker);
        db.update("""
            INSERT INTO erp_local_stock_operation
              (id,tenant_id,request_key,operation_type,source_warehouse_id,product_sku_id,
               requested_quantity,expected_on_hand,applied_delta,after_quantity,reason,created_by)
            VALUES (?,?,?,'OUTBOUND',?,?,2,3,-2,1,'V250 synthetic outbound',?)
            """,op,tenant,UUID.randomUUID(),warehouse,sku,maker);
        db.update("""
            INSERT INTO erp_local_lot_pick
              (id,tenant_id,request_key,warehouse_id,product_sku_id,operation_id,policy,
               quantity,expected_on_hand,reason,actor_user_id)
            VALUES (?,?,?,?,?,?,'FIFO',2,3,'V250 synthetic pick',?)
            """,pick,tenant,UUID.randomUUID(),warehouse,sku,op,maker);
        db.update("""
            INSERT INTO erp_local_lot_pick_line(id,tenant_id,pick_id,position_id,quantity)
            VALUES (?,?,?,?,?)
            """,pickLine,tenant,pick,position,sameItem?1:2);
        if(sameItem)db.update("INSERT INTO erp_local_lot_pick_line(id,tenant_id,pick_id,position_id,quantity) VALUES (?,?,?,?,1)",pickLine2,tenant,pick,position2);
        db.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,operation_id,movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,'OUTBOUND',-2,?)
            """,UUID.randomUUID(),tenant,warehouse,sku,op,maker);
        // LIVE is a schema fixture label, NOT a real Amazon order.
        db.update("""
            INSERT INTO sales_order(id,tenant_id,seller_marketplace_id,external_order_id,
              fulfillment_status_raw,data_origin,order_time,source_updated_at,items_observation)
            VALUES (?,?,?,'V250-SYNTHETIC-ORDER','SHIPPED','LIVE',now(),now(),'OBSERVED')
            """,sale,tenant,market);
        for(UUID item:sameItem?List.of(itemA):List.of(itemA,itemB))
            db.update("""
                INSERT INTO order_item(id,tenant_id,sales_order_id,external_item_id,seller_sku_raw,
                  quantity,data_origin,source_updated_at,is_current)
                VALUES (?,?,?,?, 'V250-SKU',?,'LIVE',now(),true)
                """,item,tenant,sale,"V250-ITEM-"+item,sameItem?2:1);
        for(UUID claim:List.of(claimA,claimB))
            db.update("""
                INSERT INTO finance_sale_lot_claim_v106
                  (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
                   receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,recorded_by)
                VALUES (?,?,?,?,?,?,?,1,?,?,?)
                """,claim,tenant,market,sale,sameItem||claim.equals(claimA)?itemA:itemB,
                sameItem&&claim.equals(claimB)?pickLine2:pickLine,
                receipt,"V250-DISPATCH-"+claim,"d".repeat(64),maker);
        assertEquals(2,db.queryForObject("""
            SELECT count(*) FROM finance_sale_lot_integrity_v106
            WHERE tenant_id=? AND receipt_id=? AND integrity_state='LOCAL_LINK_INTACT_UNVERIFIED'
            """,Integer.class,tenant,receipt));
    }
}
