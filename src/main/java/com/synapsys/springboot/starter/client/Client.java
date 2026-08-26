package com.synapsys.springboot.starter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Client {

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final String coreToken;
    private static final ObjectMapper mapper = new ObjectMapper();

    public Client() {
        this(Duration.ofSeconds(2), Duration.ofSeconds(5), null);
    }

    public Client(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, null);
    }

    public Client(Duration connectTimeout, Duration requestTimeout, String coreToken) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        this.requestTimeout = requestTimeout;
        this.coreToken = coreToken;
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout);
            addAuthentication(builder);
            HttpRequest request = switch (method) {
                case GET -> builder.GET().build();
                case POST -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                case PUT -> builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                case DELETE -> builder.DELETE().build();
            };
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
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
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json");
            addAuthentication(builder);

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
            throw new RuntimeException("Failed " + method + " call to: " + url, e);
        }
    }

    private void addAuthentication(HttpRequest.Builder builder) {
        if (coreToken != null && !coreToken.isBlank()) {
            builder.header("Authorization", "Bearer " + coreToken);
        }
    }


}
