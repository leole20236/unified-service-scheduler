package com.keyloop.scheduler.common.api;

import java.util.List;
import java.util.Map;

/** Problem VALIDATION_ERROR with the offending fields in {@code errors[]}. */
public class ValidationException extends ApiException {

  private final List<Map<String, String>> errors;

  /** Creates the problem with its human-readable detail. */
  public ValidationException(String detail, List<Map<String, String>> errors) {
    super(ProblemCodes.VALIDATION_ERROR, detail);
    this.errors = errors;
  }

  @Override
  public Map<String, Object> extras() {
    return Map.of("errors", errors);
  }
}
