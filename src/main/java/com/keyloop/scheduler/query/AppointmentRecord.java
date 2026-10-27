package com.keyloop.scheduler.query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Flat appointment data, as read from the database or assembled by a command. */
public record AppointmentRecord(
    UUID id,
    String status,
    long version,
    UUID dealershipId,
    String dealershipName,
    String timeZone,
    UUID vehicleId,
    String registration,
    String make,
    String model,
    OffsetDateTime start,
    OffsetDateTime end,
    UUID serviceBayId,
    String serviceBayName,
    UUID technicianId,
    String technicianName,
    List<ServiceLine> services) {}
