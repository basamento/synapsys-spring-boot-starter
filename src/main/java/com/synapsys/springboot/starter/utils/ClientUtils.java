package com.synapsys.springboot.starter.utils;

public class ClientUtils {

    public static String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL cannot be null or empty");
        }
        if (path == null) path = "";
        path = path.startsWith("/") ? path.substring(1) : path;
        baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return baseUrl + path;
    }

}
