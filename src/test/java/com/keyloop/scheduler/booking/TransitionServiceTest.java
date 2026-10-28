package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.InvalidTransitionException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotHeldException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.api.VersionConflictException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionOperations;

class TransitionServiceTest {

  /** Hudson: New York, 10 minute bay buffer. 15:00Z = 10:00 local in November. */
  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");

  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");
  private static final UUID BAY_RESERVATION =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b1");
  private static final UUID TECH_RESERVATION =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b2");
  private static final TimeRange PLANNED =
      new TimeRange(Instant.parse("2026-11-04T14:00:00Z"), Instant.parse("2026-11-04T15:15:00Z"));
  private static final Duration BUFFER = Duration.ofMinutes(10);
  private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

  private final CatalogService catalogs = mock(CatalogService.class);
  private final AppointmentRepository appointments = mock(AppointmentRepository.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final ResourceLocks locks = mock(ResourceLocks.class);
  private final AppointmentReadRepository reads = mock(AppointmentReadRepository.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final SlotLedger ledger = mock(SlotLedger.class);
  private final DbClock clock = mock(DbClock.class);
  private final AppointmentView view = mock(AppointmentView.class);
  private final CallerContext dispatcher =
      new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of(Fixtures.HUDSON));

  private TransitionService service() {
    return new TransitionService(
        catalogs,
        appointments,
        reservations,
        locks,
        reads,
        outbox,
        ledger,
        clock,
        TransactionOperations.withoutTransaction());
  }

  private static AppointmentState state(AppointmentStatus status, long version) {
    return new AppointmentState(
        APPOINTMENT,
        Fixtures.ALICE,
        Fixtures.HUDSON,
        status,
        version,
        PLANNED,
        Fixtures.HUDSON_BAY_1,
        Fixtures.JO);
  }

  private static ResourceCatalog catalog() {
    return new ResourceCatalog(
        CatalogFixtures.hudson(),
        List.of(
            CatalogFixtures.OIL_CHANGE,
            CatalogFixtures.BRAKE_INSPECTION,
            CatalogFixtures.DIAGNOSTIC),
        List.of(
            new BayView(Fixtures.HUDSON_BAY_1, "Bay 1"),
            new BayView(Fixtures.HUDSON_BAY_2, "Bay 2")),
        List.of(
            new TechnicianView(Fixtures.JO, "Jo", Set.of(Fixtures.OIL, Fixtures.BRAKES)),
            new TechnicianView(Fixtures.KIM, "Kim", Set.of(Fixtures.OIL))));
  }

  @BeforeEach
  void stub() {
    when(clock.now()).thenReturn(NOW);
    when(catalogs.get(Fixtures.HUDSON)).thenReturn(catalog());
    when(reads.find(APPOINTMENT)).thenReturn(Optional.of(view));
    when(appointments.serviceTypeIds(APPOINTMENT)).thenReturn(List.of(Fixtures.BRAKE_INSPECTION));
    when(appointments.progress(APPOINTMENT))
        .thenReturn(Optional.of(new AppointmentProgress(75, 0)));
  }

  private void given(AppointmentStatus status, long version) {
    when(appointments.findState(APPOINTMENT)).thenReturn(Optional.of(state(status, version)));
  }

  private void transitionsTo(long newVersion, TimeRange period) {
    when(appointments.transition(eq(APPOINTMENT), eq(Fixtures.HUDSON), anyLong(), any()))
        .thenReturn(Optional.of(new Transitioned(newVersion, period)));
  }

  private StatusChange capturedChange() {
    ArgumentCaptor<StatusChange> change = ArgumentCaptor.forClass(StatusChange.class);
    verify(appointments)
        .transition(eq(APPOINTMENT), eq(Fixtures.HUDSON), anyLong(), change.capture());
    return change.getValue();
  }

  private Collection<UUID> lockedResources() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
    verify(locks).lockAll(locked.capture());
    return locked.getValue();
  }

  private Object event(String type) {
    ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            eq("Appointment"),
            eq(APPOINTMENT),
            eq(Fixtures.HUDSON),
            eq(type),
            anyLong(),
            data.capture());
    return data.getValue();
  }

  private AppointmentView apply(Action action, Object body) {
    return service()
        .apply(dispatcher, Fixtures.HUDSON, APPOINTMENT, action, body, OptionalLong.empty());
  }

  @Test
  void holdAddsTheStintAndEndsOnlyTheTechnicianReservation() {
    given(AppointmentStatus.IN_PROGRESS, 3);
    transitionsTo(4, PLANNED);
    TimeRange worked = new TimeRange(PLANNED.start(), NOW);
    when(reservations.truncate(APPOINTMENT, ReservationRow.TECHNICIAN, Duration.ZERO))
        .thenReturn(
            List.of(
                new TruncatedReservation(
                    TECH_RESERVATION, Fixtures.JO, ReservationRow.TECHNICIAN, worked)));

    AppointmentView result = apply(Action.HOLD, new HoldActionRequest(HoldReason.PARTS, false));

    assertThat(result).isSameAs(view);
    StatusChange change = capturedChange();
    assertThat(change.to()).isEqualTo(AppointmentStatus.ON_HOLD);
    assertThat(change.from()).containsExactly(AppointmentStatus.IN_PROGRESS);
    assertThat(change.addStint()).isTrue();
    assertThat(change.holdReason()).isEqualTo(HoldReason.PARTS);
    assertThat(change.endNowIfEarlier()).isFalse();
    assertThat(lockedResources()).containsExactlyInAnyOrder(Fixtures.HUDSON_BAY_1, Fixtures.JO);
    verify(reservations).truncate(APPOINTMENT, ReservationRow.TECHNICIAN, Duration.ZERO);
    verify(reservations, never()).truncate(eq(APPOINTMENT), eq(ReservationRow.BAY), any());
    AppointmentStatusChangedEvent event =
        (AppointmentStatusChangedEvent) event("AppointmentStatusChanged");
    assertThat(event.from()).isEqualTo("IN_PROGRESS");
    assertThat(event.to()).isEqualTo("ON_HOLD");
    assertThat(event.holdReason()).isEqualTo("PARTS");
    verify(outbox).append(any(), any(), any(), eq("AppointmentStatusChanged"), eq(4L), any());
    verify(ledger)
        .applyCommitted(
            Fixtures.HUDSON,
            DAY,
            List.of(TECH_RESERVATION),
            Map.of(
                TECH_RESERVATION,
                new ReservationRow(Fixtures.JO, ReservationRow.TECHNICIAN, worked)));
  }

  @Test
  void holdWithReleaseBayEndsBothAndDeactivatedRowsOnlyLeaveTheLedger() {
    given(AppointmentStatus.IN_PROGRESS, 3);
    transitionsTo(4, PLANNED);
    when(reservations.truncate(APPOINTMENT, ReservationRow.TECHNICIAN, Duration.ZERO))
        .thenReturn(
            List.of(
                new TruncatedReservation(
                    TECH_RESERVATION, Fixtures.JO, ReservationRow.TECHNICIAN, null)));
    when(reservations.truncate(APPOINTMENT, ReservationRow.BAY, Duration.ZERO))
        .thenReturn(
            List.of(
                new TruncatedReservation(
                    BAY_RESERVATION, Fixtures.HUDSON_BAY_1, ReservationRow.BAY, null)));

    apply(Action.HOLD, new HoldActionRequest(HoldReason.CUSTOMER_APPROVAL, true));

    verify(reservations).truncate(APPOINTMENT, ReservationRow.BAY, Duration.ZERO);
    verify(ledger)
        .applyCommitted(Fixtures.HUDSON, DAY, List.of(TECH_RESERVATION, BAY_RESERVATION), Map.of());
  }

  @Test
  void holdNeedsReason() {
    given(AppointmentStatus.IN_PROGRESS, 3);

    assertThatThrownBy(() -> apply(Action.HOLD, null)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> apply(Action.HOLD, new HoldActionRequest(null, false)))
        .isInstanceOf(ValidationException.class);
    verify(appointments, never()).transition(any(), any(), anyLong(), any());
  }

  @Test
  void resumeReservesPlannedMinusWorkedFromResumeAtWithBayBuffer() {
    given(AppointmentStatus.ON_HOLD, 5);
    when(appointments.progress(APPOINTMENT))
        .thenReturn(Optional.of(new AppointmentProgress(75, 30)));
    Instant resumeAt = NOW.plus(Duration.ofMinutes(30));
    TimeRange remaining = new TimeRange(resumeAt, resumeAt.plus(Duration.ofMinutes(45)));
    transitionsTo(6, new TimeRange(PLANNED.start(), remaining.end()));
    when(reservations.deactivate(APPOINTMENT)).thenReturn(List.of(BAY_RESERVATION));
    UUID newBay = UUID.randomUUID();
    UUID newTech = UUID.randomUUID();
    when(reservations.insertAll(eq(APPOINTMENT), eq(Fixtures.HUDSON), any()))
        .thenAnswer(
            inv -> {
              List<ReservationRow> rows = inv.getArgument(2);
              return Map.of(newBay, rows.get(0), newTech, rows.get(1));
            });

    apply(
        Action.RESUME,
        new ResumeActionRequest(
            OffsetDateTime.ofInstant(resumeAt, ZoneOffset.UTC), null, null, false));

    StatusChange change = capturedChange();
    assertThat(change.to()).isEqualTo(AppointmentStatus.IN_PROGRESS);
    assertThat(change.resume().period()).isEqualTo(remaining);
    assertThat(change.resume().technicianId()).isEqualTo(Fixtures.JO);
    assertThat(change.resume().serviceBayId()).isEqualTo(Fixtures.HUDSON_BAY_1);
    verify(reservations).deactivate(APPOINTMENT);
    verify(reservations)
        .insertAll(
            APPOINTMENT,
            Fixtures.HUDSON,
            List.of(
                new ReservationRow(
                    Fixtures.HUDSON_BAY_1, ReservationRow.BAY, remaining.extendEnd(BUFFER)),
                new ReservationRow(Fixtures.JO, ReservationRow.TECHNICIAN, remaining)));
    verify(ledger)
        .applyCommitted(eq(Fixtures.HUDSON), eq(DAY), eq(List.of(BAY_RESERVATION)), anyMap());
    AppointmentStatusChangedEvent event =
        (AppointmentStatusChangedEvent) event("AppointmentStatusChanged");
    assertThat(event.from()).isEqualTo("ON_HOLD");
    assertThat(event.end()).isEqualTo(remaining.end());
  }

  @Test
  void resumeDefaultsToNowFloorsRemainingAtFiveMinutesAndMayChangeResources() {
    given(AppointmentStatus.ON_HOLD, 5);
    when(appointments.progress(APPOINTMENT))
        .thenReturn(Optional.of(new AppointmentProgress(75, 90)));
    TimeRange remaining = new TimeRange(NOW, NOW.plus(Duration.ofMinutes(5)));
    transitionsTo(6, new TimeRange(PLANNED.start(), remaining.end()));
    when(reservations.insertAll(any(), any(), any())).thenReturn(Map.of());

    apply(Action.RESUME, new ResumeActionRequest(null, Fixtures.JO, Fixtures.HUDSON_BAY_2, false));

    StatusChange change = capturedChange();
    assertThat(change.resume().period()).isEqualTo(remaining);
    assertThat(change.resume().serviceBayId()).isEqualTo(Fixtures.HUDSON_BAY_2);
    assertThat(lockedResources())
        .containsExactlyInAnyOrder(
            Fixtures.HUDSON_BAY_1, Fixtures.JO, Fixtures.HUDSON_BAY_2, Fixtures.JO);
  }

  @Test
  void resumeWithoutBodyStartsNow() {
    given(AppointmentStatus.ON_HOLD, 5);
    transitionsTo(6, PLANNED);
    when(reservations.insertAll(any(), any(), any())).thenReturn(Map.of());

    apply(Action.RESUME, null);

    assertThat(capturedChange().resume().period())
        .isEqualTo(new TimeRange(NOW, NOW.plus(Duration.ofMinutes(75))));
  }

  @Test
  void resumeOntoUnqualifiedTechnicianIs422() {
    given(AppointmentStatus.ON_HOLD, 5);

    assertThatThrownBy(
            () -> apply(Action.RESUME, new ResumeActionRequest(null, Fixtures.KIM, null, false)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).code())
                  .isEqualTo(ProblemCodes.TECHNICIAN_NOT_QUALIFIED);
              assertThat(e.getMessage())
                  .isEqualTo("Kim does not have the BRAKES skill required by Brake inspection.");
            });
    verify(locks, never()).lockAll(any());
  }

  @Test
  void resumeOntoUnknownResourcesIs404() {
    given(AppointmentStatus.ON_HOLD, 5);

    assertThatThrownBy(
            () ->
                apply(Action.RESUME, new ResumeActionRequest(null, UUID.randomUUID(), null, false)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                apply(Action.RESUME, new ResumeActionRequest(null, null, UUID.randomUUID(), false)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void resumeMoreThanFifteenMinutesAgoOrBeyondSixtyDaysIs400() {
    given(AppointmentStatus.ON_HOLD, 5);
    OffsetDateTime past =
        OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(16)), ZoneOffset.UTC);
    OffsetDateTime far = OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(61)), ZoneOffset.UTC);

    assertThatThrownBy(() -> apply(Action.RESUME, new ResumeActionRequest(past, null, null, false)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> apply(Action.RESUME, new ResumeActionRequest(far, null, null, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void resumeIntoCustomersLiveHoldIsSlotHeldUnlessOverridden() {
    given(AppointmentStatus.ON_HOLD, 5);
    UUID holdId = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    when(ledger.heldBy(eq(Fixtures.HUDSON), eq(DAY), anyMap()))
        .thenReturn(Map.of(Fixtures.JO, List.of(holdId)));
    when(ledger.getHold(Fixtures.HUDSON, DAY, holdId))
        .thenReturn(
            Optional.of(
                new StoredHold(
                    holdId,
                    holder,
                    new ResourcePair(Fixtures.HUDSON_BAY_2, Fixtures.JO),
                    PLANNED,
                    PLANNED,
                    "{}",
                    Instant.parse("2026-11-04T15:07:00Z"))));

    assertThatThrownBy(() -> apply(Action.RESUME, null))
        .isInstanceOf(SlotHeldException.class)
        .hasMessage(
            "A customer is holding this time until 10:07 (America/New_York). Resend with"
                + " overrideHold=true to take it.");
    verify(locks, never()).lockAll(any());

    transitionsTo(6, PLANNED);
    when(reservations.insertAll(any(), any(), any())).thenReturn(Map.of());
    apply(Action.RESUME, new ResumeActionRequest(null, null, null, true));
    verify(ledger).release(Fixtures.HUDSON, DAY, holdId, holder);
  }

  @Test
  void resumeRefusedByTheDatabaseIs409WithConflictsAndMarksTheLedgerDirty() {
    given(AppointmentStatus.ON_HOLD, 5);
    transitionsTo(6, PLANNED);
    when(reservations.insertAll(any(), any(), any()))
        .thenThrow(new ReservationConflictException(Fixtures.JO));
    UUID other = UUID.randomUUID();
    when(reservations.conflictingAppointments(eq(Fixtures.HUDSON), any(), any(), any(), any()))
        .thenReturn(List.of(APPOINTMENT, other));

    assertThatThrownBy(() -> apply(Action.RESUME, null))
        .isInstanceOf(SlotUnavailableException.class)
        .satisfies(
            e ->
                assertThat(((SlotUnavailableException) e).extras())
                    .containsEntry("conflicts", List.of(other)));
    verify(ledger).markDirty(Fixtures.HUDSON, DAY);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
  }

  @Test
  void completeEndsBothReservationsAtNowAndTheBayKeepsItsBuffer() {
    given(AppointmentStatus.IN_PROGRESS, 3);
    transitionsTo(4, new TimeRange(PLANNED.start(), NOW));
    TimeRange tech = new TimeRange(PLANNED.start(), NOW);
    TimeRange bay = tech.extendEnd(BUFFER);
    when(reservations.truncate(APPOINTMENT, ReservationRow.TECHNICIAN, Duration.ZERO))
        .thenReturn(
            List.of(
                new TruncatedReservation(
                    TECH_RESERVATION, Fixtures.JO, ReservationRow.TECHNICIAN, tech)));
    when(reservations.truncate(APPOINTMENT, ReservationRow.BAY, BUFFER))
        .thenReturn(
            List.of(
                new TruncatedReservation(
                    BAY_RESERVATION, Fixtures.HUDSON_BAY_1, ReservationRow.BAY, bay)));

    apply(Action.COMPLETE, null);

    StatusChange change = capturedChange();
    assertThat(change.to()).isEqualTo(AppointmentStatus.COMPLETED);
    assertThat(change.addStint()).isTrue();
    assertThat(change.endNowIfEarlier()).isTrue();
    verify(ledger)
        .applyCommitted(
            Fixtures.HUDSON,
            DAY,
            List.of(TECH_RESERVATION, BAY_RESERVATION),
            Map.of(
                TECH_RESERVATION,
                new ReservationRow(Fixtures.JO, ReservationRow.TECHNICIAN, tech),
                BAY_RESERVATION,
                new ReservationRow(Fixtures.HUDSON_BAY_1, ReservationRow.BAY, bay)));
  }

  @Test
  void noShowBeforeTheGraceIs422NoShowTooEarly() {
    AppointmentState justStarted =
        new AppointmentState(
            APPOINTMENT,
            Fixtures.ALICE,
            Fixtures.HUDSON,
            AppointmentStatus.BOOKED,
            0,
            new TimeRange(NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(35))),
            Fixtures.HUDSON_BAY_1,
            Fixtures.JO);
    when(appointments.findState(APPOINTMENT)).thenReturn(Optional.of(justStarted));

    assertThatThrownBy(() -> apply(Action.NO_SHOW, null))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).code()).isEqualTo(ProblemCodes.NO_SHOW_TOO_EARLY);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "No-show can be recorded from 10:20 (America/New_York), 30 minutes after"
                          + " the start.");
            });
    verify(appointments, never()).transition(any(), any(), anyLong(), any());
  }

  @Test
  void noShowAfterTheGraceReleasesTheReservations() {
    given(AppointmentStatus.BOOKED, 0);
    transitionsTo(1, PLANNED);
    when(reservations.deactivate(APPOINTMENT))
        .thenReturn(List.of(BAY_RESERVATION, TECH_RESERVATION));

    apply(Action.NO_SHOW, null);

    assertThat(capturedChange().requireNoShowGrace()).isTrue();
    verify(ledger)
        .applyCommitted(Fixtures.HUDSON, DAY, List.of(BAY_RESERVATION, TECH_RESERVATION), Map.of());
  }

  @Test
  void staleExpectedVersionIs412BeforeAnyWrite() {
    given(AppointmentStatus.BOOKED, 3);

    assertThatThrownBy(
            () ->
                service()
                    .apply(
                        dispatcher,
                        Fixtures.HUDSON,
                        APPOINTMENT,
                        Action.CHECK_IN,
                        null,
                        OptionalLong.of(2)))
        .isInstanceOf(VersionConflictException.class);
    verify(appointments, never()).transition(any(), any(), anyLong(), any());
  }

  @Test
  void versionMovedBetweenTheCheckAndTheUpdateWithIfMatchIs412() {
    given(AppointmentStatus.BOOKED, 3);
    when(appointments.transition(eq(APPOINTMENT), eq(Fixtures.HUDSON), eq(3L), any()))
        .thenReturn(Optional.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 4)));

    assertThatThrownBy(
            () ->
                service()
                    .apply(
                        dispatcher,
                        Fixtures.HUDSON,
                        APPOINTMENT,
                        Action.CHECK_IN,
                        null,
                        OptionalLong.of(3)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void versionMovedWithoutIfMatchRestartsTheWholeAttempt() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 4)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 4)));
    when(appointments.transition(eq(APPOINTMENT), eq(Fixtures.HUDSON), eq(3L), any()))
        .thenReturn(Optional.empty());
    when(appointments.transition(eq(APPOINTMENT), eq(Fixtures.HUDSON), eq(4L), any()))
        .thenReturn(Optional.of(new Transitioned(5, PLANNED)));
    when(reservations.deactivate(APPOINTMENT)).thenReturn(List.of());

    assertThat(apply(Action.CANCEL, new CancelActionRequest("Customer called"))).isSameAs(view);

    verify(locks, times(2)).lockAll(any());
    AppointmentCancelledEvent cancelled = (AppointmentCancelledEvent) event("AppointmentCancelled");
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    AppointmentStatusChangedEvent changed =
        (AppointmentStatusChangedEvent) event("AppointmentStatusChanged");
    assertThat(changed.reason()).isEqualTo("Customer called");
  }

  @Test
  void keepsMovingUnderUsGivesUpWith412AfterThreeAttempts() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 1)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 2)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 2)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 4)));
    when(appointments.transition(any(), any(), anyLong(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> apply(Action.CHECK_IN, null))
        .isInstanceOf(VersionConflictException.class);
    verify(appointments, times(3)).transition(any(), any(), anyLong(), any());
  }

  @Test
  void cancelledUnderUsWithoutIfMatchIs409AfterTheRestart() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.CANCELLED, 4)));
    when(appointments.transition(any(), any(), anyLong(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> apply(Action.CHECK_IN, null))
        .isInstanceOf(InvalidTransitionException.class)
        .hasMessage("Cannot check in an appointment that is CANCELLED; it can no longer change.");
  }

  @Test
  void changedUnderUsWithIfMatchIs412EvenIfTheStatusChangedToo() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.of(state(AppointmentStatus.CANCELLED, 4)));
    when(appointments.transition(any(), any(), anyLong(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .apply(
                        dispatcher,
                        Fixtures.HUDSON,
                        APPOINTMENT,
                        Action.CHECK_IN,
                        null,
                        OptionalLong.of(3)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void goneUnderUsIs404() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(AppointmentStatus.BOOKED, 3)))
        .thenReturn(Optional.empty());
    when(appointments.transition(any(), any(), anyLong(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> apply(Action.CHECK_IN, null)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void noShowGraceDecidedByTheDatabaseClockIs422() {
    given(AppointmentStatus.BOOKED, 3);
    when(appointments.transition(any(), any(), anyLong(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> apply(Action.NO_SHOW, null))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).code()).isEqualTo(ProblemCodes.NO_SHOW_TOO_EARLY));
  }

  @Test
  void invalidTransitionIs409NamingTheCurrentStatus() {
    given(AppointmentStatus.BOOKED, 3);

    assertThatThrownBy(() -> apply(Action.START, null))
        .isInstanceOf(InvalidTransitionException.class)
        .hasMessage("Cannot start an appointment that is BOOKED; check it in first.");
  }

  @Test
  void appointmentOfAnotherDealershipIs404() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                new AppointmentState(
                    APPOINTMENT,
                    Fixtures.ALICE,
                    Fixtures.RIVERSIDE,
                    AppointmentStatus.BOOKED,
                    0,
                    PLANNED,
                    Fixtures.RIVERSIDE_BAY_1,
                    Fixtures.SAM)));

    assertThatThrownBy(() -> apply(Action.CHECK_IN, null)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void checkInAndStartTakeNoLocksAndLeaveReservationsAndLedgerAlone() {
    given(AppointmentStatus.BOOKED, 0);
    transitionsTo(1, PLANNED);

    apply(Action.CHECK_IN, null);

    verify(locks, never()).lockAll(any());
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
    assertThat(capturedChange().startNow()).isFalse();
  }

  @Test
  void startStampsTheStint() {
    given(AppointmentStatus.CHECKED_IN, 1);
    transitionsTo(2, PLANNED);

    apply(Action.START, null);

    assertThat(capturedChange().startNow()).isTrue();
  }

  @Test
  void deadlockIsRetriedOnceFromTheTop() {
    given(AppointmentStatus.BOOKED, 0);
    when(appointments.transition(any(), any(), anyLong(), any()))
        .thenThrow(deadlock())
        .thenReturn(Optional.of(new Transitioned(1, PLANNED)));
    when(reservations.deactivate(APPOINTMENT)).thenReturn(List.of());

    assertThat(apply(Action.CANCEL, null)).isSameAs(view);

    when(appointments.transition(any(), any(), anyLong(), any())).thenThrow(deadlock());
    assertThatThrownBy(() -> apply(Action.CANCEL, null))
        .isInstanceOf(CannotAcquireLockException.class);
  }

  @Test
  void ledgerFailureAfterCommitOnlyMarksTheDayDirty() {
    given(AppointmentStatus.BOOKED, 0);
    transitionsTo(1, PLANNED);
    when(reservations.deactivate(APPOINTMENT)).thenReturn(List.of(BAY_RESERVATION));
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
    doThrow(new IllegalStateException("still down")).when(ledger).markDirty(any(), any());

    assertThat(apply(Action.CANCEL, null)).isSameAs(view);
    verify(ledger).markDirty(Fixtures.HUDSON, DAY);
  }

  private static CannotAcquireLockException deadlock() {
    return new CannotAcquireLockException(
        "deadlock", new java.sql.SQLException("deadlock detected", "40P01"));
  }
}
