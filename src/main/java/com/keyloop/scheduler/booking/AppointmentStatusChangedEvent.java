package com.keyloop.scheduler.booking;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code data} of AppointmentStatusChanged (spec 10.2): from and to, with the appointment's period
 * and resources after the change; ids only.
 */
public record AppointmentStatusChangedEvent(
    UUID appointmentId,
    UUID customerId,
    String from,
    String to,
    String holdReason,
    String reason,
    Instant start,
    Instant end,
    UUID serviceBayId,
    UUID technicianId,
    String timeZone) {}
