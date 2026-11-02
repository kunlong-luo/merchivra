package org.merchivra.erp.operations;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.merchivra.erp.fba.FbaPurchaseExceptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V80: read-only, explainable cross-module operations review. Reuses V77/V79
 * case/evidence/owner projections and V10 operations findings. Suggestions are
 * NOT scheduled actions, Seller verification or Amazon API results.
 */
@Service
public class OperationsExceptionInsightsService {
    private static final int TOP_LIMIT=30;
    private final JdbcTemplate jdbc;
    private final FbaPurchaseExceptionService exceptions;
    private final OperationsExceptionPriorityService priorities;

    public OperationsExceptionInsightsService(JdbcTemplate jdbc,
                                               FbaPurchaseExceptionService exceptions,
                                               OperationsExceptionPriorityService priorities) {
        this.jdbc=jdbc;
        this.exceptions=exceptions;
        this.priorities=priorities;
    }
    public record Category(String ruleCode,long open,long acknowledged,
                           long done,long cleared) {}
    public record Recommendation(String lineId,String shipmentId,
        String shipmentReference,String sellerSku,String findingCode,
        String severity,String state,String reminder,String assigneeId,
        String recommendedOwnerId,String dueAt,String priority,
        String reasonCode,String nextStep) {}
    public record Insights(String marketplaceId,boolean amazonVerified,
        boolean fbaStockCredited,boolean sourceTruncated,int shipmentScope,
        int evidenceLines,long actionableCount,boolean recommendationsTruncated,
        FbaPurchaseExceptionService.Metrics cases,
        FbaPurchaseExceptionService.Workload workload,
        List<Category> operations,List<Recommendation> recommendations) {}
    public record Decision(String priority,String code,String nextStep) {}

    /**
     * Fixed rules. Priority is evidence triage, not a learned probability or
     * carrier delivery estimate. Resolved+stale is still actionable.
     */
    public static Decision decide(FbaPurchaseExceptionService.WorkItem item) {
        if(item.stale() || "RECHECK".equals(item.reminder()))
            return new Decision("P0","SOURCE_CHANGED","复查原始 PO/ASN/FBA 来源，再由 Owner 重新确认");
        if("OVERDUE".equals(item.reminder()))
            return new Decision("P0","PAST_DUE","联系当前负责人并人工调整跟进期限");
        if("PENDING_IDENTITY_REVIEW".equals(item.findingCode()))
            return new Decision("P1","INDEPENDENT_IDENTITY_REVIEW","先完成 V75 第二 Owner 身份证据审核");
        if("LOCAL_OVER_REPORT".equals(item.findingCode()))
            return new Decision("P1","LOCAL_VARIANCE_REVIEW","先完成 V65 本地入仓差异独立复核");
        if("DUE_SOON".equals(item.reminder()))
            return new Decision("P1","DUE_SOON","优先核对未完成事项及当前人工期限");
        if("HIGH".equals(item.severity()))
            return new Decision("P1","HIGH_SEVERITY","检查高风险本地证据并决定人工跟进");
        if(item.assigneeId()==null)
            return new Decision("P2","NEEDS_OWNER","向有效 Owner 人工分派并记录依据");
        if("WARNING".equals(item.severity()))
            return new Decision("P2","SOURCE_WARNING","核对采购与 FBA 各自来源阶段，不混算数量");
        return new Decision("P3","ROUTINE_FOLLOWUP","在原处理单记录下一步证据和处理结果");
    }

    @Transactional(readOnly=true)
    public Insights insights(UUID tenant,UUID marketplace) {
        // Board and Metrics fail closed for inactive/foreign Marketplaces, even
        // when no persisted ops finding currently exists for the requested ID.
        var board=exceptions.board(tenant,marketplace);
        var metrics=exceptions.metrics(tenant,marketplace);
        var workload=exceptions.workload(tenant,marketplace);

        List<Category> categories=jdbc.query("""
            SELECT rule_code,
              count(*) FILTER (WHERE state='OPEN') AS open_count,
              count(*) FILTER (WHERE state='ACKNOWLEDGED') AS ack_count,
              count(*) FILTER (WHERE state='DONE') AS done_count,
              count(*) FILTER (WHERE state='CLEARED') AS cleared_count
            FROM ops_finding
            WHERE tenant_id=? AND seller_marketplace_id=?
            GROUP BY rule_code ORDER BY rule_code
            """,(r,i)->new Category(r.getString("rule_code"),
                r.getLong("open_count"),r.getLong("ack_count"),
                r.getLong("done_count"),r.getLong("cleared_count")),
            tenant,marketplace);

        var overrides=priorities.rules(tenant,marketplace).stream()
            .collect(java.util.stream.Collectors.toMap(
                OperationsExceptionPriorityService.Rule::reasonCode,
                OperationsExceptionPriorityService.Rule::priority));
        // Advice for unassigned work is deterministic and advisory only. No
        // out-of-tenant assignee, no fabricated capacity or auto-dispatch.
        String lightest=workload.owners().stream()
            .min(Comparator.comparingLong(
                (FbaPurchaseExceptionService.OwnerLoad x)->x.open()+x.acknowledged())
                .thenComparingLong(FbaPurchaseExceptionService.OwnerLoad::overdue)
                .thenComparing(FbaPurchaseExceptionService.OwnerLoad::userId))
            .map(FbaPurchaseExceptionService.OwnerLoad::userId).orElse(null);
        List<Recommendation> ordered=board.items().stream()
            .filter(item->!"RESOLVED".equals(item.state()) || item.stale())
            .map(item->{
                Decision decision=decide(item);
                return new Recommendation(item.lineId(),item.shipmentId(),
                    item.shipmentReference(),item.sellerSku(),item.findingCode(),
                    item.severity(),item.state(),item.reminder(),item.assigneeId(),
                    item.assigneeId()==null?lightest:null,item.dueAt(),
                    overrides.getOrDefault(decision.code(),decision.priority()),
                    decision.code(),decision.nextStep());
            }).sorted(Comparator
                .comparing(Recommendation::priority)
                .thenComparing(x->Objects.toString(x.dueAt(),"9999"))
                .thenComparing(Recommendation::lineId))
            .toList();
        return new Insights(marketplace.toString(),false,false,
            board.truncated(),board.shipmentScope(),board.items().size(),
            ordered.size(),ordered.size()>TOP_LIMIT,metrics,workload,
            categories,ordered.stream().limit(TOP_LIMIT).toList());
    }
}
