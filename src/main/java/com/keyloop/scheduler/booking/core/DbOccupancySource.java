package com.keyloop.scheduler.booking.core;

import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.booking.domain.OccupancySource;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.time.OpeningHoursCalendar;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Occupancy straight from the database; correct without Redis, one indexed query per day. */
@Component
public class DbOccupancySource implements OccupancySource {

  private final OccupancyRepository repository;

  /** Creates the source. */
  public DbOccupancySource(OccupancyRepository repository) {
    this.repository = repository;
  }

  @Override
  public Occupancy forDay(UUID dealershipId, LocalDate date, DealershipView dealership) {
    return OpeningHoursCalendar.window(dealership, date)
        .map(
            window ->
                repository.load(
                    dealershipId,
                    window.extendEnd(Duration.ofMinutes(dealership.bayBufferMinutes()))))
        .orElseGet(Occupancy::empty);
  }
}
