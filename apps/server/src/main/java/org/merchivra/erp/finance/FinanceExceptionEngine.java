package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure advisory reconciliation diagnostics. A flag describes a review gap,
 * never an Amazon accounting conclusion or a confirmed missing payment.
 */
public final class FinanceExceptionEngine {
    private FinanceExceptionEngine() {}

    public record SourceSignal(String id,String key,String currency,BigDecimal amount,
        String orderReference,boolean orderReviewed,boolean settlementAssigned,
        long leafCount,long missingMoney,long currencyMismatch,long unmappedLeaves,
        BigDecimal leafTotal) {}
    public record Finding(String code,String severity,String subjectType,
        String subjectId,String sourceReference,String currency,String signedAmount,
        String nextAction) {}
    public static List<Finding> source(SourceSignal v) {
        Objects.requireNonNull(v);
        if(v.id()==null||v.key()==null||v.currency()==null||v.amount()==null)
            throw new IllegalArgumentException("Incomplete finance source signal");
        if(v.leafCount()<0||v.missingMoney()<0||v.currencyMismatch()<0
            ||v.unmappedLeaves()<0)
            throw new IllegalArgumentException("Invalid breakdown coverage");
        var items=new ArrayList<Finding>();
        String amount=v.amount().toPlainString();
        if(v.leafCount()==0)
            items.add(finding("SOURCE_BREAKDOWN_ABSENT","REVIEW",v,amount,
                "检查官方 transaction breakdown 是否已完整同步；缺失不等于零费用"));
        else if(v.missingMoney()>0)
            items.add(finding("SOURCE_AMOUNT_INCOMPLETE","BLOCKING",v,amount,
                "核查末级明细的来源金额；禁止以零补足"));
        else if(v.currencyMismatch()>0)
            items.add(finding("SOURCE_CURRENCY_MISMATCH","BLOCKING",v,amount,
                "检查原币差异；没有审核过的汇率不得混合汇总"));
        else if(v.unmappedLeaves()>0)
            items.add(finding("SOURCE_TYPE_UNCLASSIFIED","REVIEW",v,amount,
                "在财务工作台人工审批末级 sourceType 分类规则"));
        else if(v.leafTotal()==null||v.leafTotal().compareTo(v.amount())!=0)
            items.add(finding("SOURCE_BREAKDOWN_DIFFERS","BLOCKING",v,amount,
                "核对已获取末级有符号金额与原始交易 totalAmount 的差异"));
        if(v.orderReference()!=null&&!v.orderReference().isBlank()&&!v.orderReviewed())
            items.add(finding("ORDER_LINK_UNREVIEWED","REVIEW",v,amount,
                "核对来源 Amazon Order ID 并显式审核订单关联"));
        if(!v.settlementAssigned())
            items.add(finding("SETTLEMENT_UNASSIGNED","INFO",v,amount,
                "仅为待分配来源；结合正式结算编号和人工参考单核对，不代表漏付款"));
        return List.copyOf(items);
    }
    private static Finding finding(String code,String severity,SourceSignal source,
        String amount,String action) {
        return new Finding(code,severity,"SOURCE_TRANSACTION",source.id(),
            source.key(),source.currency(),amount,action);
    }

    public static List<Finding> settlement(String id,String reference,String currency,
        String status,String delta,int unassigned) {
        if(id==null||reference==null||currency==null||status==null)
            throw new IllegalArgumentException("Incomplete settlement signal");
        var out=new ArrayList<Finding>();
        if(!"SIGNED_TOTAL_MATCHES_UNVERIFIED".equals(status)) {
            String severity=switch(status) {
                case "CURRENCY_MISMATCH","SIGNED_TOTAL_DIFFERS",
                     "OUTSIDE_STATEMENT_PERIOD" -> "BLOCKING";
                default -> "REVIEW";
            };
            String action=switch(status) {
                case "NO_LINKED_EVENTS" -> "将受信 LIVE 交易人工分配到此参考结算单";
                case "CURRENCY_MISMATCH" -> "同一参考单存在不同原币来源；停止加总并核对";
                case "OUTSIDE_STATEMENT_PERIOD" -> "检查结算期间边界和期间外关联";
                case "SIGNED_TOTAL_DIFFERS" -> "核实有符号交易总额、遗漏及结算参考金额";
                default -> "检查未识别的结算来源状态";
            };
            out.add(new Finding("STATEMENT_"+status,severity,"SETTLEMENT_REFERENCE",
                id,reference,currency,delta,action));
        }
        if(unassigned>0)
            out.add(new Finding("STATEMENT_UNASSIGNED_CANDIDATES","INFO",
                "SETTLEMENT_REFERENCE",id,reference,currency,null,
                "该期间有 "+unassigned+" 笔未分配候选；人工核对并判断是否属于此结算单"));
        return List.copyOf(out);
    }
    public static List<Finding> order(String id,String reference,String currency,
        OrderCostEngine.Preview costs) {
        Objects.requireNonNull(costs);
        if("COGS_READY_PREVIEW".equals(costs.readiness()))return List.of();
        return List.of(new Finding("ORDER_COST_"+costs.readiness(),"REVIEW",
            "ORDER_COST",id,reference,currency,null,
            "检查订单来源、业务日期、当前订单项、历史 SKU 映射与成本原币；覆盖 "
              +costs.coveredItems()+"/"+costs.itemCount()+" 项"));
    }
}
