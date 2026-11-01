package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/purchasing/piece-grades")
public class ReceiptPieceGradeController {
    private final ReceiptPieceGradeService service;
    private final TenantAccessService access;
    public ReceiptPieceGradeController(ReceiptPieceGradeService service,TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/receipts/{receiptId}")
    public List<ReceiptPieceGradeService.Grade> grades(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId){
        return service.grades(access.currentTenant(auth,req),receiptId);
    }
    @PostMapping("/receipts/{receiptId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptPieceGradeService.Grade grade(Authentication auth,HttpServletRequest req,
        @PathVariable UUID receiptId,
        @RequestBody ReceiptPieceGradeService.GradeRequest body){
        return service.grade(access.currentTenant(auth,req),
            access.authenticatedUser(auth),receiptId,body);
    }
    @PostMapping("/receipts/{receiptId}/finalize")
    public PurchaseQualityService.Inspection finalizeReceipt(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId,
        @RequestBody ReceiptPieceGradeService.FinalizeRequest body) {
        return service.finalizeReceipt(access.currentTenant(auth,req),
            access.authenticatedUser(auth),receiptId,body);
    }
}
