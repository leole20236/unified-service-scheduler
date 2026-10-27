package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.InvalidTransitionException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Checks and message helpers shared by both policies. */
final class PolicyChecks {

  static final Duration MAX_AHEAD = Duration.ofDays(60);
  static final Duration CUSTOMER_LEAD = Duration.ofHours(2);
  static final Duration CHANGE_CUTOFF = Duration.ofHours(24);

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

  private PolicyChecks() {}

  static void startWindow(JobPlan plan, DealershipView d, Instant earliest, Instant now) {
    Instant start = plan.period().start();
    if (start.isBefore(earliest)) {
      throw new BusinessRuleException(
          ProblemCodes.OUTSIDE_BOOKING_WINDOW,
          "The earliest start you can book is " + local(d, earliest) + ".");
    }
    Instant latest = now.plus(MAX_AHEAD);
    if (start.isAfter(latest)) {
      throw new BusinessRuleException(
          ProblemCodes.OUTSIDE_BOOKING_WINDOW,
          "The latest start you can book is " + local(d, latest) + ".");
    }
  }

  static OpeningHours hoursOrClosed(JobPlan plan, DealershipView d) {
    DayOfWeek day = plan.localDate().getDayOfWeek();
    OpeningHours hours = d.openingHours().get(day);
    if (hours == null) {
      throw new BusinessRuleException(
          ProblemCodes.OUTSIDE_OPENING_HOURS, d.name() + " is closed on " + dayName(day) + ".");
    }
    return hours;
  }

  static String openDescription(DealershipView d, DayOfWeek day, OpeningHours hours) {
    return d.name()
        + " is open "
        + CLOCK.format(hours.opensAt())
        + "–"
        + CLOCK.format(hours.closesAt())
        + " on "
        + dayName(day);
  }

  static String clock(LocalTime time) {
    return CLOCK.format(time);
  }

  static void requireNotTerminal(AppointmentStatus status) {
    if (status.isTerminal()) {
      throw new InvalidTransitionException("The appointment is " + status + " and can't change.");
    }
  }

  static void requireBooked(AppointmentStatus status) {
    if (status != AppointmentStatus.BOOKED) {
      throw new InvalidTransitionException(
          "Only booked appointments can be changed here; this one is " + status + ".");
    }
  }

  static void requireLeadTime(DealershipView d, TimeRange currentPeriod, Instant now) {
    if (currentPeriod.start().isBefore(now.plus(CHANGE_CUTOFF))) {
      throw new BusinessRuleException(
          ProblemCodes.CHANGE_WINDOW_CLOSED,
          "Changes must be made at least 24 hours before the start at "
              + (d == null ? currentPeriod.start().toString() : local(d, currentPeriod.start()))
              + ".");
    }
  }

  private static String local(DealershipView d, Instant t) {
    return STAMP.format(t.atZone(d.zoneId())) + " " + d.zoneId().getId();
  }

  private static String dayName(DayOfWeek day) {
    return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s";
  }
}
