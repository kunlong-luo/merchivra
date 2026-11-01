package org.merchivra.erp.finance;

import java.util.List;

/**
 * Fixed-header UTF-8 export of precisely the bounded report page. Prevent
 * spreadsheet formula injection; no PII or token fields and no realized profit.
 */
public final class SkuProfitCsv {
    private SkuProfitCsv(){}
    static String cell(String raw) {
        if(raw==null)return "";
        String safe=raw;
        if(!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0))>=0)
            safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")
            .replace("\r"," ").replace("\n"," ").replace("\t"," ")+"\"";
    }
    public static String export(SkuProfitPreviewService.Preview p) {
        StringBuilder out=new StringBuilder("\uFEFF");
        out.append("order_id,source_order_id,order_at_utc,internal_sku,units,readiness,")
            .append("original_currency,reviewed_event_count,signed_sales,")
            .append("signed_amazon_fees,signed_fba_fees,provisional_cogs,")
            .append("estimated_contribution,period_after_utc,period_before_utc,")
            .append("page_offset,has_more,financial_source_complete,bank_verified\n");
        for(var o:p.orders()) {
            List<String> values=List.of(o.orderId(),
                o.externalOrderId()==null?"":o.externalOrderId(),
                o.orderedAt()==null?"":o.orderedAt(),o.sku()==null?"":o.sku(),
                Integer.toString(o.units()),o.readiness(),o.currency(),
                Integer.toString(o.reviewedEventCount()),
                o.sales()==null?"":o.sales(),o.amazonFees()==null?"":o.amazonFees(),
                o.fbaFees()==null?"":o.fbaFees(),o.cogs()==null?"":o.cogs(),
                o.contribution()==null?"":o.contribution(),p.periodAfter(),
                p.periodBefore(),Integer.toString(p.offset()),
                Boolean.toString(p.hasMore()),"false","false");
            out.append(String.join(",",values.stream().map(SkuProfitCsv::cell).toList()))
                .append('\n');
        }
        return out.toString();
    }
}
