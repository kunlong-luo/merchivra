package org.merchivra.erp.purchasing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * V137: fail-closed private proof original scanning using the mature ClamAV
 * clamd INSTREAM protocol. Only an explicit synthetic Demo deployment may
 * bypass the scanner; all other deployments deny unscanned original uploads.
 * TCP is unauthenticated: only connect to an internal/loopback address and
 * require network isolation at the actual operator-controlled deployment.
 */
@Component
public class LotProofAvScanner {
    private static final byte[] COMMAND="zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK=32*1024;
    private static final int MAX_REPLY=512;
    private final boolean required;
    private final boolean demo;
    private final String host;
    private final int port;

    public LotProofAvScanner(
        @Value("${merchivra.evidence.av-required:false}") boolean required,
        @Value("${merchivra.demo.enabled:false}") boolean demo,
        @Value("${merchivra.evidence.av-host:}") String host,
        @Value("${merchivra.evidence.av-port:3310}") int port) {
        this.required=required;
        this.demo=demo;
        this.host=host==null?"":host.strip();
        this.port=port;
        if(required && this.host.isBlank())
            throw new IllegalStateException(
                "Private proof scanner required but internal clamd host is not configured");
        if(required && (port<1 || port>65535))
            throw new IllegalStateException("Invalid private proof scanner port");
    }

    public void scan(byte[] bytes) {
        if(bytes==null || bytes.length<1 || bytes.length>LotProofBinaryService.MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Private evidence scan size is invalid");
        if(!required) {
            if(!demo)throw unavailable();
            // Never represent a Demo-only bypass as verified malware-free.
            return;
        }
        try {
            // ClamD TCP has NO TLS or authentication. Resolve once and reject
            // any routable external address to prevent document exfiltration.
            InetAddress addr=InetAddress.getByName(host);
            if(!addr.isLoopbackAddress() && !addr.isSiteLocalAddress())
                throw unavailable();
            try(Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress(addr,port),1500);
                socket.setSoTimeout(6000);
                OutputStream out=socket.getOutputStream();
                out.write(COMMAND);
                for(int offset=0;offset<bytes.length;offset+=CHUNK) {
                    int length=Math.min(CHUNK,bytes.length-offset);
                    out.write(ByteBuffer.allocate(4).putInt(length).array());
                    out.write(bytes,offset,length);
                }
                out.write(new byte[4]); // zero-length terminator
                out.flush();
                String result=readReply(socket.getInputStream());
                if("stream: OK".equals(result))return;
                if(result.startsWith("stream: ") && result.endsWith(" FOUND"))
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Private original file failed malware screening");
                throw unavailable(); // ERROR, partial response, unknown protocol
            }
        } catch(IOException ex) {
            // Never expose scanner host, signature names or original bytes.
            throw unavailable();
        }
    }

    private static String readReply(InputStream in) throws IOException {
        var buffer=new ByteArrayOutputStream();
        while(buffer.size()<MAX_REPLY){
            int next=in.read();
            if(next<0 || next==0 || next=='\n')break;
            if(next<32 || next>126)
                throw new IOException("Invalid scanner response framing");
            buffer.write(next);
        }
        if(buffer.size()==MAX_REPLY)throw new IOException("Oversized scanner response");
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static ResponseStatusException unavailable(){
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Private evidence malware screening unavailable");
    }
}
