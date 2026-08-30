package com.synapsys.springboot.starter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Client {

    private final java.net.http.HttpClient httpClient;
    private final Duration requestTimeout;
    private final String coreToken;
    /**
     * Unknown response fields are ignored on purpose. Core is versioned independently
     * of this client: a field added to a Core response must never break workers that
     * were built against an older contract.
     */
    private static final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public Client() {
        this(Duration.ofSeconds(2), Duration.ofSeconds(5), null);
    }

    public Client(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, null);
    }

    public Client(Duration connectTimeout, Duration requestTimeout, String coreToken) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Explicit rather than relying on the JDK default: a redirect would
                // replay the Authorization header at whatever host the response names,
                // which is a credential handed to a third party.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
        this.coreToken = coreToken;
    }

    /**
     * Makes an unauthenticated HTTP call and returns only the response status code.
     *
     * <p>Used for the Core health probe, which Core serves without authentication. The
     * bearer token is not sent to an endpoint that does not ask for one: every place a
     * credential travels is a place it can leak.
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
            HttpRequest request = switch (method) {
                case GET -> builder.GET().build();
                case POST -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                case PUT -> builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                case DELETE -> builder.DELETE().build();
            };
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call URL: " + url, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call URL: " + url, e);
        }
    }

    /**
     * Executes an authenticated call with a JSON body and returns both the status code
     * and the parsed response body.
     *
     * <p>A body that cannot be parsed yields a null body rather than an exception: the
     * caller distinguishes "could not reach Core" from "Core answered with something
     * this client cannot read", and reports the two differently.
     *
     * @param url          URL to call
     * @param body         Request body (will be serialized as JSON)
     * @param responseType Class of expected response body
     * @param <B>          Type of request body
     * @param <R>          Type of response body
     * @return HttpResponseWrapper&lt;R&gt; containing status code + parsed body
     */
    public <B, R> HttpResponseWrapper<R> makeJsonCall(
            HttpMethod method,
            String url,
            B body,
            Class<R> responseType
    ) {
        HttpResponse<String> response;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            addAuthentication(builder);

            if (body != null) {
                String json = mapper.writeValueAsString(body);
                builder.method(method.name(), HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
            }

            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed " + method + " call to: " + url, e);
        } catch (Exception e) {
            // The token is never part of this message: the URL carries no credential and
            // the exception cause is a transport failure, not a request dump.
            throw new RuntimeException("Failed " + method + " call to: " + url, e);
        }

        R parsedBody = null;
        if (response.body() != null && !response.body().isBlank() && responseType != Void.class) {
            try {
                parsedBody = mapper.readValue(response.body(), responseType);
            } catch (JsonProcessingException e) {
                // Left null on purpose. The caller treats an unreadable 2xx body as a
                // protocol failure, requeues its logs and acknowledges nothing.
                parsedBody = null;
            }
        }

        return new HttpResponseWrapper<>(response.statusCode(), parsedBody);
    }

    private void addAuthentication(HttpRequest.Builder builder) {
        if (coreToken != null && !coreToken.isBlank()) {
            builder.header("Authorization", "Bearer " + coreToken);
        }
    }


}
