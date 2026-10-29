package com.keyloop.scheduler.booking.change;

import com.keyloop.scheduler.booking.domain.HoldReason;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST …/{id}/hold}: the reason, and whether the car leaves its bay. */
public record HoldActionRequest(@NotNull HoldReason holdReason, boolean releaseBay) {}
