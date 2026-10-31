package org.merchivra.erp.spapi;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All evidence and accounts are synthetic. Never calls Amazon. */
class OrdersRoleReviewServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private final OrdersRoleReviewService service=new OrdersRoleReviewService(jdbc,tx);
    private final UUID tenant=UUID.randomUUID(), account=UUID.randomUUID();
    private final UUID requester=UUID.randomUUID(), reviewer=UUID.randomUUID(), reviewId=UUID.randomUUID();
    private final Instant verified=Instant.parse("2026-10-30T12:00:00Z");
    private final String digest="a".repeat(64);

    @Test void malformedEvidenceRejectedBeforeTouchingDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.request(tenant,requester,account,"not-a-digest","AWSAPP-123456"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.request(tenant,requester,account,digest,"secret with spaces"))
            .getStatusCode());
        verifyNoInteractions(jdbc,tx);
    }

    @Test void noCurrentSellerGrantMeansNoReviewCreation() {
        transactions();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.request(tenant,requester,account,digest,"CASE-123456"))
            .getStatusCode());
        verify(jdbc,never()).update(contains("INSERT INTO spapi_orders_role_review_request"),
            any(Object[].class));
    }

    @Test void reviewerCannotApproveOwnRequest() throws Exception {
        transactions();
        requestRow(requester);
        when(jdbc.queryForObject(contains("rb.role_code='SECURITY_REVIEWER'"),
            eq(Integer.class),eq(tenant),eq(requester),eq(account))).thenReturn(1);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,requester,reviewId,true)).getStatusCode());
        verify(jdbc,never()).update(contains("INSERT INTO spapi_orders_role_evidence"),
            any(Object[].class));
    }

    @Test void independentReviewerIsRequiredAndNeverAutoGrantsRole() throws Exception {
        transactions();
        requestRow(requester);
        when(jdbc.queryForObject(contains("rb.role_code='SECURITY_REVIEWER'"),
            eq(Integer.class),eq(tenant),eq(reviewer),eq(account))).thenReturn(0);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,reviewer,reviewId,true)).getStatusCode());
        verify(jdbc,never()).update(contains("INSERT INTO spapi_orders_role_evidence"),
            any(Object[].class));
    }

    @Test void separatelyAuthorizedReviewerWritesScopedEvidenceAndAudit() throws Exception {
        transactions();
        requestRow(requester);
        when(jdbc.queryForObject(contains("rb.role_code='SECURITY_REVIEWER'"),
            eq(Integer.class),eq(tenant),eq(reviewer),eq(account))).thenReturn(1);
        when(jdbc.query(contains("SELECT g.verified_at"),any(RowMapper.class),
            eq(tenant),eq(account))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("verified_at",OffsetDateTime.class))
                    .thenReturn(OffsetDateTime.ofInstant(verified,ZoneOffset.UTC));
                return List.of(mapper.mapRow(rs,0));
            });
        assertEquals("APPROVED",service.decide(tenant,reviewer,reviewId,true));
        verify(jdbc).update(contains("INSERT INTO spapi_orders_role_evidence"),
            eq(tenant),eq(account),eq(digest),any(OffsetDateTime.class),eq(reviewer));
        verify(jdbc).update(contains("INSERT INTO spapi_orders_role_review_audit"),
            any(UUID.class),eq(tenant),eq(reviewId),eq(reviewer),eq("APPROVED"));
    }

    @Test void denialCannotCreateApprovalEvidence() throws Exception {
        transactions();
        requestRow(requester);
        when(jdbc.queryForObject(contains("rb.role_code='SECURITY_REVIEWER'"),
            eq(Integer.class),eq(tenant),eq(reviewer),eq(account))).thenReturn(1);
        when(jdbc.query(contains("SELECT g.verified_at"),any(RowMapper.class),
            eq(tenant),eq(account))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("verified_at",OffsetDateTime.class))
                    .thenReturn(OffsetDateTime.ofInstant(verified,ZoneOffset.UTC));
                return List.of(mapper.mapRow(rs,0));
            });
        assertEquals("REJECTED",service.decide(tenant,reviewer,reviewId,false));
        verify(jdbc,never()).update(contains("INSERT INTO spapi_orders_role_evidence"),
            any(Object[].class));
    }

    private void transactions() {
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback=inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }
    @SuppressWarnings("unchecked")
    private void requestRow(UUID actor) throws Exception {
        when(jdbc.query(contains("FROM spapi_orders_role_review_request"),
            any(RowMapper.class),eq(tenant),eq(reviewId))).thenAnswer(inv -> {
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("id",UUID.class)).thenReturn(reviewId);
                when(rs.getObject("seller_account_id",UUID.class)).thenReturn(account);
                when(rs.getObject("requested_by",UUID.class)).thenReturn(actor);
                when(rs.getString("state")).thenReturn("PENDING");
                when(rs.getString("external_case_ref")).thenReturn("CASE-123456");
                when(rs.getString("evidence_sha256")).thenReturn(digest);
                when(rs.getObject("grant_verified_at",OffsetDateTime.class))
                    .thenReturn(OffsetDateTime.ofInstant(verified,ZoneOffset.UTC));
                return List.of(mapper.mapRow(rs,0));
            });
    }
}
