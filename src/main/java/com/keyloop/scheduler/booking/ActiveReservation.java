package com.keyloop.scheduler.booking;

import java.util.UUID;

/** An active reservation of an appointment with its id. */
public record ActiveReservation(UUID reservationId, ReservationRow row) {}
