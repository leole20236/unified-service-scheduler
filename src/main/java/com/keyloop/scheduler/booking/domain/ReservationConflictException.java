package com.keyloop.scheduler.booking.domain;

import java.util.UUID;

/** The database refused a reservation (SQLSTATE 23P01): the resource is taken for that period. */
public class ReservationConflictException extends RuntimeException {

  private final UUID resourceId;

  /** Creates the signal for the resource whose insert was refused. */
  public ReservationConflictException(UUID resourceId) {
    super("Resource " + resourceId + " is already reserved for an overlapping period");
    this.resourceId = resourceId;
  }

  /** The bay or technician that is taken. */
  public UUID resourceId() {
    return resourceId;
  }
}
