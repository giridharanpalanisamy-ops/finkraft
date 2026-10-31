package com.finkraft.adapter;

/** rateLimitRps is 0 when the ASP publishes no limit. */
public record AspCapabilities(
        StatusMode statusMode, boolean nativeDedup, double rateLimitRps, boolean safeToRetryOnTimeout) {}
