package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.InvalidTransitionException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.time.OpeningHoursCalendar;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Duration;
import java.time.Instant;

/** Dispatcher rules: walk-ins, start inside opening hours, end may overrun, no cutoff. */
public final class DispatcherBookingPolicy implements BookingPolicy {

  private static final Duration WALK_IN_GRACE = Duration.ofMinutes(15);

  @Override
  public Instant earliestStart(Instant now) {
    return now.minus(WALK_IN_GRACE);
  }

  @Override
  public Instant latestStart(Instant now) {
    return now.plus(PolicyChecks.MAX_AHEAD);
  }

  @Override
  public void checkNew(JobPlan plan, DealershipView d, Instant now) {
    PolicyChecks.startWindow(plan, d, earliestStart(now), latestStart(now));
    OpeningHours hours = PolicyChecks.hoursOrClosed(plan, d);
    TimeRange window = OpeningHoursCalendar.window(d, plan.localDate()).orElseThrow();
    Instant start = plan.period().start();
    if (start.isBefore(window.start()) || !start.isBefore(window.end())) {
      throw new BusinessRuleException(
          ProblemCodes.OUTSIDE_OPENING_HOURS,
          PolicyChecks.openDescription(d, plan.localDate().getDayOfWeek(), hours)
              + "; a job can't start at "
              + PolicyChecks.clock(plan.localStart().toLocalTime())
              + " "
              + d.zoneId().getId()
              + ".");
    }
  }

  @Override
  public void checkChange(
      AppointmentStatus status,
      TimeRange currentPeriod,
      JobPlan newPlan,
      DealershipView d,
      Instant now) {
    PolicyChecks.requireNotTerminal(status);
    if (status == AppointmentStatus.ON_HOLD) {
      throw new InvalidTransitionException(
          "This job is on hold; resume it before moving or resizing it.");
    }
    if (status == AppointmentStatus.IN_PROGRESS) {
      if (!newPlan.period().start().equals(currentPeriod.start())) {
        throw new BusinessRuleException(
            ProblemCodes.START_FIXED,
            "The start time can't change once work has started; only the end can move.");
      }
      return;
    }
    if (newPlan.period().start().equals(currentPeriod.start())) {
      // Rules about the start only apply to a new start: a job whose start has passed can still
      // change bay, technician or length.
      return;
    }
    checkNew(newPlan, d, now);
  }

  @Override
  public void checkCancel(AppointmentStatus status, TimeRange currentPeriod, Instant now) {
    PolicyChecks.requireNotTerminal(status);
  }
}
