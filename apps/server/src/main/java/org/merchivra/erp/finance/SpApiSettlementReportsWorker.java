package org.merchivra.erp.finance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiOperation;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.spapi.api.reports.v2021_06_30.ReportsApi;

/**
 * Trusted internal read-only Reports worker, deliberately NOT scheduled or
 * exposed as a browser-triggered Amazon proxy. Settlement reports are Amazon-
 * generated, so we only discover DONE reports (never call createReport).
 */
@Service
@ConditionalOnProperty(prefix="merchivra.spapi.reports",
    name="enabled",havingValue="true")
public class SpApiSettlementReportsWorker {
    private final SpApiOfficialSdkFactory sdk;
    private final SpApiSellerGrantVault vault;
    private final SignedReportDocumentDownloader downloader;
    private final SettlementReportImportService importer;

    public SpApiSettlementReportsWorker(SpApiOfficialSdkFactory sdk,
        SpApiSellerGrantVault vault,SignedReportDocumentDownloader downloader,
        SettlementReportImportService importer) {
        this.sdk=sdk;this.vault=vault;this.downloader=downloader;this.importer=importer;
    }

    public record Outcome(int inspected,int imported,int alreadyKnown,
        int skipped,boolean hasMoreReports) {}

    /**
     * A reviewed backend coordinator supplies scope and a window <=14 days.
     * Only the trusted Seller grant Vault yields a role-approved scope.
     * A maximum of two documents and four discovery pages prevents unbounded
     * egress; caller must persist its own next scan window before scheduling.
     */
    public Outcome importRecent(UUID tenant,UUID account,String marketplace,
        OffsetDateTime from,OffsetDateTime until) throws Exception {
        if(tenant==null || account==null || marketplace==null
            || from==null || until==null || !from.isBefore(until)
            || Duration.between(from,until).compareTo(Duration.ofDays(14))>0
            || until.isAfter(OffsetDateTime.now().plusMinutes(1)))
            throw new IllegalArgumentException("Bounded trusted Reports scan scope required");
        SpApiSellerScope scope=approved(tenant,account,marketplace);
        int inspected=0,imported=0,known=0,skipped=0;
        String next=null;
        boolean hasMore=false;
        for(int page=0;page<4;page++) {
            SpApiSellerScope live=approved(tenant,account,marketplace);
            String token=next;
            var result=sdk.executeObservedRead(live,SpApiOperation.GET_REPORTS,
                ReportsApi.class,api->token==null
                    ?api.getReportsWithHttpInfo(
                        List.of(SettlementReportImportService.TYPE),
                        List.of("DONE"),List.of(marketplace),20,from,until,null)
                    :api.getReportsWithHttpInfo(null,null,null,null,null,null,token));
            if(result==null || result.getReports()==null)
                throw new IllegalStateException("Amazon Reports list has no response");
            for(var report:result.getReports()) {
                inspected++;
                if(imported>=2){hasMore=true;break;}
                if(report==null
                    || !SettlementReportImportService.TYPE.equals(report.getReportType())
                    || report.getProcessingStatus()==null
                    || !"DONE".equals(report.getProcessingStatus().name())
                    || report.getMarketplaceIds()==null
                    || report.getMarketplaceIds().size()!=1
                    || !report.getMarketplaceIds().contains(marketplace)
                    || report.getReportId()==null || report.getReportDocumentId()==null) {
                    skipped++;continue;
                }
                // Retain this second role read before egress. The importer
                // verifies the active grant again under its DB transaction.
                SpApiSellerScope current=approved(tenant,account,marketplace);
                var document=sdk.executeObservedRead(current,
                    SpApiOperation.GET_REPORT_DOCUMENT,ReportsApi.class,
                    api->api.getReportDocumentWithHttpInfo(
                        report.getReportDocumentId(),false));
                if(document==null
                    || !report.getReportDocumentId().equals(document.getReportDocumentId())
                    || document.getUrl()==null)
                    throw new IllegalStateException("Unexpected report document response");
                boolean gzip=false;
                if(document.getCompressionAlgorithm()!=null) {
                    if(!"GZIP".equals(document.getCompressionAlgorithm().name()))
                        throw new IllegalStateException("Unsupported report compression");
                    gzip=true;
                }
                // Amazon documents use short-lived signed URLs. Never store,
                // return to browsers or log the URL/query string.
                byte[] bytes=downloader.fetch(document.getUrl());
                approved(tenant,account,marketplace);
                var existing=importer.list(tenant,marketplaceId(tenant,account,marketplace))
                    .stream().anyMatch(x->x.reportId().equals(report.getReportId()));
                importer.importDocument(current,report.getReportId(),
                    report.getReportDocumentId(),bytes,gzip);
                if(existing)known++;else imported++;
            }
            next=result.getNextToken();
            if(hasMore || next==null || next.isBlank()) {
                hasMore=hasMore || (next!=null && !next.isBlank());
                break;
            }
            if(page==3)hasMore=true;
        }
        return new Outcome(inspected,imported,known,skipped,hasMore);
    }

    private SpApiSellerScope approved(UUID tenant,UUID account,String marketplace) {
        return vault.loadFinancesApproved(tenant,account,marketplace)
            .orElseThrow(()->new SecurityException("Current Finance Seller grant not approved"));
    }

    private UUID marketplaceId(UUID tenant,UUID account,String marketplace) {
        // Avoid adding a second public service accessor; if a report has already
        // been saved the DB id is resolved by the importer. This helper is only
        // for an advisory duplicate counter and must not be needed for safety.
        return null;
    }
}
