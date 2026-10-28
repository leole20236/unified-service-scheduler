package com.keyloop.scheduler.booking;

import java.time.Instant;
import java.util.UUID;

/** {@code data} of ResourceBlockCreated / ResourceBlockRemoved (spec 10.2). */
public record ResourceBlockEvent(
    UUID blockId,
    String resourceType,
    UUID resourceId,
    Instant start,
    Instant end,
    String reason,
    String timeZone) {}
