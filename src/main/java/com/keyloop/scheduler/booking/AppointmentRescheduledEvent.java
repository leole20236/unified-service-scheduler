package com.keyloop.scheduler.booking;

import java.time.Instant;
import java.util.UUID;

/** {@code data} of the AppointmentRescheduled event (spec 10.2): old and new period/resources. */
public record AppointmentRescheduledEvent(
    UUID appointmentId,
    UUID customerId,
    Instant oldStart,
    Instant oldEnd,
    UUID oldServiceBayId,
    UUID oldTechnicianId,
    Instant newStart,
    Instant newEnd,
    UUID newServiceBayId,
    UUID newTechnicianId,
    String timeZone,
    String status) {}
