package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsKeyPossessionServiceTest {
    @Test void domainSeparatedOneTimeChallengeRequiresExactTenantSourceWarehouseAndExpiry()
        throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        UUID tenant=UUID.randomUUID(),source=UUID.randomUUID(),
            warehouse=UUID.randomUUID(),challenge=UUID.randomUUID();
        String nonce="e".repeat(64);
        Instant expiry=Instant.parse("2026-11-18T10:30:00Z");
        String text=WmsKeyPossessionService.canonical(tenant,source,
            warehouse,challenge,nonce,expiry);
        assertTrue(text.startsWith("MERCHIVRA_WMS_KEY_POSSESSION_V1\n"));
        assertTrue(text.endsWith("2026-11-18T10:30:00Z\n"));
        var signer=Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(text.getBytes(StandardCharsets.UTF_8));
        String signature=Base64.getEncoder().encodeToString(signer.sign());
        assertTrue(WmsSignedReturnService.verify(publicKey,signature,text));
        for(String altered:List.of(
            text.replace(tenant.toString(),UUID.randomUUID().toString()),
            text.replace(warehouse.toString(),UUID.randomUUID().toString()),
            text.replace(source.toString(),UUID.randomUUID().toString()),
            text.replace(challenge.toString(),UUID.randomUUID().toString()),
            text.replace(nonce,"a".repeat(64)),
            text.replace("2026-11-18T10:30:00Z","2026-11-18T10:30:01Z"),
            text.replace("KEY_POSSESSION_V1","KEY_POSSESSION_V2"))) {
            assertFalse(WmsSignedReturnService.verify(publicKey,signature,altered));
        }
        assertThrows(Exception.class,()->WmsKeyPossessionService.canonical(
            tenant,source,warehouse,challenge,"BAD-NONCE",expiry));
    }
    @Test void controllerDerivesOwnerSessionAndNoStoreForAllEndpoints() {
        var access=mock(TenantAccessService.class);
        var service=mock(WmsKeyPossessionService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),
            source=UUID.randomUUID(),challenge=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var c=new WmsKeyPossessionController(access,service);
        assertTrue(c.list(auth,req,source).getHeaders().getCacheControl().contains("no-store"));
        verify(service).list(tenant,source);
        assertTrue(c.issue(auth,req,source).getHeaders().getCacheControl().contains("no-store"));
        verify(service).issue(tenant,source,actor);
        var proof=new WmsKeyPossessionService.ProofInput("base64-detached-signature");
        assertTrue(c.verify(auth,req,source,challenge,proof)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).verify(tenant,source,challenge,actor,proof);
    }
    @Test void databasePreservesUntrustedIdentityAndImmutableNonce() throws Exception {
        String ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V101__wms_key_possession_challenge.sql"));
        for(String marker:List.of(
            "finance_wms_key_challenge_v101","finance_wms_key_proof_v101",
            "UNIQUE(tenant_id,nonce_hex)",
            "UNIQUE(tenant_id,challenge_id)",
            "FOREIGN KEY(tenant_id,source_id,warehouse_id)",
            "FOREIGN KEY(tenant_id,challenge_id,source_id,warehouse_id)",
            "NEW.verified_by IN (challenge_issuer,source_registrar)",
            "now()>=challenge_expiry",
            "finance_wms_key_proof_actor_v101",
            "BEFORE UPDATE OR DELETE ON finance_wms_key_challenge_v101",
            "BEFORE UPDATE OR DELETE ON finance_wms_key_proof_v101",
            "CHECK(operator_identity_verified=FALSE)",
            "CHECK(physical_receipt_verified=FALSE)",
            "CHECK(inventory_posted=FALSE)",
            "CHECK(cogs_reversed=FALSE)"))
            assertTrue(ddl.contains(marker),marker);
        String service=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/WmsKeyPossessionService.java"));
        for(String forbidden:List.of("INSERT INTO erp_local_stock_movement",
            "INSERT INTO finance_profit_report","UPDATE finance_source_event",
            "INSERT INTO finance_cogs"))
            assertFalse(service.contains(forbidden),forbidden);
        assertTrue(service.contains("WmsSignedReturnService.verify"));
        assertTrue(service.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
        assertTrue(service.contains("TOO_MANY_REQUESTS"));
        assertTrue(service.contains("RefundRecoveryService.requireIndependent"));
    }
}
