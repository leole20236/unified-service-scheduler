package com.keyloop.scheduler.support;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.catalog.TechnicianView;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory catalog objects for pure unit tests. */
public final class CatalogFixtures {

  public static final ServiceTypeView OIL_CHANGE =
      new ServiceTypeView(Fixtures.OIL_CHANGE, "OIL_CHANGE", "Oil change", 45, Fixtures.OIL);
  public static final ServiceTypeView BRAKE_INSPECTION =
      new ServiceTypeView(
          Fixtures.BRAKE_INSPECTION, "BRAKE_INSPECTION", "Brake inspection", 75, Fixtures.BRAKES);
  public static final ServiceTypeView DIAGNOSTIC =
      new ServiceTypeView(Fixtures.DIAGNOSTIC, "DIAGNOSTIC", "Diagnostic", 60, Fixtures.DIAG);

  private CatalogFixtures() {}

  /** Riverside Motors: London, Monday to Saturday 08:00-18:00, closed Sunday. */
  public static DealershipView riverside() {
    Map<DayOfWeek, OpeningHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) {
      if (day != DayOfWeek.SUNDAY) {
        hours.put(day, new OpeningHours(LocalTime.of(8, 0), LocalTime.of(18, 0)));
      }
    }
    return new DealershipView(
        Fixtures.RIVERSIDE, "Riverside Motors", ZoneId.of("Europe/London"), 0, hours);
  }

  /** Hudson: New York, 10 minute bay buffer, open every day 09:00-17:00. */
  public static DealershipView hudson() {
    Map<DayOfWeek, OpeningHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) {
      hours.put(day, new OpeningHours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }
    return new DealershipView(Fixtures.HUDSON, "Hudson", ZoneId.of("America/New_York"), 10, hours);
  }

  /** A London dealership open 00:30-03:30 every day (spans the spring-forward gap). */
  public static DealershipView nightOwl() {
    Map<DayOfWeek, OpeningHours> hours = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek day : DayOfWeek.values()) {
      hours.put(day, new OpeningHours(LocalTime.of(0, 30), LocalTime.of(3, 30)));
    }
    return new DealershipView(UUID.randomUUID(), "Night Owl", ZoneId.of("Europe/London"), 0, hours);
  }

  /** A catalog offering the three standard services. */
  public static ResourceCatalog catalog(DealershipView dealership) {
    return new ResourceCatalog(
        dealership,
        List.of(OIL_CHANGE, BRAKE_INSPECTION, DIAGNOSTIC),
        List.<BayView>of(),
        List.<TechnicianView>of());
  }
}
