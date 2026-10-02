package com.tarun.seat_reserve_service_project.auth;


/** The caller's identity, resolved from the bearer token only — never from the request body. */
public record AuthenticatedUser(String id) {
}
