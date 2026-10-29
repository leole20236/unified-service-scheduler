package com.keyloop.scheduler.booking.domain;

import static com.keyloop.scheduler.booking.domain.AppointmentStatus.BOOKED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.CANCELLED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.CHECKED_IN;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.COMPLETED;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.IN_PROGRESS;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.NO_SHOW;
import static com.keyloop.scheduler.booking.domain.AppointmentStatus.ON_HOLD;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.api.InvalidTransitionException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class AppointmentStateMachineTest {

  /** Spec 6.2, verbatim: the only allowed transitions. */
  private static final Map<Action, Set<AppointmentStatus>> SPEC = new EnumMap<>(Action.class);

  private static final Map<Action, AppointmentStatus> TARGET = new EnumMap<>(Action.class);

  static {
    SPEC.put(Action.CHECK_IN, Set.of(BOOKED));
    SPEC.put(Action.START, Set.of(CHECKED_IN));
    SPEC.put(Action.HOLD, Set.of(IN_PROGRESS));
    SPEC.put(Action.RESUME, Set.of(ON_HOLD));
    SPEC.put(Action.COMPLETE, Set.of(IN_PROGRESS));
    SPEC.put(Action.NO_SHOW, Set.of(BOOKED));
    SPEC.put(Action.CANCEL, Set.of(BOOKED, CHECKED_IN));
    TARGET.put(Action.CHECK_IN, CHECKED_IN);
    TARGET.put(Action.START, IN_PROGRESS);
    TARGET.put(Action.HOLD, ON_HOLD);
    TARGET.put(Action.RESUME, IN_PROGRESS);
    TARGET.put(Action.COMPLETE, COMPLETED);
    TARGET.put(Action.NO_SHOW, NO_SHOW);
    TARGET.put(Action.CANCEL, CANCELLED);
  }

  static Stream<Arguments> everyActionAndStatus() {
    List<Arguments> all = new ArrayList<>();
    for (Action action : Action.values()) {
      for (AppointmentStatus status : AppointmentStatus.values()) {
        all.add(Arguments.of(action, status));
      }
    }
    return all.stream();
  }

  @ParameterizedTest(name = "{0} from {1}")
  @MethodSource("everyActionAndStatus")
  void allowedExactlyWhenListedInTheSpec(Action action, AppointmentStatus status) {
    boolean expected = SPEC.get(action).contains(status);

    assertThat(AppointmentStateMachine.allowedFrom(action).contains(status)).isEqualTo(expected);
    assertThat(AppointmentStateMachine.allows(action, status)).isEqualTo(expected);
    if (!expected) {
      InvalidTransitionException refusal = AppointmentStateMachine.refusal(action, status);
      assertThat(refusal.getMessage())
          .startsWith("Cannot " + action.verb() + " an appointment that is " + status + ";")
          .endsWith(".");
    }
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void targetPerSpec(Action action) {
    assertThat(AppointmentStateMachine.target(action)).isEqualTo(TARGET.get(action));
  }

  @Test
  void refusalTellsWhatToDoFirst() {
    assertThat(AppointmentStateMachine.refusal(Action.START, BOOKED).getMessage())
        .isEqualTo("Cannot start an appointment that is BOOKED; check it in first.");
    assertThat(AppointmentStateMachine.refusal(Action.COMPLETE, ON_HOLD).getMessage())
        .isEqualTo("Cannot complete an appointment that is ON_HOLD; resume it first.");
    assertThat(AppointmentStateMachine.refusal(Action.CANCEL, COMPLETED).getMessage())
        .isEqualTo("Cannot cancel an appointment that is COMPLETED; it can no longer change.");
  }

  @Test
  void pathSegmentsAreKebabCase() {
    assertThat(Stream.of(Action.values()).map(Action::pathSegment))
        .containsExactly("check-in", "start", "hold", "resume", "complete", "no-show", "cancel");
  }

  @Test
  void onlyCancelNoShowHoldResumeAndCompleteTouchReservations() {
    assertThat(Stream.of(Action.values()).filter(Action::touchesReservations))
        .containsExactly(
            Action.HOLD, Action.RESUME, Action.COMPLETE, Action.NO_SHOW, Action.CANCEL);
  }
}
