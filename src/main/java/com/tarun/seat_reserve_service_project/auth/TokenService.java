package com.tarun.seat_reserve_service_project.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.tarun.seat_reserve_service_project.config.BookingProperties;
import com.tarun.seat_reserve_service_project.error.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal HS256 JWT issue/verify. The user id lives only in the signed "sub" claim, so a caller
 * can never act as anyone other than the subject of a token signed with our secret.
 */
@Service
public class TokenService {

    public static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_.@-]{1,64}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final BookingProperties props;
    private final ObjectMapper mapper;
    private final SecretKeySpec key;
    private final Clock clock = Clock.systemUTC();

    public TokenService(BookingProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        byte[] secret = props.auth().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("AUTH_SECRET must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    public String issue(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw ApiException.badRequest("user_id must match " + USER_ID.pattern());
        }
        Instant now = clock.instant();
        Map<String, Object> claims = Map.of(
                "sub", userId,
                "iat", now.getEpochSecond(),
                "exp", now.plus(props.auth().tokenTtl()).getEpochSecond());
        try {
            String payload = B64.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return signingInput + "." + B64.encodeToString(sign(signingInput));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the verified user id, or throws 401. */
    public String verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            throw ApiException.unauthorized("malformed token");
        }
        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("malformed token");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw ApiException.unauthorized("bad token signature");
        }
        try {
            JsonNode claims = mapper.readTree(B64D.decode(parts[1]));
            String sub = claims.path("sub").asString(null);
            long exp = claims.path("exp").asLong(0);
            if (sub == null || !USER_ID.matcher(sub).matches()) {
                throw ApiException.unauthorized("token has no valid subject");
            }
            if (exp <= clock.instant().getEpochSecond()) {
                throw ApiException.unauthorized("token expired");
            }
            return sub;
        } catch (JacksonException | IllegalArgumentException e) {
            throw ApiException.unauthorized("malformed token");
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}