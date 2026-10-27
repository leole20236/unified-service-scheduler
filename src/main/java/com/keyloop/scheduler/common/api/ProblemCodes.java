package com.keyloop.scheduler.common.api;

/**
 * Every problem {@code code} with its fixed HTTP status and {@code title} (spec 14.6). {@link
 * #MISDIRECTED_REGION} and {@link #RATE_LIMITED} are raised by the gateway only.
 */
public enum ProblemCodes {
  VALIDATION_ERROR(400, "Request is invalid"),
  UNAUTHENTICATED(401, "Authentication required"),
  FORBIDDEN(403, "Not allowed"),
  NOT_FOUND(404, "Resource not found"),
  METHOD_NOT_ALLOWED(405, "Method not allowed"),
  NOT_ACCEPTABLE(406, "Not acceptable"),
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
  SLOT_UNAVAILABLE(409, "Requested time is not available"),
  SLOT_HELD(409, "Time is held by a customer"),
  INVALID_TRANSITION(409, "Action not allowed in current status"),
  REQUEST_IN_PROGRESS(409, "Duplicate request in progress"),
  VEHICLE_ALREADY_EXISTS(409, "Vehicle already registered"),
  VERSION_CONFLICT(412, "Appointment was changed by someone else"),
  MISDIRECTED_REGION(421, "Wrong region"),
  OUTSIDE_BOOKING_WINDOW(422, "Time is outside the booking window"),
  OUTSIDE_OPENING_HOURS(422, "Outside opening hours"),
  CHANGE_WINDOW_CLOSED(422, "Too late to change"),
  SERVICE_NOT_OFFERED(422, "Service not offered here"),
  TECHNICIAN_NOT_QUALIFIED(422, "Technician lacks required skills"),
  START_FIXED(422, "Start time can no longer change"),
  NO_SHOW_TOO_EARLY(422, "Too early to mark no-show"),
  HOLD_MISMATCH(422, "Hold does not match the request"),
  IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused"),
  PRECONDITION_REQUIRED(428, "Precondition header required"),
  RATE_LIMITED(429, "Too many requests"),
  INTERNAL_ERROR(500, "Unexpected error"),
  HOLDS_UNAVAILABLE(503, "Slot holds temporarily unavailable"),
  SERVICE_UNAVAILABLE(503, "Service temporarily unavailable");

  private final int status;
  private final String title;

  ProblemCodes(int status, String title) {
    this.status = status;
    this.title = title;
  }

  /** HTTP status. */
  public int status() {
    return status;
  }

  /** Fixed human title. */
  public String title() {
    return title;
  }

  /** Problem type URI, e.g. {@code .../problems/slot-unavailable}. */
  public String type() {
    return "https://scheduler.example/problems/"
        + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
