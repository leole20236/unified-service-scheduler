package com.keyloop.scheduler.booking.change;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A dispatcher's drag-and-drop or resize ({@code PATCH}, merge-patch): omitted (null) fields keep
 * their current value; {@code durationMinutes} counts from the appointment's start.
 */
public record DispatcherChangeRequest(
    OffsetDateTime start,
    UUID serviceBayId,
    UUID technicianId,
    Integer durationMinutes,
    boolean overrideHold) {}
