package com.keyloop.scheduler.common.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A failure that renders as an RFC 9457 problem with a fixed {@link ProblemCodes} code. */
public abstract class ApiException extends RuntimeException {

  private final ProblemCodes problem;

  /** Creates the problem with its human-readable detail. */
  protected ApiException(ProblemCodes problem, String detail) {
    super(detail);
    this.problem = problem;
  }

  /** HTTP status of the problem. */
  public HttpStatus status() {
    return HttpStatus.valueOf(problem.status());
  }

  /** Stable machine-readable code. */
  public ProblemCodes code() {
    return problem;
  }

  /** Extra problem properties (for example {@code alternatives}). */
  public Map<String, Object> extras() {
    return Map.of();
  }

  /** Seconds for the {@code Retry-After} header, or null when none applies. */
  public Integer retryAfterSeconds() {
    return null;
  }
}
