package org.merchivra.erp.demo;

import java.math.BigDecimal;
import java.util.List;
import org.merchivra.erp.demo.DemoModels.*;
import org.springframework.stereotype.Component;

@Component
public final class MockMarketplaceOverviewGateway implements MarketplaceOverviewGateway {
    public static final String US_ACCOUNT = "11111111-1111-4111-8111-111111111111";
    public static final String EU_ACCOUNT = "22222222-2222-4222-8222-222222222222";

    private static final List<Account> ACCOUNTS = List.of(
        new Account(US_ACCOUNT, "North America · Demo", "NA", "DEMO-US", "USD", "MOCK_ONLY"),
        new Account(EU_ACCOUNT, "Europe · Demo", "EU", "DEMO-DE", "EUR", "MOCK_ONLY")
    );

    private static final List<Listing> LISTINGS = List.of(
        new Listing("lst-001", US_ACCOUNT, "CUP-BLUE-US", "ASIN-DEMO-01", "MV-CUP-BLUE", "ACTIVE"),
        new Listing("lst-002", US_ACCOUNT, "TOTE-UNMAPPED", "ASIN-DEMO-02", null, "UNMAPPED"),
        new Listing("lst-003", EU_ACCOUNT, "CUP-BLUE-EU", "ASIN-DEMO-01", "MV-CUP-BLUE", "ACTIVE")
    );

    private static final List<Order> ORDERS = List.of(
        new Order("ord-001", US_ACCOUNT, "MOCK-US-001", "CUP-BLUE-US",
            "SHIPPED", "PARTIAL", "NOT_OBSERVED",
            new Money("38.00", "USD"), new Money("8.00", "USD"), "2026-10-28T10:00:00Z"),
        new Order("ord-002", US_ACCOUNT, "MOCK-US-002", "TOTE-UNMAPPED",
            "UNSHIPPED", "NONE", "NOT_OBSERVED",
            new Money("25.00", "USD"), new Money("0.00", "USD"), "2026-10-28T12:00:00Z"),
        new Order("ord-003", EU_ACCOUNT, "MOCK-EU-001", "CUP-BLUE-EU",
            "SHIPPED", "NONE", "NOT_OBSERVED",
            new Money("20.00", "EUR"), new Money("0.00", "EUR"), "2026-10-27T09:00:00Z")
    );

    private static final List<Inventory> INVENTORY = List.of(
        new Inventory("inv-001", US_ACCOUNT, "CUP-BLUE-US", 12, 2, "OBSERVED", "2026-10-28T10:00:00Z"),
        new Inventory("inv-002", US_ACCOUNT, "TOTE-UNMAPPED", null, null, "UNKNOWN", null),
        new Inventory("inv-003", EU_ACCOUNT, "CUP-BLUE-EU", 0, 0, "OBSERVED", "2026-10-27T09:00:00Z")
    );

    @Override
    public Overview getOverview() {
        var summaries = ACCOUNTS.stream().map(this::summarize).toList();
        return new Overview("MOCK",
            "全部数据为纯虚构演示；无 Amazon 授权、财务入账或生产库存。订单收入不是结算收入。",
            "2026-10-28T12:00:00Z", ACCOUNTS, LISTINGS, ORDERS, INVENTORY, summaries);
    }

    private AccountSummary summarize(Account account) {
        var orders = ORDERS.stream().filter(o -> o.accountId().equals(account.id())).toList();
        var listings = LISTINGS.stream().filter(l -> l.accountId().equals(account.id())).toList();
        var inventory = INVENTORY.stream().filter(i -> i.accountId().equals(account.id())).toList();

        var sales = BigDecimal.ZERO;
        var refunds = BigDecimal.ZERO;
        for (var order : orders) {
            if (!order.provisionalSales().currency().equals(account.currency())
                || !order.refundAmount().currency().equals(account.currency())) {
                throw new IllegalStateException("Cross-currency mock aggregation is forbidden");
            }
            sales = sales.add(new BigDecimal(order.provisionalSales().amount()));
            refunds = refunds.add(new BigDecimal(order.refundAmount().amount()));
        }
        var unmapped = (int) listings.stream()
            .filter(l -> "UNMAPPED".equals(l.mappingStatus())).count();
        var unknown = (int) inventory.stream()
            .filter(i -> i.fulfillable() == null).count();

        return new AccountSummary(account.id(), account.currency(), orders.size(),
            new Money(sales.setScale(2).toPlainString(), account.currency()),
            new Money(refunds.setScale(2).toPlainString(), account.currency()),
            listings.size(), unmapped, unknown, "NOT_OBSERVED");
    }
}
