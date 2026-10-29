package com.keyloop.scheduler.booking.create;

/**
 * Another request with the same user and Idempotency-Key committed first (unique_violation on the
 * key row); the current transaction must roll back and the stored response be replayed.
 */
public class DuplicateRequestException extends RuntimeException {

  /** Creates the signal. */
  public DuplicateRequestException() {
    super("Idempotency key already used by a committed request");
  }
}
