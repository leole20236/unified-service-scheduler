package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.query.AppointmentView;

/** Outcome of a booking: HTTP status and body; {@code replayed} for idempotent replays. */
public record BookingResult(int httpStatus, AppointmentView body, boolean replayed) {}
