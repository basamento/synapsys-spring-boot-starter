package com.synapsys.springboot.starter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Client {

    private final HttpClient httpClient;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(Client.class);

    public Client() {
        this.httpClient = HttpClient.newBuilder().build();
    }

    /**
     * Makes an HTTP call and returns only the response status code.
     *
     * @param method GET, POST, PUT, DELETE, etc.
     * @param url    The URL to call
     * @return HTTP response status code
     */
    public int makeCallAndReturnResponseCode(HttpMethod method, String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
            HttpRequest request = switch (method) {
                case GET -> builder.GET().build();
                case POST -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                case PUT -> builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                case DELETE -> builder.DELETE().build();
            };
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            log.error("SynapsysNeuronProcessor: error calling {} {} → {}", method, url, e.getMessage());
            throw new RuntimeException("Failed to call URL: " + url, e);
        }
    }

    /**
     * Executes a POST call with a JSON body and returns both status code and parsed response body.
     *
     * @param url       URL to call
     * @param body      Request body (will be serialized as JSON)
     * @param responseType Class of expected response body
     * @param <B>       Type of request body
     * @param <R>       Type of response body
     * @return          HttpResponseWrapper<R> containing status code + parsed body
     */
    public <B, R> HttpResponseWrapper<R> makeJsonCall(
            HttpMethod method,
            String url,
            B body,
            Class<R> responseType
    ) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json");

            if (body != null) {
                String json = mapper.writeValueAsString(body);
                builder.method(method.name(), HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            R parsedBody = null;
            if (response.body() != null && !response.body().isBlank() && responseType != Void.class) {
                parsedBody = mapper.readValue(response.body(), responseType);
            }

            return new HttpResponseWrapper<>(response.statusCode(), parsedBody);

        } catch (Exception e) {
            log.error("SynapsysNeuronProcessor: {} {} failed → {}", method, url, e.getMessage());
            throw new RuntimeException("Failed " + method + " call to: " + url, e);
        }
    }


}
