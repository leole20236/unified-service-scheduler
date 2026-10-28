package com.keyloop.scheduler.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An appointment of the board as read from the database. */
public record ScheduleAppointmentRow(
    UUID id,
    long version,
    String status,
    String holdReason,
    UUID serviceBayId,
    UUID technicianId,
    Instant start,
    Instant end,
    int plannedMinutes,
    int workedMinutes,
    String registration,
    String make,
    String model,
    List<String> serviceCodes) {}
