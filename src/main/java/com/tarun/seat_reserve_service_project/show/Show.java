package com.tarun.seat_reserve_service_project.show;

import java.util.UUID;

/** Immutable after creation, so it is safe to cache. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
}
