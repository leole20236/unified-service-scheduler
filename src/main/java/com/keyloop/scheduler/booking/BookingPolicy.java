package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Instant;

/** Per-actor booking, change and cancel rules (spec 6.4). */
public interface BookingPolicy {

  /** Validates a new booking; throws an {@code ApiException} on violation. */
  void checkNew(JobPlan plan, DealershipView d, Instant now);

  /** Validates moving or resizing an existing appointment. */
  void checkChange(
      AppointmentStatus status,
      TimeRange currentPeriod,
      JobPlan newPlan,
      DealershipView d,
      Instant now);

  /** Validates cancelling an existing appointment. */
  void checkCancel(AppointmentStatus status, TimeRange currentPeriod, Instant now);

  /** The dispatcher policy for dispatchers, the customer policy otherwise. */
  static BookingPolicy forCaller(CallerContext caller) {
    return caller.has(Role.DISPATCHER)
        ? new DispatcherBookingPolicy()
        : new CustomerBookingPolicy();
  }
}
