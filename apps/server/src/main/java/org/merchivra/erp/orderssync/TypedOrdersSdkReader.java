package org.merchivra.erp.orderssync;

import com.amazon.SellingPartnerAPIAA.LWAException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.merchivra.erp.spapi.SpApiHttpException;
import org.merchivra.erp.spapi.SpApiOfficialModules;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiPayloadDecoder;
import org.merchivra.erp.spapi.SpApiSellerScope;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.models.orders.v2026_01_01.SearchOrdersResponse;

/**
 * Typed Orders v2026-01-01 read using Amazon's official Java SDK.
 * Only minimal non-PII order facts cross this boundary. Selective PROCEEDS
 * gives ITEM subtotal, not settlement; no BUYER/RECIPIENT/PAYMENT dataset is requested.
 */
public final class TypedOrdersSdkReader {
    @FunctionalInterface
    interface ReadCall {
        SearchOrdersResponse search(SpApiSellerScope seller, OffsetDateTime after,
                                    OffsetDateTime before, String cursor) throws Exception;
    }

    private final ReadCall caller;

    public TypedOrdersSdkReader(SpApiOfficialSdkFactory factory) {
        Objects.requireNonNull(factory);
        SpApiOfficialModules modules=new SpApiOfficialModules(factory);
        this.caller=(seller,after,before,cursor) ->
            modules.searchOrders(seller).searchOrders(
                null, null, after, before, null,
                List.of(seller.marketplaceId()), null, 100,
                cursor, List.of("PROCEEDS"));
    }

    TypedOrdersSdkReader(ReadCall caller) { this.caller=Objects.requireNonNull(caller); }

    public record DetailedOrder(String orderId,String marketplaceId,Instant updatedAt,
        String fulfillment,List<OrdersSyncSource.ItemFact> items,boolean itemsObserved,
        Instant createdAt) {}
    public record DetailedPage(List<DetailedOrder> orders,String nextToken) {}

    /** Compatibility for existing summary-only consumers. */
    public SpApiPayloadDecoder.OrderPage search(SpApiSellerScope seller,
        Instant updatedAfter,Instant updatedBefore,String cursor) throws Exception {
        DetailedPage page=searchDetailed(seller,updatedAfter,updatedBefore,cursor);
        return new SpApiPayloadDecoder.OrderPage(page.orders().stream()
            .map(o->new SpApiPayloadDecoder.OrderSummary(o.orderId(),o.marketplaceId(),
                 o.updatedAt(),o.fulfillment())).toList(),page.nextToken());
    }

    /**
     * Orders 2026 searchOrders returns orderItems in its base dataset.
     * PROCEEDS is explicitly selected but BUYER/RECIPIENT/PAYMENT are never
     * requested or copied into stored ERP facts.
     */
    public DetailedPage searchDetailed(SpApiSellerScope seller,
        Instant updatedAfter,Instant updatedBefore,String cursor) throws Exception {
        if(seller==null || !seller.approvedRoles().contains("Inventory and Order Tracking"))
            throw new SecurityException("Independently reviewed Orders role required");
        if(updatedAfter==null || updatedBefore==null || !updatedAfter.isBefore(updatedBefore))
            throw new IllegalArgumentException("Updated time window required");
        if(cursor!=null && (cursor.isBlank() || cursor.length()>4096))
            throw new IllegalArgumentException("Invalid pagination token");

        SearchOrdersResponse response;
        try {
            response=caller.search(seller,
                OffsetDateTime.ofInstant(updatedAfter,ZoneOffset.UTC),
                OffsetDateTime.ofInstant(updatedBefore,ZoneOffset.UTC),cursor);
        } catch(ApiException.RateLimitExceeded rate) {
            throw new SpApiHttpException("searchOrders",429,"");
        } catch(ApiException remote) {
            throw new SpApiHttpException("searchOrders",remote.getCode(),"");
        } catch(LWAException denied) {
            throw new SecurityException("LWA authorization rejected");
        }

        if(response==null || response.getOrders()==null || response.getOrders().size()>100)
            throw new IllegalArgumentException("Invalid typed Orders page");
        var records=new ArrayList<DetailedOrder>();
        for(var order:response.getOrders()) {
            if(order==null || order.getOrderId()==null || order.getOrderId().isBlank()
                || order.getOrderId().length()>160 || order.getLastUpdatedTime()==null
                || order.getSalesChannel()==null
                || !seller.marketplaceId().equals(order.getSalesChannel().getMarketplaceId()))
                throw new IllegalArgumentException("Typed Orders record scope mismatch");
            var fulfillment=order.getFulfillment();
            String status=fulfillment==null || fulfillment.getFulfillmentStatus()==null
                ? "NOT_OBSERVED" : fulfillment.getFulfillmentStatus().getValue();
            boolean observed=order.getOrderItems()!=null;
            var items=new ArrayList<OrdersSyncSource.ItemFact>();
            if(observed) {
                if(order.getOrderItems().size()>1000)
                    throw new IllegalArgumentException("Orders item count exceeds safe limit");
                Set<String> seen=new HashSet<>();
                for(var item:order.getOrderItems()) {
                    if(item==null || item.getOrderItemId()==null
                       || !item.getOrderItemId().matches("[A-Za-z0-9._:/-]{1,160}")
                       || !seen.add(item.getOrderItemId()))
                        throw new IllegalArgumentException("Invalid or duplicated Order item ID");
                    Integer quantity=item.getQuantityOrdered();
                    if(quantity!=null && quantity<0)
                        throw new IllegalArgumentException("Negative order quantity");
                    String sku=item.getProduct()==null?null:item.getProduct().getSellerSku();
                    if(sku!=null && (sku.isBlank() || sku.length()>200
                        || sku.chars().anyMatch(Character::isISOControl)))
                        throw new IllegalArgumentException("Invalid Seller SKU in Order item");
                    String subtotal=null;
                    String currency=null;
                    if(item.getProceeds()!=null && item.getProceeds().getBreakdowns()!=null) {
                        for(var breakdown:item.getProceeds().getBreakdowns()) {
                            if(breakdown==null)throw new IllegalArgumentException("Invalid proceeds");
                            if(!"ITEM".equals(breakdown.getType()))continue;
                            if(subtotal!=null)
                                throw new IllegalArgumentException("Ambiguous ITEM proceeds");
                            var money=breakdown.getSubtotal();
                            if(money==null || money.getAmount()==null ||
                               money.getCurrencyCode()==null)
                                throw new IllegalArgumentException("Incomplete item proceeds");
                            BigDecimal parsed;
                            try { parsed=new BigDecimal(money.getAmount()); }
                            catch(NumberFormatException invalid) {
                                throw new IllegalArgumentException("Malformed item proceeds");
                            }
                            if(parsed.precision()>20 || parsed.scale()>6 ||
                               !money.getCurrencyCode().matches("[A-Z]{3}"))
                                throw new IllegalArgumentException("Unsafe item proceeds");
                            subtotal=parsed.toPlainString();
                            currency=money.getCurrencyCode();
                        }
                    }
                    items.add(new OrdersSyncSource.ItemFact(
                        item.getOrderItemId(),sku,quantity,subtotal,currency));
                }
            }
            records.add(new DetailedOrder(order.getOrderId(),seller.marketplaceId(),
                order.getLastUpdatedTime().toInstant(),status,List.copyOf(items),observed,
                order.getCreatedTime()==null?null:order.getCreatedTime().toInstant()));
        }
        String next=response.getPagination()==null?null:response.getPagination().getNextToken();
        if(next!=null && (next.isBlank() || next.length()>4096))
            throw new IllegalArgumentException("Invalid typed pagination token");
        return new DetailedPage(List.copyOf(records),next);
    }
}
