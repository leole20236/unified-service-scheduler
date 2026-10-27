package com.keyloop.scheduler.common.api;

/** Problem UNAUTHENTICATED. */
public class UnauthenticatedException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public UnauthenticatedException(String detail) {
    super(ProblemCodes.UNAUTHENTICATED, detail);
  }
}
