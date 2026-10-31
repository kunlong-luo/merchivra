package org.merchivra.erp.spapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit Owner Session + CSRF dual-person role review.
 * Feature is disabled by default and cannot obtain roles from Amazon.
 * Approval requires a distinct SECURITY_REVIEWER already provisioned
 * through the trusted server-side RBAC channel.
 */
@RestController
@RequestMapping("/api/v1/seller-accounts")
@ConditionalOnProperty(prefix="merchivra.spapi.orders-role-review",
    name="enabled",havingValue="true")
public class OrdersRoleReviewController {
    private final TenantAccessService access;
    private final OrdersRoleReviewService reviews;

    public OrdersRoleReviewController(TenantAccessService access,
                                      OrdersRoleReviewService reviews) {
        this.access=access;this.reviews=reviews;
    }
    public record RequestRole(String evidenceSha256,String externalCaseRef) {}
    public record CreatedReview(UUID id,String state) {}
    public record ReviewDecision(String state) {}

    @GetMapping("/{accountId}/orders-role-review")
    public ResponseEntity<List<OrdersRoleReviewService.ReviewStatus>> history(
        Authentication auth,HttpServletRequest req,@PathVariable UUID accountId) {
        UUID tenant=access.currentTenant(auth,req);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.history(tenant,accountId));
    }

    @PostMapping("/{accountId}/orders-role-review")
    public ResponseEntity<CreatedReview> request(
        Authentication auth,HttpServletRequest req,@PathVariable UUID accountId,
        @RequestBody RequestRole body) {
        UUID tenant=access.currentTenant(auth,req);
        UUID actor=access.authenticatedUser(auth);
        UUID review=reviews.request(tenant,actor,accountId,
            body.evidenceSha256(),body.externalCaseRef());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new CreatedReview(review,"PENDING"));
    }

    @PostMapping("/orders-role-review/{reviewId}/approve")
    public ResponseEntity<ReviewDecision> approve(
        Authentication auth,HttpServletRequest req,@PathVariable UUID reviewId) {
        return decide(auth,req,reviewId,true);
    }
    @PostMapping("/orders-role-review/{reviewId}/reject")
    public ResponseEntity<ReviewDecision> reject(
        Authentication auth,HttpServletRequest req,@PathVariable UUID reviewId) {
        return decide(auth,req,reviewId,false);
    }
    private ResponseEntity<ReviewDecision> decide(Authentication auth,
        HttpServletRequest req,UUID reviewId,boolean approve) {
        UUID tenant=access.currentTenant(auth,req);
        UUID actor=access.authenticatedUser(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ReviewDecision(reviews.decide(tenant,actor,reviewId,approve)));
    }

    @PostMapping("/{accountId}/orders-role-review/revoke")
    public ResponseEntity<Void> revoke(Authentication auth,HttpServletRequest req,
                                       @PathVariable UUID accountId) {
        reviews.revoke(access.currentTenant(auth,req),
            access.authenticatedUser(auth),accountId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
