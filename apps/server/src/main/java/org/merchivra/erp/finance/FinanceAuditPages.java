package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

/**
 * V98: shared read-only financial audit keyset, never a ledger operation.
 *
 * The SQL projection/where is a SERVICE-OWNED constant (never a request value).
 * Domain services must check report/event evidence and tenant Marketplace scope
 * before calling this helper, to prevent cross-tenant existence probing.
 */
final class FinanceAuditPages {
    private FinanceAuditPages() {}
    private record Entry<T>(T item,String time,String id) {}

    static <T> LocalKeysetPage.Page<T> read(
        JdbcTemplate jdbc,UUID tenant,UUID marketplace,UUID target,String type,
        int requested,String rawCursor,String query,RowMapper<T> mapper) {
        int limit=LocalKeysetPage.limit(requested);
        String[] cursor=LocalKeysetPage.decode(rawCursor,6);
        var args=new ArrayList<Object>();
        args.add(tenant);
        args.add(marketplace);
        args.add(target);
        String seek="";
        if(cursor!=null) {
            if(!tenant.toString().equals(cursor[0]) ||
                !marketplace.toString().equals(cursor[1]) ||
                !target.toString().equals(cursor[2]) ||
                !type.equals(cursor[3]))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Audit cursor scope mismatch");
            try {
                seek=" AND (a.occurred_at,a.id)<(?,?)";
                args.add(Instant.parse(cursor[4]).atOffset(ZoneOffset.UTC));
                args.add(UUID.fromString(cursor[5]));
            } catch(IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid audit cursor timestamp or identifier");
            }
        }
        args.add(limit+1);
        List<Entry<T>> rows=jdbc.query(query+seek+
            " ORDER BY a.occurred_at DESC,a.id DESC LIMIT ?",
            (rs,i)->new Entry<>(mapper.mapRow(rs,i),
                rs.getObject("occurred_at",OffsetDateTime.class).toInstant().toString(),
                rs.getObject("id",UUID.class).toString()),args.toArray());
        var page=LocalKeysetPage.pack(rows,limit,e->LocalKeysetPage.encode(
            tenant.toString(),marketplace.toString(),target.toString(),type,
            e.time(),e.id()));
        return new LocalKeysetPage.Page<>(
            page.items().stream().map(Entry::item).toList(),
            page.nextCursor(),page.hasMore(),page.pageSize());
    }
}
