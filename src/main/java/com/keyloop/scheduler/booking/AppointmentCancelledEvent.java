package com.keyloop.scheduler.booking;

import java.time.Instant;
import java.util.UUID;

/** {@code data} of the AppointmentCancelled event (spec 10.2); ids only. */
public record AppointmentCancelledEvent(
    UUID appointmentId,
    UUID customerId,
    Instant start,
    Instant end,
    UUID serviceBayId,
    UUID technicianId,
    String timeZone,
    String status) {}
