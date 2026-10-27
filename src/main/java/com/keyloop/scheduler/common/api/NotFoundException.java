package com.keyloop.scheduler.common.api;

/** Problem NOT_FOUND. */
public class NotFoundException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public NotFoundException(String detail) {
    super(ProblemCodes.NOT_FOUND, detail);
  }
}
