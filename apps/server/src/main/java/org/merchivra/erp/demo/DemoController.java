package org.merchivra.erp.demo;

import org.merchivra.erp.demo.DemoModels.Overview;
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

    @GetMapping("/overview")
    public Overview overview() {
        return gateway.getOverview();
    }
}
