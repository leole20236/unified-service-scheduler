package com.keyloop.scheduler.booking.block;

import java.time.Instant;
import java.util.UUID;

/** A resource block as returned by the API; times are UTC instants. */
public record BlockView(
    UUID id, String resourceType, UUID resourceId, Instant start, Instant end, String reason) {}
