package com.keyloop.scheduler.common.api;

/** Problem HOLDS_UNAVAILABLE; clients retry after thirty seconds. */
public class HoldsUnavailableException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public HoldsUnavailableException(String detail) {
    super(ProblemCodes.HOLDS_UNAVAILABLE, detail);
  }

  @Override
  public Integer retryAfterSeconds() {
    return 30;
  }
}
