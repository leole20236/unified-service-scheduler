package com.keyloop.scheduler.common.api;

/** Problem VERSION_CONFLICT. */
public class VersionConflictException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public VersionConflictException(String detail) {
    super(ProblemCodes.VERSION_CONFLICT, detail);
  }
}
