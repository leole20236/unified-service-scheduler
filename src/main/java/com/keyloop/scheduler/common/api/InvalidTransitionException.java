package com.keyloop.scheduler.common.api;

/** Problem INVALID_TRANSITION. */
public class InvalidTransitionException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public InvalidTransitionException(String detail) {
    super(ProblemCodes.INVALID_TRANSITION, detail);
  }
}
