package com.keyloop.scheduler.availability;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bookable starts per dealership-local date; instants serialise as UTC {@code Z}. */
public record AvailabilityView(
    UUID dealershipId, String timeZone, int durationMinutes, List<Day> days) {

  /** The starts of one local date (empty when closed or full). */
  public record Day(LocalDate date, List<Instant> starts) {}
}
