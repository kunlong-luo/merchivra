package org.merchivra.erp.catalogsync;

import com.amazon.SellingPartnerAPIAA.LWAException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.merchivra.erp.spapi.SpApiHttpException;
import org.merchivra.erp.spapi.SpApiOfficialModules;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiSellerScope;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.models.fba.inventory.v1.GetInventorySummariesResponse;
import software.amazon.spapi.models.listings.items.v2021_08_01.ItemSearchResults;

/** Typed minimal non-PII read boundaries. No Listings write methods are reachable. */
public final class TypedCatalogSdkReader {
    public record ListingFact(String sellerSku,String asin,String fnsku,Instant updatedAt) {}
    public record StockFact(String sellerSku,Integer fulfillable,Integer reserved,
                            Instant updatedAt,String quality) {}
    public record Page(List<ListingFact> listings,List<StockFact> stocks,String nextToken) {}

    @FunctionalInterface interface ListingsCall {
        ItemSearchResults call(SpApiSellerScope scope,String cursor) throws Exception;
    }
    @FunctionalInterface interface StockCall {
        GetInventorySummariesResponse call(SpApiSellerScope scope,String cursor) throws Exception;
    }
    private final ListingsCall listings;
    private final StockCall inventory;
    public TypedCatalogSdkReader(SpApiOfficialSdkFactory factory) {
        Objects.requireNonNull(factory);
        var modules=new SpApiOfficialModules(factory);
        this.listings=(scope,cursor)->modules.listings(scope).searchListingsItems(
            scope.sellerId(),List.of(scope.marketplaceId()),null,List.of("summaries"),
            null,null,null,null,null,null,null,null,null,null,null,
            null,null,20,cursor);
        this.inventory=(scope,cursor)->modules.fbaInventory(scope).getInventorySummaries(
            "Marketplace",scope.marketplaceId(),List.of(scope.marketplaceId()),
            true,null,null,null,cursor);
    }
    TypedCatalogSdkReader(ListingsCall listings,StockCall inventory) {
        this.listings=Objects.requireNonNull(listings);
        this.inventory=Objects.requireNonNull(inventory);
    }
    private static void eligible(SpApiSellerScope scope,String cursor) {
        if(scope==null || !scope.approvedRoles().contains("Inventory and Order Tracking"))
            throw new SecurityException("Independently verified Inventory role required");
        if(cursor!=null && (cursor.isBlank() || cursor.length()>4096))
            throw new IllegalArgumentException("Invalid pagination cursor");
    }
    private static String next(String cursor) {
        if(cursor!=null && (cursor.isBlank() || cursor.length()>4096))
            throw new IllegalArgumentException("Unsafe pagination cursor");
        return cursor;
    }
    private static String sku(String value) {
        if(value==null || value.isBlank() || value.length()>200
          || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Unsafe seller SKU");
        return value;
    }
    private static void positive(Integer n) {
        if(n!=null && n<0)throw new IllegalArgumentException("Negative FBA quantity");
    }
    public Page fetchListings(SpApiSellerScope scope,String cursor) throws Exception {
        eligible(scope,cursor);
        ItemSearchResults response;
        try { response=listings.call(scope,cursor); }
        catch(ApiException.RateLimitExceeded e) {
            throw new SpApiHttpException("searchListingsItems",429,"");
        } catch(ApiException e) {
            throw new SpApiHttpException("searchListingsItems",e.getCode(),"");
        } catch(LWAException e) { throw new SecurityException("LWA authorization rejected"); }
        if(response==null || response.getItems()==null || response.getItems().size()>100)
            throw new IllegalArgumentException("Invalid Listing result page");
        var facts=new ArrayList<ListingFact>();
        Set<String> seen=new HashSet<>();
        for(var item:response.getItems()) {
            if(item==null)throw new IllegalArgumentException("Invalid listing");
            String sellerSku=sku(item.getSku());
            if(!seen.add(sellerSku))throw new IllegalArgumentException("Duplicate seller SKU");
            String asin=null,fnsku=null;Instant updated=null;
            if(item.getSummaries()!=null) {
                for(var summary:item.getSummaries()) {
                    if(summary==null || !scope.marketplaceId().equals(summary.getMarketplaceId()))
                        throw new IllegalArgumentException("Listing marketplace scope mismatch");
                    if(summary.getAsin()!=null) {
                        if(summary.getAsin().length()>32)throw new IllegalArgumentException("ASIN too long");
                        asin=summary.getAsin();
                    }
                    if(summary.getFnSku()!=null) {
                        if(summary.getFnSku().length()>64)throw new IllegalArgumentException("FNSKU too long");
                        fnsku=summary.getFnSku();
                    }
                    if(summary.getLastUpdatedDate()!=null)
                        updated=summary.getLastUpdatedDate().toInstant();
                }
            }
            facts.add(new ListingFact(sellerSku,asin,fnsku,updated));
        }
        return new Page(List.copyOf(facts),List.of(),
            next(response.getPagination()==null?null:response.getPagination().getNextToken()));
    }
    public Page fetchInventory(SpApiSellerScope scope,String cursor) throws Exception {
        eligible(scope,cursor);
        GetInventorySummariesResponse response;
        try { response=inventory.call(scope,cursor); }
        catch(ApiException.RateLimitExceeded e) {
            throw new SpApiHttpException("getInventorySummaries",429,"");
        } catch(ApiException e) {
            throw new SpApiHttpException("getInventorySummaries",e.getCode(),"");
        } catch(LWAException e) { throw new SecurityException("LWA authorization rejected"); }
        if(response==null || response.getPayload()==null
           || response.getPayload().getInventorySummaries()==null
           || response.getPayload().getInventorySummaries().size()>10000)
            throw new IllegalArgumentException("Invalid FBA inventory page");
        var facts=new ArrayList<StockFact>();
        Set<String> seen=new HashSet<>();
        for(var row:response.getPayload().getInventorySummaries()) {
            if(row==null)throw new IllegalArgumentException("Invalid FBA inventory item");
            String sellerSku=sku(row.getSellerSku());
            if(!seen.add(sellerSku))throw new IllegalArgumentException("Duplicate inventory SKU");
            var details=row.getInventoryDetails();
            Integer available=details==null?null:details.getFulfillableQuantity();
            Integer reserved=details==null||details.getReservedQuantity()==null
                ?null:details.getReservedQuantity().getTotalReservedQuantity();
            positive(available);positive(reserved);
            Instant observed=row.getLastUpdatedTime()==null?null:row.getLastUpdatedTime().toInstant();
            String quality=available==null || reserved==null || observed==null?"PARTIAL":"OBSERVED";
            facts.add(new StockFact(sellerSku,available,reserved,observed,quality));
        }
        return new Page(List.of(),List.copyOf(facts),
            next(response.getPagination()==null?null:response.getPagination().getNextToken()));
    }
}
