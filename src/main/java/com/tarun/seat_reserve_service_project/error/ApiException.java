package com.tarun.seat_reserve_service_project.error;


import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * A domain outcome that maps to a 4xx response. Thrown inside a transaction it also rolls the
 * transaction back, so a decline never leaves partial state behind.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> seats;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<String> seats) {
        super(message, null, false, false); // no stack trace: these are expected outcomes, not bugs
        this.status = status;
        this.code = code;
        this.seats = seats;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> seats() {
        return seats;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
