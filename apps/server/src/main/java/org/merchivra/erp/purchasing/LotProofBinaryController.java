package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** V52: tenant Owner-only private attachment API; POST CSRF remains enforced. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-proof-files")
public class LotProofBinaryController {
    private final LotProofBinaryService files;
    private final TenantAccessService access;
    public LotProofBinaryController(LotProofBinaryService files,TenantAccessService access){
        this.files=files;this.access=access;
    }
    @PostMapping(value="/{proofId}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LotProofBinaryService.FileInfo upload(Authentication auth,HttpServletRequest req,
        @PathVariable UUID proofId,@RequestParam UUID requestKey,
        @RequestPart("file") MultipartFile file) throws IOException {
        if(file==null || file.isEmpty() || file.getSize()>LotProofBinaryService.MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Private evidence file must be 1 byte to 2 MiB");
        return files.upload(access.currentTenant(auth,req),
            access.authenticatedUser(auth),proofId,requestKey,file.getBytes());
    }
    @GetMapping("/{proofId}")
    public LotProofBinaryService.FileInfo metadata(Authentication auth,
        HttpServletRequest req,@PathVariable UUID proofId){
        return files.metadata(access.currentTenant(auth,req),proofId);
    }
    @GetMapping("/{proofId}/download")
    public ResponseEntity<byte[]> download(Authentication auth,HttpServletRequest req,
        @PathVariable UUID proofId){
        var file=files.download(access.currentTenant(auth,req),
            access.authenticatedUser(auth),proofId);
        String extension=switch(file.info().mediaType()){
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        // Force a download, not inline rendering of untrusted submitted bytes.
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"lot-proof-"+proofId+extension+"\"")
            .header("X-Content-Type-Options","nosniff")
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(file.bytes());
    }
}
