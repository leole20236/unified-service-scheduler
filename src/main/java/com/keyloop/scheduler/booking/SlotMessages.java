package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human {@code detail} texts about slots, in dealership-local time (spec 14.6). */
public final class SlotMessages {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

  private SlotMessages() {}

  /** The SLOT_UNAVAILABLE detail, e.g. "No qualified technician and bay are free at 09:00 ...". */
  public static String unavailable(JobPlan plan, DealershipView d) {
    return "No qualified technician and bay are free at "
        + at(plan.localStart())
        + " ("
        + d.zoneId().getId()
        + ").";
  }

  /** Local time and day, e.g. "09:00 on Mon 2 Nov". */
  static String at(LocalDateTime local) {
    return CLOCK.format(local) + " on " + DAY.format(local);
  }
}
