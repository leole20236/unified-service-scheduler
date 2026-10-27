package com.keyloop.scheduler.common.api;

/** Problem FORBIDDEN. */
public class ForbiddenException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public ForbiddenException(String detail) {
    super(ProblemCodes.FORBIDDEN, detail);
  }
}
