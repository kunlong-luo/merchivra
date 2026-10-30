package org.merchivra.erp.demo;

import org.merchivra.erp.demo.DemoModels.Overview;

/**
 * Integration port: real Seller API adapters must be implemented separately and
 * only after authentication, authorization, and tenant data isolation exist.
 */
public interface MarketplaceOverviewGateway {
    Overview getOverview();
}
