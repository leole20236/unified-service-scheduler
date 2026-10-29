package com.keyloop.scheduler.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingPolicyTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final DealershipView RIVERSIDE = CatalogFixtures.riverside();
  private static final BookingPolicy CUSTOMER = new CustomerBookingPolicy();
  private static final BookingPolicy DISPATCHER = new DispatcherBookingPolicy();

  private static JobPlan plan(DealershipView d, String start, UUID... services) {
    return JobPlanner.plan(
        CatalogFixtures.catalog(d), List.of(services), OffsetDateTime.parse(start));
  }

  private static JobPlan oil(String start) {
    return plan(RIVERSIDE, start, Fixtures.OIL_CHANGE);
  }

  private static JobPlan twoHours(String start) {
    return plan(RIVERSIDE, start, Fixtures.OIL_CHANGE, Fixtures.BRAKE_INSPECTION);
  }

  private static TimeRange range(String start, int minutes) {
    Instant s = Instant.parse(start);
    return new TimeRange(s, s.plusSeconds(minutes * 60L));
  }

  private static void assertCode(Runnable call, ProblemCodes code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  // customer

  @Test
  void customerValidBookingAllowed() {
    assertThatCode(() -> CUSTOMER.checkNew(oil("2026-11-02T09:00:00Z"), RIVERSIDE, NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void startLessThanTwoHoursAheadRejected() {
    assertCode(
        () -> CUSTOMER.checkNew(oil("2026-11-01T11:59:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void startMoreThanSixtyDaysRejected() {
    assertCode(
        () -> CUSTOMER.checkNew(oil("2027-01-05T09:00:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void jobPastClosingRejected() {
    assertThatThrownBy(() -> CUSTOMER.checkNew(twoHours("2026-11-02T17:00:00Z"), RIVERSIDE, NOW))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.OUTSIDE_OPENING_HOURS);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "Riverside Motors is open 08:00–18:00 on Mondays; a 120-minute job"
                          + " starting at 17:00 would end after closing.");
            });
  }

  @Test
  void jobBeforeOpeningRejected() {
    assertCode(
        () -> CUSTOMER.checkNew(oil("2026-11-02T07:00:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void closedDayRejected() {
    assertThatThrownBy(() -> CUSTOMER.checkNew(oil("2026-11-08T10:00:00Z"), RIVERSIDE, NOW))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.OUTSIDE_OPENING_HOURS);
              assertThat(e.getMessage()).contains("closed on Sundays");
            });
  }

  @Test
  void cancelWithin24hRejected() {
    assertCode(
        () ->
            CUSTOMER.checkCancel(AppointmentStatus.BOOKED, range("2026-11-02T09:00:00Z", 60), NOW),
        ProblemCodes.CHANGE_WINDOW_CLOSED);
  }

  @Test
  void cancelAtExactly24hAllowed() {
    assertThatCode(
            () ->
                CUSTOMER.checkCancel(
                    AppointmentStatus.BOOKED, range("2026-11-02T10:00:00Z", 60), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void customerCancelOfCheckedInRejected() {
    assertCode(
        () ->
            CUSTOMER.checkCancel(
                AppointmentStatus.CHECKED_IN, range("2026-11-05T09:00:00Z", 60), NOW),
        ProblemCodes.INVALID_TRANSITION);
  }

  @Test
  void changeOfCheckedInRejected() {
    assertCode(
        () ->
            CUSTOMER.checkChange(
                AppointmentStatus.CHECKED_IN,
                range("2026-11-05T09:00:00Z", 60),
                oil("2026-11-05T10:00:00Z"),
                RIVERSIDE,
                NOW),
        ProblemCodes.INVALID_TRANSITION);
  }

  @Test
  void changeWithin24hRejected() {
    assertCode(
        () ->
            CUSTOMER.checkChange(
                AppointmentStatus.BOOKED,
                range("2026-11-02T09:00:00Z", 60),
                oil("2026-11-05T10:00:00Z"),
                RIVERSIDE,
                NOW),
        ProblemCodes.CHANGE_WINDOW_CLOSED);
  }

  @Test
  void customerChangeValidatesNewPlan() {
    assertCode(
        () ->
            CUSTOMER.checkChange(
                AppointmentStatus.BOOKED,
                range("2026-11-05T09:00:00Z", 60),
                oil("2026-11-08T10:00:00Z"),
                RIVERSIDE,
                NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
    assertThatCode(
            () ->
                CUSTOMER.checkChange(
                    AppointmentStatus.BOOKED,
                    range("2026-11-05T09:00:00Z", 60),
                    oil("2026-11-05T10:00:00Z"),
                    RIVERSIDE,
                    NOW))
        .doesNotThrowAnyException();
  }

  // dispatcher

  @Test
  void walkInFifteenMinutesAgoAllowed() {
    assertThatCode(
            () ->
                DISPATCHER.checkNew(
                    oil("2026-11-02T08:00:00Z"), RIVERSIDE, Instant.parse("2026-11-02T08:15:00Z")))
        .doesNotThrowAnyException();
  }

  @Test
  void sixteenMinutesAgoRejected() {
    assertCode(
        () ->
            DISPATCHER.checkNew(
                oil("2026-11-02T08:00:00Z"), RIVERSIDE, Instant.parse("2026-11-02T08:16:00Z")),
        ProblemCodes.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void dispatcherMoreThanSixtyDaysRejected() {
    assertCode(
        () -> DISPATCHER.checkNew(oil("2027-01-05T09:00:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void endPastClosingAllowed() {
    assertThatCode(() -> DISPATCHER.checkNew(twoHours("2026-11-02T17:00:00Z"), RIVERSIDE, NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void startBeforeOpeningRejected() {
    assertCode(
        () -> DISPATCHER.checkNew(oil("2026-11-02T07:30:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void startAtClosingRejected() {
    assertCode(
        () -> DISPATCHER.checkNew(oil("2026-11-02T18:00:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void dispatcherClosedDayRejected() {
    assertCode(
        () -> DISPATCHER.checkNew(oil("2026-11-08T10:00:00Z"), RIVERSIDE, NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void inProgressStartChangeRejected() {
    assertCode(
        () ->
            DISPATCHER.checkChange(
                AppointmentStatus.IN_PROGRESS,
                range("2026-11-01T09:00:00Z", 60),
                oil("2026-11-02T09:30:00Z"),
                RIVERSIDE,
                NOW),
        ProblemCodes.START_FIXED);
  }

  @Test
  void onHoldAnyChangeRejected() {
    // start change, end change, and same-plan (e.g. technician change) all need a resume first
    for (JobPlan newPlan :
        List.of(
            oil("2026-11-02T09:30:00Z"),
            twoHours("2026-11-01T09:00:00Z"),
            oil("2026-11-01T09:00:00Z"))) {
      assertThatThrownBy(
              () ->
                  DISPATCHER.checkChange(
                      AppointmentStatus.ON_HOLD,
                      range("2026-11-01T09:00:00Z", 45),
                      newPlan,
                      RIVERSIDE,
                      NOW))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.code()).isEqualTo(ProblemCodes.INVALID_TRANSITION);
                assertThat(e.getMessage())
                    .isEqualTo("This job is on hold; resume it before moving or resizing it.");
              });
    }
  }

  @Test
  void leadTimeDetailIsRelativeForChangeAndCancel() {
    String hours =
        "This appointment starts in 23 hours; online changes close 24 hours before."
            + " Please call the dealership.";
    assertThatThrownBy(
            () ->
                CUSTOMER.checkChange(
                    AppointmentStatus.BOOKED,
                    range("2026-11-02T09:00:00Z", 60),
                    oil("2026-11-05T10:00:00Z"),
                    RIVERSIDE,
                    NOW))
        .hasMessage(hours);
    assertThatThrownBy(
            () ->
                CUSTOMER.checkCancel(
                    AppointmentStatus.BOOKED, range("2026-11-02T09:00:00Z", 60), NOW))
        .hasMessage(hours);
    assertThatThrownBy(
            () ->
                CUSTOMER.checkCancel(
                    AppointmentStatus.BOOKED, range("2026-11-01T10:30:00Z", 60), NOW))
        .hasMessageContaining("starts in less than 1 hour;");
    assertThatThrownBy(
            () ->
                CUSTOMER.checkCancel(
                    AppointmentStatus.BOOKED, range("2026-11-01T11:30:00Z", 60), NOW))
        .hasMessageContaining("starts in 1 hour;");
  }

  @Test
  void inProgressExtendAllowed() {
    assertThatCode(
            () ->
                DISPATCHER.checkChange(
                    AppointmentStatus.IN_PROGRESS,
                    range("2026-11-01T09:00:00Z", 45),
                    twoHours("2026-11-01T09:00:00Z"),
                    RIVERSIDE,
                    NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void dispatcherChangeKeepingPassedStartSkipsTheStartRules() {
    // started an hour ago (outside the 15 minute walk-in grace), only the length changes
    assertThatCode(
            () ->
                DISPATCHER.checkChange(
                    AppointmentStatus.CHECKED_IN,
                    range("2026-11-01T09:00:00Z", 45),
                    twoHours("2026-11-01T09:00:00Z"),
                    RIVERSIDE,
                    NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void checkedInMoveValidatesNewPlan() {
    assertThatCode(
            () ->
                DISPATCHER.checkChange(
                    AppointmentStatus.CHECKED_IN,
                    range("2026-11-02T09:00:00Z", 45),
                    oil("2026-11-02T10:00:00Z"),
                    RIVERSIDE,
                    NOW))
        .doesNotThrowAnyException();
    assertCode(
        () ->
            DISPATCHER.checkChange(
                AppointmentStatus.CHECKED_IN,
                range("2026-11-02T09:00:00Z", 45),
                oil("2026-11-08T10:00:00Z"),
                RIVERSIDE,
                NOW),
        ProblemCodes.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void dispatcherCannotChangeOrCancelTerminal() {
    for (AppointmentStatus status :
        List.of(
            AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)) {
      assertCode(
          () ->
              DISPATCHER.checkChange(
                  status,
                  range("2026-11-02T09:00:00Z", 45),
                  oil("2026-11-02T10:00:00Z"),
                  RIVERSIDE,
                  NOW),
          ProblemCodes.INVALID_TRANSITION);
      assertCode(
          () -> DISPATCHER.checkCancel(status, range("2026-11-02T09:00:00Z", 45), NOW),
          ProblemCodes.INVALID_TRANSITION);
    }
  }

  @Test
  void dispatcherCancelHasNoCutoff() {
    assertThatCode(
            () ->
                DISPATCHER.checkCancel(
                    AppointmentStatus.IN_PROGRESS, range("2026-11-01T09:30:00Z", 60), NOW))
        .doesNotThrowAnyException();
  }

  // factory and status

  @Test
  void forCallerPicksPolicyByRole() {
    CallerContext dispatcher =
        new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of());
    CallerContext customer = new CallerContext(UUID.randomUUID(), Set.of(Role.CUSTOMER), Set.of());
    assertThat(BookingPolicy.forCaller(dispatcher)).isInstanceOf(DispatcherBookingPolicy.class);
    assertThat(BookingPolicy.forCaller(customer)).isInstanceOf(CustomerBookingPolicy.class);
  }

  @Test
  void terminalStatuses() {
    assertThat(AppointmentStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(AppointmentStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(AppointmentStatus.NO_SHOW.isTerminal()).isTrue();
    assertThat(AppointmentStatus.BOOKED.isTerminal()).isFalse();
    assertThat(AppointmentStatus.ON_HOLD.isTerminal()).isFalse();
  }

  @Test
  void latestStartIsSixtyDaysAheadForBothActors() {
    Instant sixtyDays = Instant.parse("2026-12-31T10:00:00Z");

    assertThat(CUSTOMER.latestStart(NOW)).isEqualTo(sixtyDays);
    assertThat(DISPATCHER.latestStart(NOW)).isEqualTo(sixtyDays);
  }
}
