package org.merchivra.erp.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped internal SKU master data and explicit human Listing mappings.
 * Does not edit Amazon listings or infer mappings from SKU name similarity.
 */
@Service
public class ProductCatalogService {
    private final JdbcTemplate jdbc;
    public ProductCatalogService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Sku(String id,String internalSku,String displayName,String status,String updatedAt) {}
    public record Mapping(String listingId,String productSkuId,int version) {}

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private static String name(String value) {
        if(value==null)throw bad("Display name required");
        String s=value.strip();
        if(s.length()<2 || s.length()>300 || s.chars().anyMatch(Character::isISOControl))
            throw bad("Display name must be 2..300 printable characters");
        return s;
    }
    private static String skuCode(String value) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}"))
            throw bad("Internal SKU must be 1..200 safe characters");
        return value;
    }
    private static Sku map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Sku(rs.getObject("id",UUID.class).toString(),
            rs.getString("internal_sku"),rs.getString("display_name"),rs.getString("status"),
            rs.getObject("updated_at",OffsetDateTime.class).toInstant().toString());
    }

    public List<Sku> list(UUID tenant,String prefix,String after,int limit) {
        if(limit<1 || limit>100)throw bad("limit must be 1..100");
        if(prefix!=null && (prefix.length()>200 || prefix.chars().anyMatch(Character::isISOControl)))
            throw bad("Invalid SKU search");
        if(after!=null)skuCode(after);
        return jdbc.query("""
            SELECT id,internal_sku,display_name,status,updated_at
            FROM product_sku
            WHERE tenant_id=?
              AND (? IS NULL OR internal_sku ILIKE ? || '%')
              AND (? IS NULL OR internal_sku>?)
            ORDER BY internal_sku ASC LIMIT ?
            """,ProductCatalogService::map,tenant,prefix,prefix,after,after,limit);
    }

    @Transactional
    public Sku create(UUID tenant,UUID actor,String code,String label) {
        code=skuCode(code);label=name(label);
        UUID id=UUID.randomUUID();
        int n=jdbc.update("""
            INSERT INTO product_sku(id,tenant_id,internal_sku,display_name,status)
            VALUES (?,?,?,?,'ACTIVE')
            ON CONFLICT (tenant_id,internal_sku) DO NOTHING
            """,id,tenant,code,label);
        if(n!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Internal SKU already exists");
        audit(tenant,actor,"SKU",id,"SKU_CREATED");
        return get(tenant,id);
    }

    public Sku get(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT id,internal_sku,display_name,status,updated_at
            FROM product_sku WHERE tenant_id=? AND id=?
            """,ProductCatalogService::map,tenant,id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Sku update(UUID tenant,UUID actor,UUID id,String label,String nextStatus) {
        if(nextStatus==null || !List.of("ACTIVE","ARCHIVED").contains(nextStatus))
            throw bad("Status must be ACTIVE or ARCHIVED");
        String display=name(label);
        Sku current=get(tenant,id);
        if(current.status().equals(nextStatus) && current.displayName().equals(display))
            return current;
        if("ARCHIVED".equals(nextStatus)) {
            Integer active=jdbc.queryForObject("""
                SELECT COUNT(*) FROM listing_sku_mapping m
                WHERE m.tenant_id=? AND m.product_sku_id=?
                  AND m.status='ACTIVE' AND m.valid_to IS NULL
                """,Integer.class,tenant,id);
            if(active!=null && active>0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Unmap active Listings before archiving SKU");
        }
        int n=jdbc.update("""
            UPDATE product_sku SET display_name=?,status=?,updated_at=now()
            WHERE tenant_id=? AND id=?
            """,display,nextStatus,tenant,id);
        if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String event=!current.status().equals(nextStatus)
            ? ("ARCHIVED".equals(nextStatus)?"SKU_ARCHIVED":"SKU_RESTORED")
            : "SKU_RENAMED";
        audit(tenant,actor,"SKU",id,event);
        return get(tenant,id);
    }

    @Transactional
    public Mapping mapListing(UUID tenant,UUID actor,UUID listing,UUID sku) {
        List<UUID> source=jdbc.query("""
            SELECT id FROM marketplace_listing WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,listing);
        if(source.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<UUID> target=jdbc.query("""
            SELECT id FROM product_sku WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,sku);
        if(target.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Mapping> current=jdbc.query("""
            SELECT listing_id,product_sku_id,mapping_version
            FROM listing_sku_mapping WHERE tenant_id=? AND listing_id=?
              AND status='ACTIVE' AND valid_to IS NULL FOR UPDATE
            """,(rs,n)->new Mapping(
                rs.getObject(1,UUID.class).toString(),
                rs.getObject(2,UUID.class).toString(),rs.getInt(3)),tenant,listing);
        if(!current.isEmpty() && current.getFirst().productSkuId().equals(sku.toString()))
            return current.getFirst();
        jdbc.update("""
            UPDATE listing_sku_mapping SET status='SUPERSEDED',valid_to=now()
            WHERE tenant_id=? AND listing_id=? AND status='ACTIVE' AND valid_to IS NULL
            """,tenant,listing);
        Integer version=jdbc.queryForObject("""
            SELECT COALESCE(MAX(mapping_version),0)+1 FROM listing_sku_mapping
            WHERE tenant_id=? AND listing_id=?
            """,Integer.class,tenant,listing);
        jdbc.update("""
            INSERT INTO listing_sku_mapping
              (id,tenant_id,listing_id,product_sku_id,status,valid_from,mapping_version)
            VALUES (?,?,?,?,'ACTIVE',now(),?)
            """,UUID.randomUUID(),tenant,listing,sku,version);
        jdbc.update("""
            UPDATE marketplace_listing SET mapping_status='ACTIVE'
            WHERE tenant_id=? AND id=?
            """,tenant,listing);
        audit(tenant,actor,"LISTING_MAPPING",listing,"LISTING_MAPPED");
        return new Mapping(listing.toString(),sku.toString(),version);
    }

    @Transactional
    public void unmapListing(UUID tenant,UUID actor,UUID listing) {
        List<UUID> source=jdbc.query("""
            SELECT id FROM marketplace_listing WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,listing);
        if(source.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        int changed=jdbc.update("""
            UPDATE listing_sku_mapping SET status='SUPERSEDED',valid_to=now()
            WHERE tenant_id=? AND listing_id=? AND status='ACTIVE' AND valid_to IS NULL
            """,tenant,listing);
        if(changed==0)return;
        jdbc.update("""
            UPDATE marketplace_listing SET mapping_status='UNMAPPED'
            WHERE tenant_id=? AND id=?
            """,tenant,listing);
        audit(tenant,actor,"LISTING_MAPPING",listing,"LISTING_UNMAPPED");
    }

    private void audit(UUID tenant,UUID actor,String type,UUID id,String event) {
        jdbc.update("""
            INSERT INTO catalog_change_audit
              (id,tenant_id,actor_user_id,resource_type,resource_id,event_type)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,actor,type,id,event);
    }
}
