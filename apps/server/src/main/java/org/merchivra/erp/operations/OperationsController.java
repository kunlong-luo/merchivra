package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** All operations derive tenant & actor from authenticated server Session. CSRF on POST. */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final TenantAccessService access;
    private final OperationsAutomationService service;

    public OperationsController(TenantAccessService access, OperationsAutomationService service) {
        this.access = access;
        this.service = service;
    }
    public record ChangeState(String state) {}
    public record Snooze(@Min(15) @Max(1440) int minutes,
                         @NotBlank @Size(min=10,max=300) String note) {}

    @Operation(operationId="listOperationFindings")
    @GetMapping("/findings")
    public List<OperationsAutomationService.Finding> findings(
        Authentication auth, HttpServletRequest request,
        @RequestParam(defaultValue = "ACTIVE") String filter) {
        return service.findings(access.currentTenant(auth, request), filter);
    }

    @Operation(operationId="getOperationsSummary")
    @GetMapping("/summary")
    public OperationsAutomationService.Summary summary(Authentication auth, HttpServletRequest request) {
        return service.summary(access.currentTenant(auth, request));
    }

    @Operation(operationId="scanOperationsNow")
    @PostMapping("/scan")
    public OperationsAutomationService.ScanResult scan(Authentication auth, HttpServletRequest request) {
        UUID tenant = access.currentTenant(auth, request);
        return service.scan(tenant, access.authenticatedUser(auth), "MANUAL");
    }

    @Operation(operationId="setOperationFindingState")
    @PostMapping("/findings/{findingId}/state")
    public OperationsAutomationService.FindingChange change(
        Authentication auth, HttpServletRequest request,
        @PathVariable UUID findingId, @RequestBody ChangeState body) {
        UUID tenant = access.currentTenant(auth, request);
        return service.changeState(tenant, access.authenticatedUser(auth), findingId,
            body == null ? null : body.state());
    }
    @Operation(operationId="claimOperationFinding")
    @PostMapping("/findings/{findingId}/claim")
    public OperationsAutomationService.Attention claim(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        return service.claim(access.currentTenant(auth,request),
            access.authenticatedUser(auth),findingId);
    }

    @Operation(operationId="releaseOperationFinding")
    @PostMapping("/findings/{findingId}/release")
    public OperationsAutomationService.Attention release(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        return service.release(access.currentTenant(auth,request),
            access.authenticatedUser(auth),findingId);
    }

    @Operation(operationId="snoozeOperationFinding")
    @PostMapping("/findings/{findingId}/snooze")
    public OperationsAutomationService.Attention snooze(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId,
        @Valid @RequestBody Snooze value) {
        return service.snooze(access.currentTenant(auth,request),
            access.authenticatedUser(auth),findingId,value.minutes(),value.note());
    }

    @Operation(operationId="unsnoozeOperationFinding")
    @PostMapping("/findings/{findingId}/unsnooze")
    public OperationsAutomationService.Attention unsnooze(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        return service.unsnooze(access.currentTenant(auth,request),
            access.authenticatedUser(auth),findingId);
    }

    @Operation(operationId="listOperationRules")
    @GetMapping("/marketplaces/{marketplaceId}/rules")
    public List<OperationsAutomationService.RuleSetting> rules(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return service.rules(access.currentTenant(auth,request),marketplaceId);
    }

    @Operation(operationId="saveOperationRule")
    @PostMapping("/marketplaces/{marketplaceId}/rules/{ruleCode}")
    public OperationsAutomationService.RuleSetting saveRule(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable String ruleCode,
        @RequestBody OperationsAutomationService.SaveRule body) {
        UUID tenant=access.currentTenant(auth,request);
        return service.saveRule(tenant,access.authenticatedUser(auth),
            marketplaceId,ruleCode,body);
    }

    @Operation(operationId="listOperationRuleAudit")
    @GetMapping("/marketplaces/{marketplaceId}/rules/audit")
    public List<OperationsAutomationService.RuleAudit> ruleAudit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return service.ruleHistory(access.currentTenant(auth,request),marketplaceId);
    }

    @Operation(operationId="getOperationFindingAttentionAudit")
    @GetMapping("/findings/{findingId}/attention-audit")
    public List<OperationsAutomationService.AttentionAudit> attentionAudit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID findingId) {
        return service.attentionHistory(access.currentTenant(auth,request),findingId);
    }

}
