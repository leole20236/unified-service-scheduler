package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where availability reads occupancy from. There must be exactly one preferred bean: the database
 * implementation now, a Redis-ledger implementation (marked {@code @Primary}) later.
 */
public interface OccupancySource {

  /** Reservations that matter for the dealership-local date (its opening window plus buffer). */
  Occupancy forDay(UUID dealershipId, LocalDate date, DealershipView dealership);
}
