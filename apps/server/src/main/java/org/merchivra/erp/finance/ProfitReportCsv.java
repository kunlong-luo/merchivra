package org.merchivra.erp.finance;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Complete bounded frozen report; no re-query of current Finances source. */
@Service
public class ProfitReportCsv {
    private final ProfitReportJobService reports;
    public ProfitReportCsv(ProfitReportJobService reports){this.reports=reports;}
    static String encode(List<String> cells) {
        return String.join(",",cells.stream().map(SkuProfitCsv::cell).toList())+"\n";
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String export(UUID tenant,UUID mp,UUID id) {
        var first=reports.report(tenant,mp,id,0);
        if(!"SUCCEEDED".equals(first.job().state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only completed immutable extracts may be exported");
        StringBuilder out=new StringBuilder("\uFEFF");
        out.append(encode(List.of("kind","sku","source_order_uuid","readiness",
            "original_currency","units","signed_sales","signed_amazon_fees",
            "signed_fba_fees","provisional_cogs","estimated_contribution",
            "period_after_utc","period_before_utc","extract_sha256",
            "financial_source_complete","bank_verified","ledger_posted")));
        for(var sku:first.skus()){
            out.append(encode(List.of("SKU",sku.sku(),"","REVIEWED_SOURCE_PREVIEW",
                sku.currency(),Integer.toString(sku.units()),sku.sales(),
                sku.amazonFees(),sku.fbaFees(),sku.cogs(),sku.contribution(),
                first.job().after(),first.job().before(),"","false","false","false")));
        }
        int offset=0;
        ProfitReportJobService.Report page=first;
        do {
            for(var order:page.orders()) {
                out.append(encode(List.of("ORDER","",order.orderId(),
                    order.readiness()==null?"PENDING":order.readiness(),
                    order.currency(),"",empty(order.sales()),empty(order.fees()),
                    empty(order.fba()),empty(order.cogs()),
                    empty(order.contribution()),first.job().after(),
                    first.job().before(),empty(order.extractSha256()),
                    "false","false","false")));
            }
            offset+=100;
            if(offset>5000)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report pagination upper bound exceeded");
            page=page.hasMore()?reports.report(tenant,mp,id,offset):null;
        }while(page!=null);
        return out.toString();
    }
    private static String empty(String value){return value==null?"":value;}
}
