package com.synapsys.springboot.starter.client;

import com.sun.net.httpserver.HttpServer;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.dto.response.HeartbeatProcessesResponseDTO;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ClientTest {

    /**
     * Core is versioned independently of this client. A field added to a Core response
     * must not break workers built against an older contract.
     */
    @Test
    public void unknownResponseFieldsAreIgnored() throws Exception {
        String body = "{\"processes\":[{\"name\":\"demo\",\"desiredState\":\"running\"}],"
                + "\"warnings\":[\"something new\"],\"serverVersion\":\"9.9.9\"}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/heartbeat", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        try {
            Client client = new Client(Duration.ofSeconds(2), Duration.ofSeconds(5), "token");
            HttpResponseWrapper<HeartbeatProcessesResponseDTO> response = client.makeJsonCall(
                    HttpMethod.POST,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/heartbeat",
                    null,
                    HeartbeatProcessesResponseDTO.class
            );

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(1, response.getBody().getProcesses().size());
            assertEquals("demo", response.getBody().getProcesses().get(0).getName());
        } finally {
            server.stop(0);
        }
    }
}
