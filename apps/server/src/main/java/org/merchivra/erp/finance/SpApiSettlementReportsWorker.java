package org.merchivra.erp.finance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiOperation;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.spapi.api.reports.v2021_06_30.ReportsApi;

/**
 * One bounded, resumable Amazon-generated settlement Reports page, no remote writes.
 * Downloads outside SQL transactions; caller fences the immutable import in SQL.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.spapi.reports",name="enabled",havingValue="true")
public class SpApiSettlementReportsWorker {
    private final SpApiOfficialSdkFactory sdk;
    private final SpApiSellerGrantVault vault;
    private final SignedReportDocumentDownloader downloader;
    private final SettlementReportImportService importer;

    public SpApiSettlementReportsWorker(SpApiOfficialSdkFactory sdk,
        SpApiSellerGrantVault vault, SignedReportDocumentDownloader downloader,
        SettlementReportImportService importer) {
        this.sdk=sdk;this.vault=vault;this.downloader=downloader;this.importer=importer;
    }

    /** Cursor/page drift is recoverable by restarting the fixed scan window. */
    public static class StaleCursorException extends IllegalStateException {
        public StaleCursorException() {super("Reports cursor no longer stable");}
    }

    @FunctionalInterface
    public interface FencedImport {
        void persist(SpApiSellerScope scope,String reportId,String documentId,
            byte[] bytes,boolean gzip);
    }
    public record Page(String nextToken,int nextOffset,int inspected,
        int imported,int alreadyKnown,int skipped,boolean done) {}

    /**
     * Page state (nextToken,offset) is committed only after all processed
     * documents commit. If a worker dies first, safe replay skips identical
     * immutable report IDs. Old pagination tokens are reset by the coordinator.
     */
    public Page scanPage(UUID tenant,UUID account,String marketplace,
        OffsetDateTime from,OffsetDateTime until,String token,int offset,int maxNew,
        FencedImport fencedImport) throws Exception {
        if(tenant==null || account==null || marketplace==null || from==null || until==null
            || !from.isBefore(until)
            || Duration.between(from,until).compareTo(Duration.ofDays(14))>0
            || offset<0 || offset>1000 || maxNew<1 || maxNew>2
            || (token!=null && (token.isBlank() || token.length()>8192)))
            throw new IllegalArgumentException("Invalid bounded Reports page scope");
        var seller=approved(tenant,account,marketplace);
        var result=sdk.executeObservedRead(seller,SpApiOperation.GET_REPORTS,
            ReportsApi.class,api->token==null
                ? api.getReportsWithHttpInfo(
                    List.of(SettlementReportImportService.TYPE),
                    List.of("DONE"),List.of(marketplace),20,from,until,null)
                : api.getReportsWithHttpInfo(null,null,null,null,null,null,token));
        if(result==null || result.getReports()==null)
            throw new IllegalStateException("Reports list missing");
        var reports=result.getReports();
        if(offset>reports.size())
            throw new StaleCursorException();
        String following=result.getNextToken();
        if(following!=null && (following.isBlank() || following.length()>8192
            || following.equals(token)))
            throw new StaleCursorException();
        int inspected=0,imported=0,known=0,skipped=0;
        for(int i=offset;i<reports.size();i++) {
            if(imported>=maxNew)
                return new Page(token,i,inspected,imported,known,skipped,false);
            var report=reports.get(i);
            inspected++;
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
            var current=approved(tenant,account,marketplace);
            if(importer.alreadyImported(current,report.getReportId(),
                report.getReportDocumentId())) {
                known++;continue;
            }
            var document=sdk.executeObservedRead(current,SpApiOperation.GET_REPORT_DOCUMENT,
                ReportsApi.class,api->api.getReportDocumentWithHttpInfo(
                    report.getReportDocumentId(),false));
            if(document==null || !report.getReportDocumentId().equals(
                document.getReportDocumentId()) || document.getUrl()==null)
                throw new IllegalStateException("Unexpected report document metadata");
            boolean gzip=false;
            if(document.getCompressionAlgorithm()!=null) {
                if(!"GZIP".equals(document.getCompressionAlgorithm().name()))
                    throw new IllegalStateException("Unsupported document compression");
                gzip=true;
            }
            // Signed URLs are short-lived, never persisted; fresh metadata
            // is fetched on every attempt after any crash or URL expiry.
            byte[] body=downloader.fetch(document.getUrl());
            current=approved(tenant,account,marketplace);
            fencedImport.persist(current,report.getReportId(),
                report.getReportDocumentId(),body,gzip);
            imported++;
        }
        return new Page(following,0,inspected,imported,known,skipped,following==null);
    }

    private SpApiSellerScope approved(UUID tenant,UUID account,String marketplace) {
        return vault.loadFinancesApproved(tenant,account,marketplace)
            .orElseThrow(()->new SecurityException("Finance Seller scope revoked"));
    }
}
