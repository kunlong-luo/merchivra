package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Human owner operations on virtual local receiving-stage bins only. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-locations")
public class ReceiptLotLocationController {
    private final ReceiptLotLocationService service;
    private final TenantAccessService access;
    public ReceiptLotLocationController(ReceiptLotLocationService service,
                                       TenantAccessService access) {
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}/bins")
    public List<ReceiptLotLocationService.Bin> bins(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId){
        return service.bins(access.currentTenant(auth,req),warehouseId);
    }
    @GetMapping("/warehouses/{warehouseId}/bins/page")
    public ResponseEntity<LocalKeysetPage.Page<ReceiptLotLocationService.Bin>> binsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.binsPage(access.currentTenant(auth,req),warehouseId,limit,cursor));
    }
    @PostMapping("/warehouses/{warehouseId}/bins")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptLotLocationService.Bin createBin(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestBody ReceiptLotLocationService.NewBin body) {
        return service.createBin(access.currentTenant(auth,req),
            access.authenticatedUser(auth),warehouseId,body);
    }
    @GetMapping("/receipts/{receiptId}/pieces")
    public List<ReceiptLotLocationService.Piece> pieces(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId){
        return service.pieces(access.currentTenant(auth,req),receiptId);
    }
    @GetMapping("/receipts/{receiptId}/pieces/page")
    public ResponseEntity<ReceiptLotLocationService.PiecePage> piecesPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID receiptId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.piecesPage(access.currentTenant(auth,req),receiptId,limit,cursor));
    }
    @PostMapping("/receipts/{receiptId}/split")
    public List<ReceiptLotLocationService.Piece> split(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId,
        @RequestBody ReceiptLotLocationService.Split body) {
        return service.split(access.currentTenant(auth,req),
            access.authenticatedUser(auth),receiptId,body);
    }
    @PostMapping("/receipts/{receiptId}/move")
    public List<ReceiptLotLocationService.Piece> move(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId,
        @RequestBody ReceiptLotLocationService.Move body){
        return service.move(access.currentTenant(auth,req),
            access.authenticatedUser(auth),receiptId,body);
    }
    @GetMapping("/warehouses/{warehouseId}/pressure/page")
    public ResponseEntity<LocalKeysetPage.Page<ReceiptLotLocationService.Pressure>> pressurePage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.pressurePage(access.currentTenant(auth,req),warehouseId,limit,cursor));
    }
    @GetMapping("/warehouses/{warehouseId}/pressure")
    public List<ReceiptLotLocationService.Pressure> pressure(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId){
        return service.pressure(access.currentTenant(auth,req),warehouseId);
    }
}
