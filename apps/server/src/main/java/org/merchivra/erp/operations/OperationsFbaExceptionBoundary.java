package org.merchivra.erp.operations;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.merchivra.erp.fba.FbaExceptionPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V85 integration boundary: V78 FBA_PO_* findings represent the same local
 * human evidence as the V77 workbench. All other Operations rule families
 * retain their existing independent OWNER semantics.
 */
@Service
public class OperationsFbaExceptionBoundary {
    private static final Set<String> FBA_RULES=Set.of(
        "FBA_PO_EVIDENCE_CHANGED","FBA_PO_EXCEPTION_OVERDUE");
    private final JdbcTemplate jdbc;
    private final FbaExceptionPermissionService permissions;

    public OperationsFbaExceptionBoundary(JdbcTemplate jdbc,
                                           FbaExceptionPermissionService permissions) {
        this.jdbc=jdbc;
        this.permissions=permissions;
    }
    public static boolean isFbaRule(String rule) {
        return FBA_RULES.contains(rule);
    }
    public void rule(UUID tenant,UUID marketplace,UUID actor,String code,
                     FbaExceptionPermissionService.Capability capability) {
        if(isFbaRule(code))permissions.require(tenant,marketplace,actor,capability);
    }
    public void finding(UUID tenant,UUID actor,UUID finding,
                        FbaExceptionPermissionService.Capability capability) {
        var found=jdbc.query("""
            SELECT seller_marketplace_id,rule_code FROM ops_finding
            WHERE tenant_id=? AND id=?
            """,(r,i)->new Object[]{
                r.getObject("seller_marketplace_id",UUID.class),r.getString("rule_code")
            },tenant,finding);
        if(found.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Finding not found");
        Object[] row=found.getFirst();
        if(isFbaRule((String)row[1]))
            permissions.require(tenant,(UUID)row[0],actor,capability);
    }
    private Set<String> deniedSites(UUID tenant,UUID actor) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT seller_marketplace_id FROM fba_exception_owner_permission
            WHERE tenant_id=? AND owner_user_id=? AND NOT can_view
            """,UUID.class,tenant,actor).stream()
            .map(UUID::toString).collect(Collectors.toSet()));
    }
    @Transactional(readOnly=true)
    public List<OperationsAutomationService.Finding> visible(
        UUID tenant,UUID actor,List<OperationsAutomationService.Finding> rows) {
        Set<String> denied=deniedSites(tenant,actor);
        return rows.stream().filter(row->!isFbaRule(row.ruleCode())
            ||!denied.contains(row.marketplaceId())).toList();
    }
    @Transactional(readOnly=true)
    public List<OperationsAutomationService.RuleSetting> visibleRules(
        UUID tenant,UUID marketplace,UUID actor,
        List<OperationsAutomationService.RuleSetting> rules) {
        if(!deniedSites(tenant,actor).contains(marketplace.toString()))return rules;
        return rules.stream().filter(row->!isFbaRule(row.ruleCode())).toList();
    }
    @Transactional(readOnly=true)
    public List<OperationsAutomationService.RuleAudit> visibleRuleAudit(
        UUID tenant,UUID marketplace,UUID actor,
        List<OperationsAutomationService.RuleAudit> rows) {
        if(!deniedSites(tenant,actor).contains(marketplace.toString()))return rows;
        return rows.stream().filter(row->!isFbaRule(row.ruleCode())).toList();
    }
    /**
     * Scan is a tenant-wide mutating operation. If any FBA marketplace denies
     * CONFIGURE, reject the whole manual scan instead of silently processing
     * that source without authorization. Scheduled scans remain independent
     * system maintenance and cannot be triggered through this endpoint.
     */
    @Transactional(readOnly=true)
    public void manualScan(UUID tenant,UUID actor) {
        List<UUID> restricted=jdbc.queryForList("""
            SELECT seller_marketplace_id FROM fba_exception_owner_permission
            WHERE tenant_id=? AND owner_user_id=? AND NOT can_configure
            """,UUID.class,tenant,actor);
        if(!restricted.isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Manual tenant-wide Operations scan requires exception configuration permission");
    }
    /** Aggregate all persisted Ops findings, not only the first 100 displayed.
     * Drop only FBA_PO findings from explicitly VIEW-denied marketplaces.
     */
    @Transactional(readOnly=true)
    public OperationsAutomationService.Summary visibleSummary(UUID tenant,UUID actor) {
        return jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE f.state='OPEN'),
                   count(*) FILTER (WHERE f.state='ACKNOWLEDGED'),
                   count(*) FILTER (WHERE f.state='DONE'),
                   count(*) FILTER (WHERE f.state='CLEARED'),
                   count(*) FILTER (WHERE f.state IN ('OPEN','ACKNOWLEDGED')
                                     AND f.snoozed_until>now())
            FROM ops_finding f
            WHERE f.tenant_id=?
              AND NOT (
                f.rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
                AND EXISTS (
                    SELECT 1 FROM fba_exception_owner_permission p
                    WHERE p.tenant_id=f.tenant_id
                      AND p.seller_marketplace_id=f.seller_marketplace_id
                      AND p.owner_user_id=? AND NOT p.can_view
                )
              )
            """,(r,i)->new OperationsAutomationService.Summary(
                r.getLong(1),r.getLong(2),r.getLong(3),
                r.getLong(4),r.getLong(5)),tenant,actor);
    }
}
