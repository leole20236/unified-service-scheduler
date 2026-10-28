package com.keyloop.scheduler.booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code POST …/resource-blocks}: block a bay or technician for {@code [start, end)} (spec 6.5);
 * {@code overrideHold} takes time a customer is holding.
 */
public record BlockRequest(
    String resourceType,
    UUID resourceId,
    OffsetDateTime start,
    OffsetDateTime end,
    String reason,
    boolean overrideHold) {}
