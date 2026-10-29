package com.keyloop.scheduler.booking.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code data} of the AppointmentBooked event (spec 10.2); ids only, no personal data. */
public record AppointmentBookedEvent(
    UUID appointmentId,
    UUID customerId,
    UUID vehicleId,
    List<String> serviceTypeCodes,
    UUID serviceBayId,
    UUID technicianId,
    Instant start,
    Instant end,
    String timeZone,
    String status) {}
