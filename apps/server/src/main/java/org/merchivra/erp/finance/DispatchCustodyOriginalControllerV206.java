package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.merchivra.erp.purchasing.LotProofBinaryService;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Owner/CSRF private original input; NO public download and NO cost posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class DispatchCustodyOriginalControllerV206 {
    private final TenantAccessService access;
    private final DispatchCustodyOriginalServiceV206 originals;
    public DispatchCustodyOriginalControllerV206(TenantAccessService access,
        DispatchCustodyOriginalServiceV206 originals) {
        this.access=access;this.originals=originals;
    }

    @GetMapping("/custody-original-integrity")
    public ResponseEntity<DispatchCustodyOriginalServiceV206.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            originals.preview(access.currentTenant(auth,request),
                marketplaceId,orderId));
    }

    @PostMapping(value="/custody-dossiers/{dossierId}/originals/{kind}",
        consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DispatchCustodyOriginalServiceV206.OriginalInfo> upload(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,@PathVariable String kind,
        @RequestPart("file") MultipartFile file) throws IOException {
        if(file==null||file.isEmpty()||
            file.getSize()>LotProofBinaryService.MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Private PDF/PNG/JPEG original required, maximum 2 MiB");
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(originals.upload(access.currentTenant(auth,request),
                marketplaceId,orderId,dossierId,access.authenticatedUser(auth),
                kind,file.getBytes()));
    }
}
