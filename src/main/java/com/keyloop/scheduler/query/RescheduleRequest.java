package com.keyloop.scheduler.query;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of {@code POST /me/appointments/{id}/reschedule}: the hold taken for the new time. */
public record RescheduleRequest(@NotNull UUID holdId) {}
