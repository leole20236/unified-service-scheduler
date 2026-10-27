package com.keyloop.scheduler.common.api;

/** A 422 business-rule violation identified by one of the {@link ProblemCodes} 422 codes. */
public class BusinessRuleException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public BusinessRuleException(String code, String detail) {
    super(ProblemCodes.valueOf(code), detail);
  }
}
