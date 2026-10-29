package com.keyloop.scheduler.booking.domain;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.UUID;

/**
 * A reservation cut short: {@code remaining} is its new period, or null when nothing was left and
 * the row was deactivated.
 */
public record TruncatedReservation(
    UUID reservationId, UUID resourceId, String resourceType, TimeRange remaining) {

  /** Whether the reservation is still active. */
  public boolean active() {
    return remaining != null;
  }
}
