package com.tarun.seat_reserve_service_project.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tarun.seat_reserve_service_project.config.BookingProperties;
import com.tarun.seat_reserve_service_project.error.ApiException;


@RestController
public class AuthController {

    private final TokenService tokens;
    private final BookingProperties props;

    public AuthController(TokenService tokens, BookingProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    public record TokenRequest(@JsonProperty("user_id") String userId) {
    }

    /**
     * Development token issuer standing in for a real identity provider: mints a signed token for the
     * given user id. Disabled with AUTH_DEV_ISSUER_ENABLED=false.
     */
    @PostMapping("/auth/token")
    public Map<String, Object> issue(@RequestBody TokenRequest request) {
        if (!props.auth().devIssuerEnabled()) {
            throw ApiException.notFound("not_found", "token issuer disabled");
        }
        String userId = request == null ? null : request.userId();
        return Map.of("user_id", String.valueOf(userId), "token", tokens.issue(userId), "token_type", "Bearer");
    }

    @GetMapping("/me")
    public Map<String, Object> me(AuthenticatedUser user) {
        return Map.of("user_id", user.id());
    }
}

