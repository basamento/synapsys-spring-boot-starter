package com.synapsys.springboot.starter.client;

public class HttpResponseWrapper<T> {

    private final int statusCode;
    private final T body;

    public HttpResponseWrapper(int statusCode, T body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public T getBody() {
        return body;
    }

}
