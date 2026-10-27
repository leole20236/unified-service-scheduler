package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.VersionConflictException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Explains why a conditional appointment UPDATE changed no row: not the caller's (404), a stale
 * version (412), a status or time rule (409 / 422 from the policy) or, when none of these applies
 * any more, a concurrent change (412).
 */
final class ChangeDiagnosis {

  private ChangeDiagnosis() {}

  static NotFoundException notFound(UUID appointmentId) {
    return new NotFoundException("Appointment " + appointmentId + " was not found.");
  }

  static VersionConflictException stale(UUID appointmentId) {
    return new VersionConflictException(
        "Appointment " + appointmentId + " was changed by someone else; reload it and try again.");
  }

  /** The problem for a refused update, read from the appointment's current state. */
  static ApiException of(
      UUID appointmentId,
      UUID customerId,
      OptionalLong expectedVersion,
      Optional<AppointmentState> current,
      Instant now) {
    AppointmentState state = current.filter(s -> s.customerId().equals(customerId)).orElse(null);
    if (state == null) {
      return notFound(appointmentId);
    }
    if (expectedVersion.isPresent() && expectedVersion.getAsLong() != state.version()) {
      return stale(appointmentId);
    }
    try {
      BookingChannel.CUSTOMER.policy().checkCancel(state.status(), state.period(), now);
    } catch (ApiException e) {
      return e;
    }
    return stale(appointmentId);
  }
}
