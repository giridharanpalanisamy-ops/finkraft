package com.finkraft.integration;

import com.finkraft.adapters.AspAdapterProperties;
import com.finkraft.adapters.AspCatalog;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IntegrationConfig {

    /**
     * One limiter per rate-limited ASP, shared by submits and polls. One permit per 1/rps seconds
     * (rather than rps permits per second) so no one-second window ever sees a burst above the rate.
     */
    @Bean
    AspRateLimiters aspRateLimiters(AspAdapterProperties props) {
        Map<String, RateLimiter> limiters = new HashMap<>();
        add(limiters, AspCatalog.ASP_A, props.a().rateLimitRps(), props.permitWait());
        add(limiters, AspCatalog.ASP_B, props.b().rateLimitRps(), props.permitWait());
        return new AspRateLimiters(limiters);
    }

    private static void add(Map<String, RateLimiter> limiters, String aspId, double rps, Duration permitWait) {
        if (rps <= 0) {
            return;
        }
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofNanos(Math.round(1_000_000_000L / rps)))
                .timeoutDuration(permitWait)
                .build();
        limiters.put(aspId, RateLimiter.of(aspId, config));
    }

    @Bean
    RestClient invoiceHandlerClient(@Value("${finkraft.invoice-handler.url}") String url) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
