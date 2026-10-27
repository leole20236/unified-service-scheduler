package com.keyloop.scheduler.booking;

/**
 * Notified after a booking transaction has committed (never inside it). The only bean is the Redis
 * slot ledger's write-through ({@code hold.LedgerBookingListener}).
 */
@FunctionalInterface
public interface BookingListener {

  /** Called once per committed booking. */
  void afterCommit(AppointmentCommitted event);
}
