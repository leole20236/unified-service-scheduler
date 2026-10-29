package com.keyloop.scheduler.booking.domain;

/** Why work on a job paused (spec 6.2). */
public enum HoldReason {
  /** Waiting for parts. */
  PARTS,
  /** Extra work found; waiting for the customer's go-ahead. */
  CUSTOMER_APPROVAL
}
