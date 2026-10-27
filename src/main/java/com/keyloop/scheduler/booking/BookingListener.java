package com.keyloop.scheduler.booking;

/**
 * Notified after a booking transaction has committed (never inside it). The default bean does
 * nothing; the Redis slot ledger plugs in here.
 */
@FunctionalInterface
public interface BookingListener {

  /** Called once per committed booking. */
  void afterCommit(AppointmentCommitted event);
}
