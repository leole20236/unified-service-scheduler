package com.keyloop.scheduler.common.api;

/** Problem PRECONDITION_REQUIRED. */
public class PreconditionRequiredException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public PreconditionRequiredException(String detail) {
    super(ProblemCodes.PRECONDITION_REQUIRED, detail);
  }
}
