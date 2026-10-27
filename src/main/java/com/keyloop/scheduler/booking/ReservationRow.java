package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.UUID;

/** One reservation to write: a bay or technician and the period it is occupied. */
public record ReservationRow(UUID resourceId, String resourceType, TimeRange period) {

  /** {@code resourceType} of a service bay. */
  public static final String BAY = "BAY";

  /** {@code resourceType} of a technician. */
  public static final String TECHNICIAN = "TECHNICIAN";
}
