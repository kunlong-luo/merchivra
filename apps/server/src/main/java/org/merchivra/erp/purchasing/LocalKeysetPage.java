package org.merchivra.erp.purchasing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Read-only cursor pages for tenant-scoped local purchasing data, never a ledger snapshot. */
final class LocalKeysetPage {
    private LocalKeysetPage() {}
    record Page<T>(List<T> items,String nextCursor,boolean hasMore,int pageSize) {}

    static int limit(int limit) {
        if(limit<1 || limit>100)throw bad("Page limit must be 1..100");
        return limit;
    }
    static String encode(String... parts) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            String.join("\u001f",parts).getBytes(StandardCharsets.UTF_8));
    }
    static String[] decode(String cursor,int expected) {
        if(cursor==null || cursor.isBlank())return null;
        if(cursor.length()>2048 || !cursor.matches("[A-Za-z0-9_-]+"))
            throw bad("Invalid pagination cursor");
        try {
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8).split("\u001f",-1);
            if(parts.length!=expected)throw bad("Invalid pagination cursor");
            for(String part:parts)if(part.isBlank() || part.length()>256)
                throw bad("Invalid pagination cursor");
            return parts;
        } catch(IllegalArgumentException e) {
            throw bad("Invalid pagination cursor");
        }
    }
    static <T> Page<T> pack(List<T> fetched,int limit,Function<T,String> cursor) {
        boolean more=fetched.size()>limit;
        List<T> items=List.copyOf(fetched.subList(0,Math.min(limit,fetched.size())));
        return new Page<>(items,more?cursor.apply(items.getLast()):null,more,limit);
    }
    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
