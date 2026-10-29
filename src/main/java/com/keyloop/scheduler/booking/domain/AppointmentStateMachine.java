package com.keyloop.scheduler.booking.domain;

import static com.keyloop.scheduler.booking.domain.AppointmentStatus.BOOKED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.CANCELLED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.CHECKED_IN;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.COMPLETED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.IN_PROGRESS;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.NO_SHOW;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.ON_HOLD;

import com.keyloop.scheduler.common.api.InvalidTransitionException;
import java.util.Set;

/** The appointment lifecycle of spec 6.2: which action may run from which status, and to where. */
public final class AppointmentStateMachine {

  private AppointmentStateMachine() {}

  /** The statuses the action may start from. */
  public static Set<AppointmentStatus> allowedFrom(Action action) {
    return switch (action) {
      case CHECK_IN, NO_SHOW -> Set.of(BOOKED);
      case START -> Set.of(CHECKED_IN);
      case HOLD, COMPLETE -> Set.of(IN_PROGRESS);
      case RESUME -> Set.of(ON_HOLD);
      case CANCEL -> Set.of(BOOKED, CHECKED_IN);
    };
  }

  /** The status the action leads to. */
  public static AppointmentStatus target(Action action) {
    return switch (action) {
      case CHECK_IN -> CHECKED_IN;
      case START, RESUME -> IN_PROGRESS;
      case HOLD -> ON_HOLD;
      case COMPLETE -> COMPLETED;
      case NO_SHOW -> NO_SHOW;
      case CANCEL -> CANCELLED;
    };
  }

  /** Whether the action may run from the status. */
  public static boolean allows(Action action, AppointmentStatus status) {
    return allowedFrom(action).contains(status);
  }

  /**
   * The 409 for an action the status does not allow, e.g. "Cannot start an appointment that is
   * BOOKED; check it in first."
   */
  public static InvalidTransitionException refusal(Action action, AppointmentStatus status) {
    return new InvalidTransitionException(
        "Cannot "
            + action.verb()
            + " an appointment that is "
            + status
            + "; "
            + hint(action, status)
            + ".");
  }

  private static String hint(Action action, AppointmentStatus status) {
    if (status.isTerminal()) {
      return "it can no longer change";
    }
    return switch (action) {
      case CHECK_IN -> "it has already been checked in";
      case START -> status == BOOKED ? "check it in first" : startedHint(status);
      case HOLD, COMPLETE -> notStartedHint(status);
      case RESUME -> "only jobs on hold can be resumed";
      case NO_SHOW -> "the vehicle has already arrived";
      case CANCEL -> "work has already started";
    };
  }

  private static String startedHint(AppointmentStatus status) {
    return status == ON_HOLD ? "resume it instead" : "work is already in progress";
  }

  private static String notStartedHint(AppointmentStatus status) {
    return switch (status) {
      case BOOKED -> "check it in and start it first";
      case ON_HOLD -> "resume it first";
      default -> "start it first";
    };
  }
}
