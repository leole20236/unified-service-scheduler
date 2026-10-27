package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.time.OpeningHoursCalendar;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Instant;

/** Customer rules: 2h to 60 days ahead, whole job in opening hours, own BOOKED jobs, 24h cutoff. */
public final class CustomerBookingPolicy implements BookingPolicy {

  @Override
  public Instant earliestStart(Instant now) {
    return now.plus(PolicyChecks.CUSTOMER_LEAD);
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
    TimeRange period = plan.period();
    if (period.start().isBefore(window.start()) || period.end().isAfter(window.end())) {
      throw new BusinessRuleException(
          ProblemCodes.OUTSIDE_OPENING_HOURS,
          PolicyChecks.openDescription(d, plan.localDate().getDayOfWeek(), hours)
              + "; a "
              + plan.durationMinutes()
              + "-minute job starting at "
              + PolicyChecks.clock(plan.localStart().toLocalTime())
              + (period.start().isBefore(window.start())
                  ? " would start before opening."
                  : " would end after closing."));
    }
  }

  @Override
  public void checkChange(
      AppointmentStatus status,
      TimeRange currentPeriod,
      JobPlan newPlan,
      DealershipView d,
      Instant now) {
    PolicyChecks.requireBooked(status);
    PolicyChecks.requireLeadTime(currentPeriod, now);
    checkNew(newPlan, d, now);
  }

  @Override
  public void checkCancel(AppointmentStatus status, TimeRange currentPeriod, Instant now) {
    PolicyChecks.requireBooked(status);
    PolicyChecks.requireLeadTime(currentPeriod, now);
  }
}
