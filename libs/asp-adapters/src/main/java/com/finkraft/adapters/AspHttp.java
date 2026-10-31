package com.finkraft.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalJson;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Plain HTTP for adapters, classifying failures the way the submit contract needs them. */
final class AspHttp {

    /** How a call ended before any response, if it did. */
    enum Failure {
        /** Never connected: the ASP provably did not receive the request. */
        NOT_CONNECTED,
        /** Sent, but no response in time or the connection broke: the ASP may have it. */
        NO_RESPONSE
    }

    record Result(int status, String body, Failure failure) {

        JsonNode json() {
            try {
                return body == null || body.isBlank() ? CanonicalJson.mapper().createObjectNode() : CanonicalJson.mapper().readTree(body);
            } catch (IOException e) {
                return CanonicalJson.mapper().createObjectNode();
            }
        }
    }

    private final HttpClient client;
    private final Duration readTimeout;

    AspHttp(Duration connectTimeout, Duration readTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
        this.readTimeout = readTimeout;
    }

    Result post(String url, String json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach((k, v) -> { if (v != null) b.header(k, v); });
        return send(b.build());
    }

    Result get(String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).GET();
        headers.forEach((k, v) -> { if (v != null) b.header(k, v); });
        return send(b.build());
    }

    private Result send(HttpRequest request) {
        try {
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(res.statusCode(), res.body(), null);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return new Result(0, e.toString(), Failure.NOT_CONNECTED);
        } catch (HttpTimeoutException e) {
            return new Result(0, e.toString(), Failure.NO_RESPONSE);
        } catch (IOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                return new Result(0, e.toString(), Failure.NOT_CONNECTED);
            }
            return new Result(0, e.toString(), Failure.NO_RESPONSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, e.toString(), Failure.NO_RESPONSE);
        }
    }
}
