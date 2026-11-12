package org.merchivra.erp.purchasing;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/** V137: actual local TCP protocol framing, never a real production clamd. */
class LotProofAvScannerTest {
    private static final byte[] DATA=new byte[77000];
    static {
        Arrays.fill(DATA,(byte)65);
        DATA[0]='%';DATA[1]='P';DATA[2]='D';DATA[3]='F';DATA[4]='-';
    }

    private static void fakeClamd(String verdict, HttpStatus denial) throws Exception {
        fakeClamd(verdict,denial,true);
    }
    private static void fakeClamd(String verdict,HttpStatus denial,boolean framed)
        throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,
            InetAddress.getByName("127.0.0.1"))) {
            var received=new AtomicReference<byte[]>();
            var failed=new AtomicReference<Throwable>();
            Thread daemon=Thread.startVirtualThread(()->{
                try(var client=server.accept()){
                    var in=new DataInputStream(client.getInputStream());
                    assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII),
                        in.readNBytes(10));
                    var body=new ByteArrayOutputStream();
                    int chunks=0;
                    while(true){
                        int length=in.readInt();
                        if(length==0)break;
                        assertTrue(length>0 && length<=32768);
                        byte[] next=in.readNBytes(length);
                        assertEquals(length,next.length);
                        body.write(next);
                        chunks++;
                    }
                    assertTrue(chunks>=3);
                    received.set(body.toByteArray());
                    client.getOutputStream().write(
                        (framed?verdict+"\0":verdict)
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                }catch(Throwable e){failed.set(e);}
            });
            LotProofAvScanner scanner=new LotProofAvScanner(true,false,
                "127.0.0.1",server.getLocalPort());
            if(denial==null){
                assertDoesNotThrow(()->scanner.scan(DATA));
            }else{
                assertEquals(denial,assertThrows(ResponseStatusException.class,
                    ()->scanner.scan(DATA)).getStatusCode());
            }
            daemon.join(3000);
            assertFalse(daemon.isAlive(),"Test ClamAV protocol receiver blocked");
            assertNull(failed.get(),()->String.valueOf(failed.get()));
            assertArrayEquals(DATA,received.get(),
                "Only the actual original byte sequence may be scanned");
        }
    }

    @Test void exactCleanReplyAcceptsChunkedOriginal() throws Exception {
        fakeClamd("stream: OK",null);
    }
    @Test void knownMalwareDeniesOriginalWithoutExposingSignature() throws Exception {
        fakeClamd("stream: Synthetic-Signature FOUND",HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void truncatedOkWithoutNullTerminatorIsNotAValidCleanVerdict()
        throws Exception {
        fakeClamd("stream: OK",HttpStatus.SERVICE_UNAVAILABLE,false);
    }
    @Test void scannerErrorDeniesOriginal() throws Exception {
        fakeClamd("stream: INSTREAM size limit exceeded. ERROR",
            HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Test void unknownReplyCannotBeTreatedAsClean() throws Exception {
        fakeClamd("stream: MAYBE",HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Test void offlineScannerFailsClosedAndOnlySyntheticDemoMayBypass() throws Exception {
        int unusedPort;
        try(ServerSocket unused=new ServerSocket(0,1,
            InetAddress.getByName("127.0.0.1"))) {
            unusedPort=unused.getLocalPort();
        }
        LotProofAvScanner offline=new LotProofAvScanner(true,false,
            "127.0.0.1",unusedPort);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,()->offline.scan(DATA))
            .getStatusCode());
        LotProofAvScanner demo=new LotProofAvScanner(false,true,"",3310);
        assertDoesNotThrow(()->demo.scan(DATA));
        LotProofAvScanner noScanner=new LotProofAvScanner(false,false,"",3310);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,()->noScanner.scan(DATA))
            .getStatusCode());
        assertThrows(IllegalStateException.class,
            ()->new LotProofAvScanner(true,false,"",3310));
        assertThrows(IllegalStateException.class,
            ()->new LotProofAvScanner(true,false,"127.0.0.1",0));
    }
}
