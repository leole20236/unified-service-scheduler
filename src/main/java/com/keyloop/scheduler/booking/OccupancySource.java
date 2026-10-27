package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where availability reads occupancy from. The Redis slot ledger is the preferred
 * ({@code @Primary}) bean and falls back to the database implementation when Redis fails.
 */
public interface OccupancySource {

  /** Reservations that matter for the dealership-local date (its opening window plus buffer). */
  Occupancy forDay(UUID dealershipId, LocalDate date, DealershipView dealership);

  /**
   * As {@link #forDay(UUID, LocalDate, DealershipView)}, as seen by {@code viewerId}: a source that
   * knows about holds leaves out the viewer's own hold, so they still see the slot they hold.
   */
  default Occupancy forDay(
      UUID dealershipId, LocalDate date, DealershipView dealership, UUID viewerId) {
    return forDay(dealershipId, date, dealership);
  }
}
