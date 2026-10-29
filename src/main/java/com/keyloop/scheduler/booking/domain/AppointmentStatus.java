package com.keyloop.scheduler.booking.domain;

/** Lifecycle status of an appointment. */
public enum AppointmentStatus {
  BOOKED,
  CHECKED_IN,
  IN_PROGRESS,
  ON_HOLD,
  COMPLETED,
  CANCELLED,
  NO_SHOW;

  /** Whether no further change is possible. */
  public boolean isTerminal() {
    return this == COMPLETED || this == CANCELLED || this == NO_SHOW;
  }
}
