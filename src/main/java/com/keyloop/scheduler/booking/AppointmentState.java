package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.UUID;

/** The columns of an appointment that change decisions are made on. */
public record AppointmentState(
    UUID id,
    UUID customerId,
    UUID dealershipId,
    AppointmentStatus status,
    long version,
    TimeRange period,
    UUID serviceBayId,
    UUID technicianId) {}
