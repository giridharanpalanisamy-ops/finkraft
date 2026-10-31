package com.finkraft.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A tiny HTTP client for the public API and the mocks' control APIs. */
public final class Api {

    public record Response(int status, JsonNode body, String raw) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Api() {}

    public static Response post(String url, Object body, String... headers) {
        return send(url, "POST", json(body), "application/json", headers);
    }

    public static Response postRaw(String url, String body, String contentType, String... headers) {
        return send(url, "POST", body, contentType, headers);
    }

    public static Response get(String url, String... headers) {
        return send(url, "GET", null, null, headers);
    }

    private static Response send(String url, String method, String body, String contentType, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = null;
            if (res.body() != null && !res.body().isBlank()) {
                try {
                    node = CanonicalJson.mapper().readTree(res.body());
                } catch (Exception e) {
                    // not JSON
                }
            }
            return new Response(res.statusCode(), node, res.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + url + " failed", e);
        }
    }

    public static String json(Object body) {
        try {
            return body instanceof String s ? s : CanonicalJson.mapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
