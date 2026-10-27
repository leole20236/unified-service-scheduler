package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.booking.BookingPolicy;
import com.keyloop.scheduler.booking.JobPlan;
import com.keyloop.scheduler.booking.JobPlanner;
import com.keyloop.scheduler.booking.Occupancy;
import com.keyloop.scheduler.booking.OccupancySource;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Computes bookable starts for a service combination over a date range. */
@Service
public class AvailabilityService {

  static final int MAX_DAYS = 14;

  private final OccupancySource occupancy;
  private final CatalogService catalogs;
  private final DbClock clock;

  /** Creates the service. */
  public AvailabilityService(OccupancySource occupancy, CatalogService catalogs, DbClock clock) {
    this.occupancy = occupancy;
    this.catalogs = catalogs;
    this.clock = clock;
  }

  /**
   * Starts per local date; the caller's booking policy decides the earliest allowed start. Other
   * customers' live holds count as taken; the caller's own hold does not.
   */
  public AvailabilityView find(
      CallerContext caller,
      UUID dealershipId,
      List<UUID> serviceTypeIds,
      LocalDate from,
      LocalDate to) {
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days < 1) {
      throw invalid("from", "'from' must not be after 'to'.");
    }
    if (days > MAX_DAYS) {
      throw invalid("to", "The range may span at most " + MAX_DAYS + " days.");
    }
    ResourceCatalog catalog = catalogs.get(dealershipId);
    JobPlan plan =
        JobPlanner.plan(
            catalog,
            serviceTypeIds,
            from.atStartOfDay(catalog.dealership().zoneId()).toOffsetDateTime());
    BookingPolicy policy = BookingPolicy.forCaller(caller);
    Instant now = clock.now();
    Instant earliest = policy.earliestStart(now);
    Instant latest = policy.latestStart(now);
    List<AvailabilityView.Day> result = new ArrayList<>();
    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      Occupancy occupied =
          occupancy.forDay(dealershipId, date, catalog.dealership(), caller.userId());
      List<Instant> starts =
          AvailabilityCalculator.starts(
                  catalog, plan.requiredSkills(), plan.durationMinutes(), date, occupied, earliest)
              .stream()
              .map(OffsetDateTime::toInstant)
              .filter(start -> !start.isAfter(latest))
              .toList();
      result.add(new AvailabilityView.Day(date, starts));
    }
    return new AvailabilityView(
        dealershipId, catalog.dealership().zoneId().getId(), plan.durationMinutes(), result);
  }

  private static ValidationException invalid(String field, String message) {
    return new ValidationException(
        "1 field is invalid.", List.of(Map.of("field", field, "message", message)));
  }
}
