package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** V138: governance state changes never remove original file bytes. */
class LotProofGovernanceServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotProofGovernanceService service=new LotProofGovernanceService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID reviewer=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID uploader=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID proof=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID file=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String NOTE="Verified legal preservation request with supporting case file";
    private static final OffsetDateTime FUTURE=OffsetDateTime.parse("2050-01-01T00:00:00Z");
    private static final OffsetDateTime PAST=OffsetDateTime.parse("2020-01-01T00:00:00Z");

    private static void bad(Runnable call) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }
    private static void conflict(Runnable call) {
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }
    private LotProofGovernanceService.Command cmd(String action){
        return new LotProofGovernanceService.Command(action,NOTE,null);
    }
    @SuppressWarnings("unchecked")
    private void file() throws Exception{
        when(jdbc.query(contains("SELECT id,uploaded_by FROM erp_local_lot_disposition_proof_file"),
            any(RowMapper.class),eq(tenant),eq(proof))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(file);
            when(rs.getObject("uploaded_by",UUID.class)).thenReturn(uploader);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    @SuppressWarnings("unchecked")
    private void state(boolean held,UUID holdBy,UUID releaseBy,
        OffsetDateTime retained,OffsetDateTime proposed,UUID proposedBy,
        String disposition,UUID requestedBy,UUID reviewedBy)throws Exception {
        when(jdbc.query(contains("FROM erp_lot_proof_file_governance"),
            any(RowMapper.class),eq(tenant),eq(file))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getBoolean("legal_hold")).thenReturn(held);
            when(rs.getObject("hold_set_by",UUID.class)).thenReturn(holdBy);
            when(rs.getObject("hold_release_requested_by",UUID.class)).thenReturn(releaseBy);
            when(rs.getObject("retention_until",OffsetDateTime.class)).thenReturn(retained);
            when(rs.getObject("retention_proposed_until",OffsetDateTime.class)).thenReturn(proposed);
            when(rs.getObject("retention_proposed_by",UUID.class)).thenReturn(proposedBy);
            when(rs.getString("disposal_state")).thenReturn(disposition);
            when(rs.getObject("disposal_requested_by",UUID.class)).thenReturn(requestedBy);
            when(rs.getObject("disposal_reviewed_by",UUID.class)).thenReturn(reviewedBy);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    private void ready() throws Exception{
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        file();
        when(jdbc.update(contains("UPDATE erp_lot_proof_file_governance"),
            any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO erp_lot_proof_file_governance_audit"),
            any(Object[].class))).thenReturn(1);
    }
    private void noFileOrStockWrites(){
        verify(jdbc,never()).update(contains("DELETE "),any(Object[].class));
        verify(jdbc,never()).update(contains("erp_local_lot_disposition_proof_file"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }

    @Test void validatesInputsBeforeAnyQuery() {
        bad(()->service.command(tenant,actor,proof,cmd("DROP_ORIGINAL")));
        bad(()->service.command(tenant,actor,proof,
            new LotProofGovernanceService.Command("PLACE_HOLD","short",null)));
        bad(()->service.command(tenant,actor,proof,
            new LotProofGovernanceService.Command("PROPOSE_RETENTION",NOTE,"invalid")));
        bad(()->service.command(tenant,actor,proof,
            new LotProofGovernanceService.Command("REQUEST_DISPOSAL",NOTE,FUTURE.toString())));
        bad(()->service.command(tenant,null,proof,cmd("PLACE_HOLD")));
        verifyNoInteractions(jdbc);
    }
    @Test void legacyFileHasNoRetentionOrDisposalEligibility() throws Exception {
        file();
        var result=service.status(tenant,proof);
        assertFalse(result.legalHold());
        assertNull(result.retentionUntil());
        assertEquals("NONE",result.disposalState());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void placeHoldIsAuditedBeforeReturningAndRevokesDisposalReview() throws Exception{
        ready();state(false,null,null,PAST,null,null,
            "REVIEWED_NO_DELETE",actor,reviewer);
        var result=service.command(tenant,reviewer,proof,cmd("PLACE_HOLD"));
        assertTrue(result.legalHold());
        assertEquals("NONE",result.disposalState());
        var order=inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant));
        order.verify(jdbc).update(contains("INSERT INTO erp_lot_proof_file_governance("),
            eq(tenant),eq(proof),eq(file));
        order.verify(jdbc).update(contains("UPDATE erp_lot_proof_file_governance"),
            any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO erp_lot_proof_file_governance_audit"),
            any(Object[].class));
        noFileOrStockWrites();
    }
    @Test void samePersonCannotApproveHoldRelease() throws Exception{
        ready();state(true,actor,actor,null,null,null,"NONE",null,null);
        conflict(()->service.command(tenant,actor,proof,cmd("APPROVE_HOLD_RELEASE")));
        verify(jdbc,never()).update(contains("UPDATE erp_lot_proof_file_governance"),
            any(Object[].class));
        noFileOrStockWrites();
    }
    @Test void independentHoldReleaseDoesNotApproveAnyDisposal() throws Exception{
        ready();state(true,actor,actor,PAST,null,null,"NONE",null,null);
        var result=service.command(tenant,reviewer,proof,cmd("APPROVE_HOLD_RELEASE"));
        assertFalse(result.legalHold());
        assertEquals("NONE",result.disposalState());
        noFileOrStockWrites();
    }
    @Test void retentionNeedsIndependentNonUploaderOwner() throws Exception{
        ready();state(false,null,null,null,FUTURE,actor,"NONE",null,null);
        conflict(()->service.command(tenant,actor,proof,cmd("APPROVE_RETENTION")));
        conflict(()->service.command(tenant,uploader,proof,cmd("APPROVE_RETENTION")));
        var approved=service.command(tenant,reviewer,proof,cmd("APPROVE_RETENTION"));
        assertEquals("2050-01-01T00:00:00Z",approved.retentionUntil());
        assertNull(approved.retentionProposedUntil());
        assertEquals("NONE",approved.disposalState());
        noFileOrStockWrites();
    }
    @Test void unapprovedOrHeldOrUnexpiredSourceCannotRequestDisposal() throws Exception{
        ready();state(false,null,null,null,null,null,"NONE",null,null);
        conflict(()->service.command(tenant,actor,proof,cmd("REQUEST_DISPOSAL")));
        state(true,actor,null,PAST,null,null,"NONE",null,null);
        conflict(()->service.command(tenant,actor,proof,cmd("REQUEST_DISPOSAL")));
        state(false,actor,null,FUTURE,null,null,"NONE",null,null);
        when(jdbc.queryForObject(contains("retention_until<=now()"),eq(Boolean.class),
            eq(tenant),eq(file))).thenReturn(false);
        conflict(()->service.command(tenant,actor,proof,cmd("REQUEST_DISPOSAL")));
        noFileOrStockWrites();
    }
    @Test void independentDisposalReviewIsNoDeleteStateOnly() throws Exception{
        ready();state(false,null,null,PAST,null,null,"REQUESTED",actor,null);
        when(jdbc.queryForObject(contains("retention_until<=now()"),eq(Boolean.class),
            eq(tenant),eq(file))).thenReturn(true);
        conflict(()->service.command(tenant,actor,proof,cmd("REVIEW_DISPOSAL")));
        conflict(()->service.command(tenant,uploader,proof,cmd("REVIEW_DISPOSAL")));
        var reviewed=service.command(tenant,reviewer,proof,cmd("REVIEW_DISPOSAL"));
        assertEquals("REVIEWED_NO_DELETE",reviewed.disposalState());
        assertEquals(reviewer.toString(),reviewed.disposalReviewedBy());
        noFileOrStockWrites();
    }
    @Test void auditWriteFailureBlocksGovernanceSuccess() throws Exception{
        ready();state(false,null,null,null,null,null,"NONE",null,null);
        when(jdbc.update(contains("INSERT INTO erp_lot_proof_file_governance_audit"),
            any(Object[].class))).thenReturn(0);
        conflict(()->service.command(tenant,actor,proof,cmd("PLACE_HOLD")));
    }
}
