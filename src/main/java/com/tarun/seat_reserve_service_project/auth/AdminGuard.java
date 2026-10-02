package com.tarun.seat_reserve_service_project.auth;

import org.springframework.stereotype.Component;

import com.tarun.seat_reserve_service_project.config.BookingProperties;
import com.tarun.seat_reserve_service_project.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminGuard {

    private final byte[] adminKey;

    public AdminGuard(BookingProperties props) {
        this.adminKey = props.adminKey().getBytes(StandardCharsets.UTF_8);
    }

    public void check(String providedKey) {
        if (providedKey == null) {
            throw ApiException.unauthorized("missing X-Admin-Key");
        }
        if (!MessageDigest.isEqual(adminKey, providedKey.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("invalid admin key");
        }
    }
}
