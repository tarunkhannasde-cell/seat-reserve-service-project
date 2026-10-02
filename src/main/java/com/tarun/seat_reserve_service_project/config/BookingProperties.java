package com.tarun.seat_reserve_service_project.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "booking")
public record BookingProperties(int defaultPerUserLimit, String adminKey, Auth auth, Metrics metrics) {

    public record Auth(String secret, Duration tokenTtl, boolean devIssuerEnabled) {
    }

    public record Metrics(long gaugeRefreshMs) {
    }
}
