package org.merchivra.erp.purchasing;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LocalKeysetPageTest {
    @Test void traversesAllRowsWithoutTreatingFirstHundredAsTotal() {
        var all=new ArrayList<Integer>();
        for(int i=1;i<=225;i++)all.add(i);
        var a=LocalKeysetPage.pack(all.subList(0,51),50,
            n->LocalKeysetPage.encode("warehouse",""+n));
        var b=LocalKeysetPage.pack(all.subList(50,101),50,
            n->LocalKeysetPage.encode("warehouse",""+n));
        var c=LocalKeysetPage.pack(all.subList(100,151),50,
            n->LocalKeysetPage.encode("warehouse",""+n));
        var d=LocalKeysetPage.pack(all.subList(150,201),50,
            n->LocalKeysetPage.encode("warehouse",""+n));
        var e=LocalKeysetPage.pack(all.subList(200),50,
            n->LocalKeysetPage.encode("warehouse",""+n));
        assertEquals(List.of(50,50,50,50,25),
            List.of(a.items().size(),b.items().size(),c.items().size(),
                d.items().size(),e.items().size()));
        assertTrue(a.hasMore());
        assertEquals("50",LocalKeysetPage.decode(a.nextCursor(),2)[1]);
        assertNull(e.nextCursor());
        assertFalse(e.hasMore());
        assertEquals(225,e.items().getLast());
    }
    @Test void refusesUnknownOrOversizedTokensAndPages() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LocalKeysetPage.limit(101)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LocalKeysetPage.limit(0)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LocalKeysetPage.decode("invalid*",2)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LocalKeysetPage.decode(LocalKeysetPage.encode("only-one"),2))
            .getStatusCode());
        assertNull(LocalKeysetPage.decode(null,3));
    }
}
