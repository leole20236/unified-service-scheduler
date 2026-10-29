package com.keyloop.scheduler.booking.domain;

/** The endpoint a booking came through; it selects the rules and scopes idempotency keys. */
public enum BookingChannel {
  /** {@code POST /me/appointments}. */
  CUSTOMER("/me/appointments"),
  /** {@code POST /dealerships/{d}/appointments}. */
  DISPATCHER("/dealerships/{dealershipId}/appointments");

  private final String route;

  BookingChannel(String route) {
    this.route = route;
  }

  /** The route template, part of the idempotency request hash. */
  public String route() {
    return route;
  }

  /** The booking rules of this channel (spec 6.4). */
  public BookingPolicy policy() {
    return this == DISPATCHER ? new DispatcherBookingPolicy() : new CustomerBookingPolicy();
  }
}
