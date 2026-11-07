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
import org.merchivra.erp.fba.FbaExceptionPermissionService;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
    private final OperationsFbaExceptionBoundary fbaBoundary;

    public OperationsController(TenantAccessService access,OperationsAutomationService service,
                                OperationsFbaExceptionBoundary fbaBoundary) {
        this.access = access;
        this.service = service;
        this.fbaBoundary=fbaBoundary;
    }
    public record ChangeState(String state) {}
    public record Snooze(@Min(15) @Max(1440) int minutes,
                         @NotBlank @Size(min=10,max=300) String note) {}

    @Operation(operationId="listOperationFindings")
    @GetMapping("/findings")
    public List<OperationsAutomationService.Finding> findings(
        Authentication auth, HttpServletRequest request,
        @RequestParam(defaultValue = "ACTIVE") String filter) {
        UUID tenant=access.currentTenant(auth,request);
        return service.findings(tenant,filter,access.authenticatedUser(auth));
    }

    @Operation(operationId="listOperationFindingsPage")
    @GetMapping("/findings/page")
    public ResponseEntity<LocalKeysetPage.Page<OperationsAutomationService.Finding>> findingsPage(
        Authentication auth,HttpServletRequest request,
        @RequestParam(defaultValue="ACTIVE") String filter,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.findingsPage(tenant,filter,actor,limit,cursor));
    }

    @Operation(operationId="getOperationsSummary")
    @GetMapping("/summary")
    public OperationsAutomationService.Summary summary(Authentication auth, HttpServletRequest request) {
        return fbaBoundary.visibleSummary(access.currentTenant(auth,request),
            access.authenticatedUser(auth));
    }

    @Operation(operationId="scanOperationsNow")
    @PostMapping("/scan")
    public OperationsAutomationService.ScanResult scan(Authentication auth, HttpServletRequest request) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.manualScan(tenant,actor);
        return service.scan(tenant,actor,"MANUAL");
    }

    @Operation(operationId="setOperationFindingState")
    @PostMapping("/findings/{findingId}/state")
    public OperationsAutomationService.FindingChange change(
        Authentication auth, HttpServletRequest request,
        @PathVariable UUID findingId, @RequestBody ChangeState body) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        String target=body==null?null:body.state();
        fbaBoundary.finding(tenant,actor,findingId,
            "DONE".equals(target)?FbaExceptionPermissionService.Capability.RESOLVE:
                FbaExceptionPermissionService.Capability.REVIEW);
        return service.changeState(tenant,actor,findingId,target);
    }
    @Operation(operationId="claimOperationFinding")
    @PostMapping("/findings/{findingId}/claim")
    public OperationsAutomationService.Attention claim(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.finding(tenant,actor,findingId,
            FbaExceptionPermissionService.Capability.ASSIGN);
        return service.claim(tenant,actor,findingId);
    }

    @Operation(operationId="releaseOperationFinding")
    @PostMapping("/findings/{findingId}/release")
    public OperationsAutomationService.Attention release(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.finding(tenant,actor,findingId,
            FbaExceptionPermissionService.Capability.ASSIGN);
        return service.release(tenant,actor,findingId);
    }

    @Operation(operationId="snoozeOperationFinding")
    @PostMapping("/findings/{findingId}/snooze")
    public OperationsAutomationService.Attention snooze(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId,
        @Valid @RequestBody Snooze value) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.finding(tenant,actor,findingId,
            FbaExceptionPermissionService.Capability.REVIEW);
        return service.snooze(tenant,actor,findingId,value.minutes(),value.note());
    }

    @Operation(operationId="unsnoozeOperationFinding")
    @PostMapping("/findings/{findingId}/unsnooze")
    public OperationsAutomationService.Attention unsnooze(Authentication auth,
        HttpServletRequest request,@PathVariable UUID findingId) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.finding(tenant,actor,findingId,
            FbaExceptionPermissionService.Capability.REVIEW);
        return service.unsnooze(tenant,actor,findingId);
    }

    @Operation(operationId="listOperationRules")
    @GetMapping("/marketplaces/{marketplaceId}/rules")
    public List<OperationsAutomationService.RuleSetting> rules(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        UUID tenant=access.currentTenant(auth,request);
        return fbaBoundary.visibleRules(tenant,marketplaceId,
            access.authenticatedUser(auth),service.rules(tenant,marketplaceId));
    }

    @Operation(operationId="saveOperationRule")
    @PostMapping("/marketplaces/{marketplaceId}/rules/{ruleCode}")
    public OperationsAutomationService.RuleSetting saveRule(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable String ruleCode,
        @Valid @RequestBody OperationsAutomationService.SaveRule body) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.rule(tenant,marketplaceId,actor,ruleCode,
            FbaExceptionPermissionService.Capability.CONFIGURE);
        return service.saveRule(tenant,actor,marketplaceId,ruleCode,body);
    }

    @Operation(operationId="listOperationRuleAudit")
    @GetMapping("/marketplaces/{marketplaceId}/rules/audit")
    public List<OperationsAutomationService.RuleAudit> ruleAudit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        UUID tenant=access.currentTenant(auth,request);
        return fbaBoundary.visibleRuleAudit(tenant,marketplaceId,
            access.authenticatedUser(auth),service.ruleHistory(tenant,marketplaceId));
    }

    @Operation(operationId="getOperationFindingAttentionAudit")
    @GetMapping("/findings/{findingId}/attention-audit")
    public List<OperationsAutomationService.AttentionAudit> attentionAudit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID findingId) {
        UUID tenant=access.currentTenant(auth,request);
        UUID actor=access.authenticatedUser(auth);
        fbaBoundary.finding(tenant,actor,findingId,
            FbaExceptionPermissionService.Capability.VIEW);
        return service.attentionHistory(tenant,findingId);
    }

}
