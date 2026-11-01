package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only Session tenant; read-only and no route for arbitrary tenant IDs. */
@RestController
@RequestMapping("/api/v1/operations/sync-tasks")
public class SyncTaskMonitorController {
    private final TenantAccessService access;
    private final SyncTaskMonitorService monitor;

    public SyncTaskMonitorController(TenantAccessService access,
        SyncTaskMonitorService monitor) {
        this.access=access; this.monitor=monitor;
    }

    @GetMapping
    public ResponseEntity<SyncTaskMonitorService.Snapshot> snapshot(
        Authentication auth, HttpServletRequest request,
        @RequestParam(defaultValue="ALL") String kind,
        @RequestParam(defaultValue="ALL") String state,
        @RequestParam(defaultValue="50") int limit) {
        var tenant=access.currentTenant(auth,request);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(monitor.snapshot(tenant,kind,state,limit));
        } catch (IllegalArgumentException invalid) {
            // Do not expose SQL, internal details or arbitrary values in errors.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported sync task filter or limit");
        }
    }
}
