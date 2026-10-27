package com.keyloop.scheduler.hold;

/**
 * The ledger of a dealership-day could not be rebuilt from the primary (every attempt was overtaken
 * by concurrent writes), so it must not be used for this call: holds answer 503 and availability
 * reads the database.
 */
public class LedgerUnavailableException extends RuntimeException {

  /** Creates the exception. */
  public LedgerUnavailableException(String message) {
    super(message);
  }
}
