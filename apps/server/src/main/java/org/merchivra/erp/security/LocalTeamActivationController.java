package org.merchivra.erp.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** One-time activation is anonymous but subject to Session CSRF. */
@RestController
@RequestMapping("/api/v1/auth/invitations")
@ConditionalOnProperty(prefix="merchivra.team",name="enabled",havingValue="true")
public class LocalTeamActivationController {
    private final LocalTeamService team;
    public LocalTeamActivationController(LocalTeamService team) { this.team=team; }
    public record Activation(String token,String password) {}
    @PostMapping("/accept")
    public ResponseEntity<Void> accept(@RequestBody Activation body) {
        team.accept(body.token(),body.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
