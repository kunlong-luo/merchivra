package org.merchivra.erp.fba;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.HashSet;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.LocalAdminBootstrap;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Springdoc contract with bootstrap mocked; no database or Amazon traffic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"spring.flyway.enabled=false", "MERCHIVRA_CONTRACT_EXPORT_ENABLED=true"})
class FbaPurchaseLinkOpenApiV145Test {
    @MockitoBean LocalAdminBootstrap bootstrap;
    @LocalServerPort int port;
    @Test void exportActualControllerContract() throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:"+port+"/v3/api-docs/fba-inbound"))
            .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        var doc=new ObjectMapper().readTree(response.body());
        var paths=doc.path("paths");
        String root="/api/v1/fba/marketplaces/{marketplaceId}/shipments/{shipmentId}/purchase-links";
        String[][] operations={
            {root,"get","listFbaPurchaseLinks","marketplaceId,shipmentId"},
            {root+"/lines/{lineId}/candidates","get","listFbaPurchaseCandidates","marketplaceId,shipmentId,lineId"},
            {root,"post","proposeFbaPurchaseLink","marketplaceId,shipmentId"},
            {root+"/{linkId}/confirm","post","confirmFbaPurchaseLink","marketplaceId,shipmentId,linkId"},
            {root+"/{linkId}/revoke","post","revokeFbaPurchaseLink","marketplaceId,shipmentId,linkId"}
        };
        for(var op:operations){
            var actual=paths.path(op[0]).path(op[1]);
            assertEquals(op[2],actual.path("operationId").asText());
            Set<String> parameters=new HashSet<>();
            for(var parameter:actual.path("parameters")){
                parameters.add(parameter.path("name").asText());
                assertEquals("path",parameter.path("in").asText());
                assertEquals("uuid",parameter.path("schema").path("format").asText());
            }
            assertEquals(Set.of(op[3].split(",")),parameters);
            var schema=actual.path("responses").path("200").path("content")
                .path("*/*").path("schema");
            if(op[1].equals("get"))assertEquals("array",schema.path("type").asText());
            else {
                assertEquals("#/components/schemas/Linked",schema.path("$ref").asText());
                assertTrue(actual.has("requestBody"));
            }
        }
        // V147 adds separate whole-store source pages; older array reads remain compatible.
        String[][] pages={
            {root+"/page","listFbaPurchaseLinksPage","marketplaceId,shipmentId",
                "limit,state,inboundLineId,cursor"},
            {root+"/lines/{lineId}/candidates/page","listFbaPurchaseCandidatesPage",
                "marketplaceId,shipmentId,lineId","limit,purchaseLineId,cursor"}
        };
        for(var page:pages){
            var op=paths.path(page[0]).path("get");
            assertEquals(page[1],op.path("operationId").asText());
            Set<String> pathParams=new HashSet<>(),queryParams=new HashSet<>();
            for(var p:op.path("parameters")){
                if("path".equals(p.path("in").asText())){
                    pathParams.add(p.path("name").asText());
                    assertEquals("uuid",p.path("schema").path("format").asText());
                }else if("query".equals(p.path("in").asText()))
                    queryParams.add(p.path("name").asText());
            }
            assertEquals(Set.of(page[2].split(",")),pathParams);
            assertEquals(Set.of(page[3].split(",")),queryParams);
            String schemaRef=op.path("responses").path("200").path("content")
                .path("*/*").path("schema").path("$ref").asText();
            assertTrue(schemaRef.startsWith("#/components/schemas/"),schemaRef);
            assertTrue(schemaRef.contains("Page"),schemaRef);
        }
        var schemas=doc.path("components").path("schemas");
        for(String name:new String[]{"Propose","Candidate","Linked"}){
            var asnType=schemas.path(name).path("properties").path("asnId").path("type");
            assertTrue(asnType.isArray(),name+" ASN must preserve explicit null");
            assertTrue(asnType.toString().contains("null"));
        }
        // Build output enables normalizing this real contract locally; never a fixture.
        Files.writeString(Path.of("build/fba-openapi-v145.json"),response.body());
    }
}
