package org.merchivra.erp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** OWNER-only local team management; all writes require Session CSRF. */
@RestController
@RequestMapping("/api/v1/team")
@ConditionalOnProperty(prefix="merchivra.team",name="enabled",havingValue="true")
public class LocalTeamController {
    private final TenantAccessService access;
    private final LocalTeamService team;
    public LocalTeamController(TenantAccessService access,LocalTeamService team) {
        this.access=access;this.team=team;
    }
    public record InviteRequest(String loginName) {}
    public record ReviewerChange(boolean enabled) {}
    @GetMapping("/members")
    public ResponseEntity<List<LocalTeamService.Member>> members(
        Authentication auth,HttpServletRequest request) {
        UUID tenant=access.currentTenant(auth,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(team.members(tenant,access.authenticatedUser(auth)));
    }
    @GetMapping("/invitations")
    public ResponseEntity<List<LocalTeamService.Invitation>> invitations(
        Authentication auth,HttpServletRequest request) {
        UUID tenant=access.currentTenant(auth,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(team.invitations(tenant,access.authenticatedUser(auth)));
    }
    @PostMapping("/invitations")
    public ResponseEntity<LocalTeamService.Issued> invite(
        Authentication auth,HttpServletRequest request,@RequestBody InviteRequest body) {
        UUID tenant=access.currentTenant(auth,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(team.invite(tenant,access.authenticatedUser(auth),body.loginName()));
    }
    @PostMapping("/invitations/{id}/revoke")
    public ResponseEntity<Void> revokeInvite(
        Authentication auth,HttpServletRequest request,@PathVariable UUID id) {
        team.revokeInvitation(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/members/{id}/reviewer")
    public ResponseEntity<Void> reviewer(Authentication auth,HttpServletRequest request,
        @PathVariable UUID id,@RequestBody ReviewerChange body) {
        team.setReviewer(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body.enabled());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/members/{id}/revoke")
    public ResponseEntity<Void> revokeOwner(Authentication auth,HttpServletRequest request,
        @PathVariable UUID id) {
        team.revokeOwner(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
