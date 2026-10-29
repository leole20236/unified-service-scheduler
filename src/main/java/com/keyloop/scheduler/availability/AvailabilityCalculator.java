package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.booking.domain.JobPlan;
import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.time.OpeningHoursCalendar;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Pure availability maths over a catalog and an occupancy snapshot (spec 9.3). */
public final class AvailabilityCalculator {

  private static final Duration STEP = Duration.ofMinutes(15);
  private static final int ALTERNATIVE_DAYS = 14;

  private AvailabilityCalculator() {}

  /**
   * Bookable starts on one dealership-local date, in the dealership's offset. Candidates step in
   * real time from opening, so a daylight-saving gap can never produce a non-existent local time.
   */
  public static List<OffsetDateTime> starts(
      ResourceCatalog catalog,
      Set<String> skills,
      int durationMinutes,
      LocalDate date,
      Occupancy occupancy,
      Instant earliest) {
    DealershipView d = catalog.dealership();
    Optional<TimeRange> window = OpeningHoursCalendar.window(d, date);
    if (window.isEmpty()) {
      return List.of();
    }
    List<TechnicianView> qualified =
        catalog.technicians().stream().filter(t -> t.skills().containsAll(skills)).toList();
    Duration job = Duration.ofMinutes(durationMinutes);
    Duration buffer = Duration.ofMinutes(d.bayBufferMinutes());
    List<OffsetDateTime> result = new ArrayList<>();
    for (Instant start = window.get().start();
        !start.plus(job).isAfter(window.get().end());
        start = start.plus(STEP)) {
      if (start.isBefore(earliest)) {
        continue;
      }
      TimeRange period = new TimeRange(start, start.plus(job));
      if (hasFreePair(catalog, qualified, occupancy, period, period.extendEnd(buffer))) {
        result.add(OpeningHoursCalendar.toDealershipOffset(d, start));
      }
    }
    return result;
  }

  /**
   * The nearest starts after the requested one, looking up to 14 days ahead and never past {@code
   * latest}. Occupancy is fetched per dealership-local date, lazily: closed dates are never loaded
   * and the search stops as soon as {@code limit} starts are found.
   */
  public static List<OffsetDateTime> alternatives(
      ResourceCatalog catalog,
      JobPlan plan,
      Function<LocalDate, Occupancy> occupancyFor,
      Instant earliest,
      Instant latest,
      int limit) {
    List<OffsetDateTime> result = new ArrayList<>();
    Instant requested = plan.period().start();
    for (int offset = 0; offset <= ALTERNATIVE_DAYS && result.size() < limit; offset++) {
      LocalDate date = plan.localDate().plusDays(offset);
      Optional<TimeRange> window = OpeningHoursCalendar.window(catalog.dealership(), date);
      if (window.isEmpty()) {
        continue;
      }
      if (window.get().start().isAfter(latest)) {
        break;
      }
      Occupancy occupancy = occupancyFor.apply(date);
      for (OffsetDateTime start :
          starts(
              catalog, plan.requiredSkills(), plan.durationMinutes(), date, occupancy, earliest)) {
        Instant at = start.toInstant();
        if (at.isAfter(requested) && !at.isAfter(latest) && result.size() < limit) {
          result.add(start);
        }
      }
    }
    return result;
  }

  private static boolean hasFreePair(
      ResourceCatalog catalog,
      List<TechnicianView> qualified,
      Occupancy occupancy,
      TimeRange period,
      TimeRange bayPeriod) {
    boolean technician = qualified.stream().anyMatch(t -> occupancy.isFree(t.id(), period));
    return technician
        && catalog.bays().stream().map(BayView::id).anyMatch(b -> occupancy.isFree(b, bayPeriod));
  }
}
