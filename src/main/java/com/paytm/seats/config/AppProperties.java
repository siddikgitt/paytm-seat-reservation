package com.paytm.seats.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        String adminApiKey,
        Duration tokenTtl,
        int defaultPerUserLimit,
        int maxSeatsPerShow,
        Metrics metrics) {

    public record Metrics(int trackedShows, long refreshMs) {
    }
}
