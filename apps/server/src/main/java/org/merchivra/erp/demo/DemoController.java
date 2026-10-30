package org.merchivra.erp.demo;

import org.merchivra.erp.demo.DemoModels.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately isolated demo endpoint. Disabled by default; can only serve
 * invented data. Not an alternative to authentication for live seller data.
 */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(prefix = "merchivra.demo", name = "enabled", havingValue = "true")
public class DemoController {
    private final MarketplaceOverviewGateway gateway;

    public DemoController(MarketplaceOverviewGateway gateway) {
        this.gateway = gateway;
    }

    private void requireKnownAccount(String accountId) {
        if (gateway.getOverview().accounts().stream().noneMatch(a -> a.id().equals(accountId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo account not found");
        }
    }

    @GetMapping("/accounts")
    public List<Account> accounts() { return gateway.getOverview().accounts(); }

    @GetMapping("/orders")
    public List<Order> orders(@RequestParam String accountId) {
        requireKnownAccount(accountId);
        return gateway.getOverview().orders().stream().filter(o -> o.accountId().equals(accountId)).toList();
    }

    @GetMapping("/listings")
    public List<Listing> listings(@RequestParam String accountId) {
        requireKnownAccount(accountId);
        return gateway.getOverview().listings().stream().filter(l -> l.accountId().equals(accountId)).toList();
    }

    @GetMapping("/inventory/fba")
    public List<Inventory> inventory(@RequestParam String accountId) {
        requireKnownAccount(accountId);
        return gateway.getOverview().inventory().stream().filter(i -> i.accountId().equals(accountId)).toList();
    }

    @GetMapping("/overview")
    public Overview overview() {
        return gateway.getOverview();
    }
}
