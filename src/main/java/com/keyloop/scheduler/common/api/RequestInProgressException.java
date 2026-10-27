package com.keyloop.scheduler.common.api;

/** Problem REQUEST_IN_PROGRESS; clients retry after one second. */
public class RequestInProgressException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public RequestInProgressException(String detail) {
    super(ProblemCodes.REQUEST_IN_PROGRESS, detail);
  }

  @Override
  public Integer retryAfterSeconds() {
    return 1;
  }
}
