package com.keyloop.scheduler.common.time;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/** Turns a dealership's local wall-clock opening hours into UTC windows (DST aware). */
public final class OpeningHoursCalendar {

  private OpeningHoursCalendar() {}

  /** The open window on a local date, empty when the dealership is closed that day. */
  public static Optional<TimeRange> window(DealershipView d, LocalDate date) {
    OpeningHours hours = d.openingHours().get(date.getDayOfWeek());
    if (hours == null) {
      return Optional.empty();
    }
    Instant opens = ZonedDateTime.of(date, hours.opensAt(), d.zoneId()).toInstant();
    Instant closes = ZonedDateTime.of(date, hours.closesAt(), d.zoneId()).toInstant();
    return Optional.of(new TimeRange(opens, closes));
  }

  /** The dealership-local calendar date of an instant. */
  public static LocalDate localDate(DealershipView d, Instant t) {
    return t.atZone(d.zoneId()).toLocalDate();
  }

  /** The instant expressed with the dealership's offset at that moment. */
  public static OffsetDateTime toDealershipOffset(DealershipView d, Instant t) {
    return t.atZone(d.zoneId()).toOffsetDateTime();
  }
}
