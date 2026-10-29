package com.keyloop.scheduler.booking.domain;

import java.util.UUID;

/** An active resource block. */
public record BlockRecord(UUID id, ReservationRow row, String reason) {}
