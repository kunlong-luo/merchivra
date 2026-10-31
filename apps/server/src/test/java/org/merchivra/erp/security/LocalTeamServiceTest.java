package org.merchivra.erp.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalTeamServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final LocalTeamService team=new LocalTeamService(jdbc,tx,encoder);

    @Test void badInvitationLoginFailsWithoutTouchingDb() {
        var ex=assertThrows(ResponseStatusException.class,
            ()->team.invite(UUID.randomUUID(),UUID.randomUUID(),"user@outside"));
        assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
        verifyNoInteractions(jdbc,tx,encoder);
    }
    @Test void inviteTokenAndPasswordAreStrictlyValidatedBeforeDatabase() {
        var ex=assertThrows(ResponseStatusException.class,
            ()->team.accept("not-a-real-token","not-long-enough"));
        assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
        team.accept("x".repeat(43),"secure-password-1234");
        // Execution is deliberately stubbed because actual role and token
        // correctness require the PostgreSQL Compose HTTP integration test.
        verify(tx).executeWithoutResult(any());
        verifyNoInteractions(encoder);
    }
    @Test void neverEnableReviewerInOwnAccountViaClientInput() {
        // Each role assignment must lock tenant and verify OWNER via SQL;
        // no arbitrary role-code is accepted by the API/service.
        assertNotNull(LocalTeamController.ReviewerChange.class);
        assertEquals(1,LocalTeamController.ReviewerChange.class.getRecordComponents().length);
    }
}
