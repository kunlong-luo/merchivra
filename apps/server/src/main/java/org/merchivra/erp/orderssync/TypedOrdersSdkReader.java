package org.merchivra.erp.orderssync;

import com.amazon.SellingPartnerAPIAA.LWAException;
import java.time.Instant;
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
 * Only stable minimal order facts cross this boundary; buyer, recipient,
 * proceeds and payment information are never requested or persisted.
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
                cursor, null);
    }

    TypedOrdersSdkReader(ReadCall caller) { this.caller=Objects.requireNonNull(caller); }

    public SpApiPayloadDecoder.OrderPage search(SpApiSellerScope seller,
        Instant updatedAfter, Instant updatedBefore, String cursor) throws Exception {
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
            // Remote body can contain credentials or personal information.
            throw new SpApiHttpException("searchOrders",remote.getCode(),"");
        } catch(LWAException denied) {
            throw new SecurityException("LWA authorization rejected");
        }

        if(response==null || response.getOrders()==null || response.getOrders().size()>100)
            throw new IllegalArgumentException("Invalid typed Orders page");
        var records=new ArrayList<SpApiPayloadDecoder.OrderSummary>();
        for(var order:response.getOrders()) {
            if(order==null || order.getOrderId()==null || order.getOrderId().isBlank()
                || order.getOrderId().length()>160
                || order.getLastUpdatedTime()==null
                || order.getSalesChannel()==null
                || !seller.marketplaceId().equals(order.getSalesChannel().getMarketplaceId()))
                throw new IllegalArgumentException("Typed Orders record scope mismatch");
            var fulfillment=order.getFulfillment();
            String status=fulfillment==null || fulfillment.getFulfillmentStatus()==null
                ? "NOT_OBSERVED" : fulfillment.getFulfillmentStatus().getValue();
            records.add(new SpApiPayloadDecoder.OrderSummary(
                order.getOrderId(),seller.marketplaceId(),
                order.getLastUpdatedTime().toInstant(),status));
        }
        String next=response.getPagination()==null?null:response.getPagination().getNextToken();
        if(next!=null && (next.isBlank() || next.length()>4096))
            throw new IllegalArgumentException("Invalid typed pagination token");
        return new SpApiPayloadDecoder.OrderPage(List.copyOf(records),next);
    }
}
