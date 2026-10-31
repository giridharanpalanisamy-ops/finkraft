package com.finkraft.adapters;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** finkraft.asp.* (spec section 10). Adapters read the HTTP settings; integration-service the rest. */
@ConfigurationProperties("finkraft.asp")
public record AspAdapterProperties(
        Duration connectTimeout,
        Duration readTimeout,
        Duration permitWait,
        AspA a,
        AspB b) {

    /** apiKey is Finkraft's own key for status reads; submits use the seller's credentials. */
    public record AspA(String baseUrl, String apiKey, double rateLimitRps, List<Duration> timeoutResends) {}

    /** rateLimitRps 0 means no published limit. */
    public record AspB(String baseUrl, String webhookSecret, String signatureHeader, double rateLimitRps) {}
}
