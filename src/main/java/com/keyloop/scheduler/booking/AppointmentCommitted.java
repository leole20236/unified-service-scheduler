package com.keyloop.scheduler.booking;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A booking that has committed: its dealership-local date, the hold it confirmed (if any) and the
 * reservations written, keyed by reservation id.
 */
public record AppointmentCommitted(
    UUID appointmentId,
    UUID dealershipId,
    LocalDate localDate,
    UUID holdId,
    Map<UUID, ReservationRow> reservations) {}
