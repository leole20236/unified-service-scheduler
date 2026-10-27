package com.keyloop.scheduler.common.api;

/** A 422 business-rule violation identified by one of the {@link ProblemCodes} 422 codes. */
public class BusinessRuleException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public BusinessRuleException(ProblemCodes code, String detail) {
    super(requireUnprocessable(code), detail);
  }

  private static ProblemCodes requireUnprocessable(ProblemCodes code) {
    if (code.status() != 422) {
      throw new IllegalArgumentException(code + " is not a 422 business rule code");
    }
    return code;
  }
}
