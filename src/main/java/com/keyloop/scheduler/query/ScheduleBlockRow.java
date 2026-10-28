package com.keyloop.scheduler.query;

import java.time.Instant;
import java.util.UUID;

/** An active resource block as read from the database; exactly one of bay and technician is set. */
public record ScheduleBlockRow(
    UUID id, UUID serviceBayId, UUID technicianId, Instant start, Instant end, String reason) {}
