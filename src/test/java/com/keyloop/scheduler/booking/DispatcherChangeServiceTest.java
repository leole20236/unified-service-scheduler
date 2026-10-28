package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class DispatcherChangeServiceTest {

  /** 15:00Z = 10:00 in New York (Hudson, open 09:00-17:00, 10 minute bay buffer). */
  private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");

  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");
  private static final UUID BAY_RESERVATION =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b1");
  private static final UUID TECH_RESERVATION =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b2");
  private static final Duration BUFFER = Duration.ofMinutes(10);

  /** Thursday 09:00-10:15 New York. */
  private static final TimeRange THURSDAY_9 =
      new TimeRange(Instant.parse("2026-11-05T14:00:00Z"), Instant.parse("2026-11-05T15:15:00Z"));

  private static final Instant THURSDAY_13 = Instant.parse("2026-11-05T18:00:00Z");
  private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 5);

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

  private DispatcherChangeService service() {
    return new DispatcherChangeService(
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

  private static ResourceCatalog catalog() {
    return new ResourceCatalog(
        CatalogFixtures.hudson(),
        List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
        List.of(
            new BayView(Fixtures.HUDSON_BAY_1, "Bay 1"),
            new BayView(Fixtures.HUDSON_BAY_2, "Bay 2")),
        List.of(
            new TechnicianView(Fixtures.JO, "Jo", Set.of(Fixtures.OIL, Fixtures.BRAKES)),
            new TechnicianView(Fixtures.KIM, "Kim", Set.of(Fixtures.OIL))));
  }

  private void given(AppointmentStatus status, long version, TimeRange period) {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                new AppointmentState(
                    APPOINTMENT,
                    Fixtures.ALICE,
                    Fixtures.HUDSON,
                    status,
                    version,
                    period,
                    Fixtures.HUDSON_BAY_1,
                    Fixtures.JO)));
  }

  @BeforeEach
  void stub() {
    when(clock.now()).thenReturn(NOW);
    when(catalogs.get(Fixtures.HUDSON)).thenReturn(catalog());
    when(reads.find(APPOINTMENT)).thenReturn(Optional.of(view));
    when(appointments.serviceTypeIds(APPOINTMENT)).thenReturn(List.of(Fixtures.BRAKE_INSPECTION));
    when(appointments.markMoved(
            eq(APPOINTMENT), eq(Fixtures.HUDSON), anyLong(), any(), any(), any()))
        .thenReturn(OptionalLong.of(4));
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenAnswer(
            inv -> {
              List<ReservationRow> rows = inv.getArgument(1);
              Map<UUID, ReservationRow> moved = new LinkedHashMap<>();
              moved.put(BAY_RESERVATION, rows.get(0));
              moved.put(TECH_RESERVATION, rows.get(1));
              return moved;
            });
  }

  private AppointmentView change(long expected, DispatcherChangeRequest req) {
    return service().change(dispatcher, Fixtures.HUDSON, APPOINTMENT, expected, req);
  }

  private static DispatcherChangeRequest drag(Instant start, UUID bay, UUID tech) {
    return new DispatcherChangeRequest(
        start == null ? null : OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
        bay,
        tech,
        null,
        false);
  }

  @Test
  void dragMovesBothReservationsLocksOldAndNewAndWritesTheEvent() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);
    TimeRange moved = new TimeRange(THURSDAY_13, THURSDAY_13.plus(Duration.ofMinutes(75)));

    AppointmentView result = change(3, drag(THURSDAY_13, Fixtures.HUDSON_BAY_2, null));

    assertThat(result).isSameAs(view);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
    verify(locks).lockAll(locked.capture());
    assertThat(locked.getValue())
        .containsExactlyInAnyOrder(
            Fixtures.HUDSON_BAY_1, Fixtures.JO, Fixtures.HUDSON_BAY_2, Fixtures.JO);
    verify(appointments)
        .markMoved(APPOINTMENT, Fixtures.HUDSON, 3, Fixtures.HUDSON_BAY_2, Fixtures.JO, moved);
    List<ReservationRow> target =
        List.of(
            new ReservationRow(Fixtures.HUDSON_BAY_2, ReservationRow.BAY, moved.extendEnd(BUFFER)),
            new ReservationRow(Fixtures.JO, ReservationRow.TECHNICIAN, moved));
    verify(reservations).move(APPOINTMENT, target);
    ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            eq("Appointment"),
            eq(APPOINTMENT),
            eq(Fixtures.HUDSON),
            eq("AppointmentRescheduled"),
            eq(4L),
            data.capture());
    AppointmentRescheduledEvent event = (AppointmentRescheduledEvent) data.getValue();
    assertThat(event.oldStart()).isEqualTo(THURSDAY_9.start());
    assertThat(event.newStart()).isEqualTo(THURSDAY_13);
    assertThat(event.oldServiceBayId()).isEqualTo(Fixtures.HUDSON_BAY_1);
    assertThat(event.newServiceBayId()).isEqualTo(Fixtures.HUDSON_BAY_2);
    assertThat(event.status()).isEqualTo("BOOKED");
    Map<UUID, ReservationRow> written = new LinkedHashMap<>();
    written.put(BAY_RESERVATION, target.get(0));
    written.put(TECH_RESERVATION, target.get(1));
    verify(ledger)
        .applyCommitted(
            Fixtures.HUDSON, THURSDAY, List.of(BAY_RESERVATION, TECH_RESERVATION), written);
  }

  @Test
  void moveToAnotherDayUpdatesBothLedgerDays() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);
    Instant friday = THURSDAY_13.plus(Duration.ofDays(1));

    change(3, drag(friday, null, null));

    verify(ledger)
        .applyCommitted(
            Fixtures.HUDSON, THURSDAY, List.of(BAY_RESERVATION, TECH_RESERVATION), Map.of());
    verify(ledger)
        .applyCommitted(eq(Fixtures.HUDSON), eq(THURSDAY.plusDays(1)), eq(List.of()), anyMap());
  }

  @Test
  void resizeKeepsStartAndResourcesWhenOmitted() {
    given(AppointmentStatus.CHECKED_IN, 3, THURSDAY_9);

    change(3, new DispatcherChangeRequest(null, null, null, 120, false));

    verify(appointments)
        .markMoved(
            APPOINTMENT,
            Fixtures.HUDSON,
            3,
            Fixtures.HUDSON_BAY_1,
            Fixtures.JO,
            new TimeRange(THURSDAY_9.start(), THURSDAY_9.start().plus(Duration.ofMinutes(120))));
  }

  @Test
  void overlapWithAnotherUsersHoldIsSlotHeldAndOverrideReleasesIt() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);
    UUID holdId = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    when(ledger.heldBy(eq(Fixtures.HUDSON), eq(THURSDAY), anyMap()))
        .thenReturn(Map.of(Fixtures.HUDSON_BAY_2, List.of(holdId)));
    when(ledger.getHold(Fixtures.HUDSON, THURSDAY, holdId))
        .thenReturn(
            Optional.of(
                new StoredHold(
                    holdId,
                    holder,
                    new ResourcePair(Fixtures.HUDSON_BAY_2, Fixtures.KIM),
                    THURSDAY_9,
                    THURSDAY_9,
                    "{}",
                    Instant.parse("2026-11-04T15:09:00Z"))));

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, Fixtures.HUDSON_BAY_2, null)))
        .isInstanceOf(SlotHeldException.class)
        .hasMessageContaining("until 10:09 (America/New_York)");
    verify(locks, never()).lockAll(any());
    verify(ledger, never()).release(any(), any(), any(), any());

    change(
        3,
        new DispatcherChangeRequest(
            OffsetDateTime.ofInstant(THURSDAY_13, ZoneOffset.UTC),
            Fixtures.HUDSON_BAY_2,
            null,
            null,
            true));

    verify(ledger).release(Fixtures.HUDSON, THURSDAY, holdId, holder);
    verify(reservations).move(eq(APPOINTMENT), any());
  }

  @Test
  void inProgressStartChangeIs422StartFixed() {
    given(AppointmentStatus.IN_PROGRESS, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ProblemCodes.START_FIXED));
  }

  @Test
  void inProgressResizeKeepsTheCurrentSegmentStart() {
    TimeRange today =
        new TimeRange(NOW.minus(Duration.ofMinutes(60)), NOW.plus(Duration.ofMinutes(15)));
    given(AppointmentStatus.IN_PROGRESS, 3, today);
    Instant segment = NOW.minus(Duration.ofMinutes(20));
    when(reservations.activeRows(APPOINTMENT))
        .thenReturn(
            List.of(
                new ActiveReservation(
                    BAY_RESERVATION,
                    new ReservationRow(
                        Fixtures.HUDSON_BAY_1,
                        ReservationRow.BAY,
                        new TimeRange(segment, today.end().plus(BUFFER)))),
                new ActiveReservation(
                    TECH_RESERVATION,
                    new ReservationRow(
                        Fixtures.JO,
                        ReservationRow.TECHNICIAN,
                        new TimeRange(segment, today.end())))));

    change(3, new DispatcherChangeRequest(null, Fixtures.HUDSON_BAY_2, null, 120, false));

    Instant newEnd = today.start().plus(Duration.ofMinutes(120));
    verify(reservations)
        .move(
            APPOINTMENT,
            List.of(
                new ReservationRow(
                    Fixtures.HUDSON_BAY_2,
                    ReservationRow.BAY,
                    new TimeRange(segment, newEnd.plus(BUFFER))),
                new ReservationRow(
                    Fixtures.JO, ReservationRow.TECHNICIAN, new TimeRange(segment, newEnd))));
    verify(appointments)
        .markMoved(
            APPOINTMENT,
            Fixtures.HUDSON,
            3,
            Fixtures.HUDSON_BAY_2,
            Fixtures.JO,
            new TimeRange(today.start(), newEnd));
  }

  @Test
  void inProgressShrinkBeforeTheCurrentSegmentIs400() {
    TimeRange today =
        new TimeRange(NOW.minus(Duration.ofMinutes(60)), NOW.plus(Duration.ofMinutes(15)));
    given(AppointmentStatus.IN_PROGRESS, 3, today);
    Instant segment = NOW.minus(Duration.ofMinutes(20));
    when(reservations.activeRows(APPOINTMENT))
        .thenReturn(
            List.of(
                new ActiveReservation(
                    TECH_RESERVATION,
                    new ReservationRow(
                        Fixtures.JO,
                        ReservationRow.TECHNICIAN,
                        new TimeRange(segment, today.end())))));

    assertThatThrownBy(() -> change(3, new DispatcherChangeRequest(null, null, null, 30, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void onHoldIs409InvalidTransition() {
    given(AppointmentStatus.ON_HOLD, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, null, null)))
        .isInstanceOf(InvalidTransitionException.class);
    verify(appointments, never()).markMoved(any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void terminalIs409InvalidTransition() {
    given(AppointmentStatus.COMPLETED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, null, null)))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void staleIfMatchIs412() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(2, drag(THURSDAY_13, null, null)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void otherDealershipOrMissingIs404() {
    when(appointments.findState(APPOINTMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void unqualifiedTechnicianIs422() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(null, null, Fixtures.KIM)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e ->
                assertThat(((ApiException) e).code())
                    .isEqualTo(ProblemCodes.TECHNICIAN_NOT_QUALIFIED));
  }

  @Test
  void unknownBayOrTechnicianIs404() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(null, UUID.randomUUID(), null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> change(3, drag(null, null, UUID.randomUUID())))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void durationOutOfRangeIs400() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, new DispatcherChangeRequest(null, null, null, 4, false)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> change(3, new DispatcherChangeRequest(null, null, null, 1441, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void dragIntoThePastIsOutsideTheBookingWindow() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);

    assertThatThrownBy(() -> change(3, drag(NOW.minus(Duration.ofHours(1)), null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e ->
                assertThat(((ApiException) e).code())
                    .isEqualTo(ProblemCodes.OUTSIDE_BOOKING_WINDOW));
  }

  @Test
  void checkedInJobWhoseStartPassedCanStillChangeTechnician() {
    TimeRange started =
        new TimeRange(NOW.minus(Duration.ofMinutes(40)), NOW.plus(Duration.ofMinutes(35)));
    given(AppointmentStatus.CHECKED_IN, 3, started);

    change(3, drag(null, null, Fixtures.JO));

    verify(reservations).move(eq(APPOINTMENT), any());
  }

  @Test
  void refusedByTheDatabaseIs409WithTheOtherAppointmentsAsConflicts() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenThrow(new ReservationConflictException(Fixtures.HUDSON_BAY_2));
    UUID other = UUID.randomUUID();
    when(reservations.conflictingAppointments(eq(Fixtures.HUDSON), any(), any(), any(), any()))
        .thenReturn(List.of(APPOINTMENT, other));

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, Fixtures.HUDSON_BAY_2, null)))
        .isInstanceOf(SlotUnavailableException.class)
        .satisfies(
            e -> {
              assertThat(((SlotUnavailableException) e).extras())
                  .containsEntry("conflicts", List.of(other));
              assertThat(e.getMessage())
                  .isEqualTo(
                      "The chosen bay or technician is not free at 13:00 on Thu 5 Nov"
                          + " (America/New_York).");
            });
    verify(ledger).markDirty(Fixtures.HUDSON, THURSDAY);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
  }

  @Test
  void updateMissedAfterTheCheckIsDiagnosed() {
    given(AppointmentStatus.BOOKED, 3, THURSDAY_9);
    when(appointments.markMoved(
            eq(APPOINTMENT), eq(Fixtures.HUDSON), anyLong(), any(), any(), any()))
        .thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                new AppointmentState(
                    APPOINTMENT,
                    Fixtures.ALICE,
                    Fixtures.HUDSON,
                    AppointmentStatus.BOOKED,
                    3,
                    THURSDAY_9,
                    Fixtures.HUDSON_BAY_1,
                    Fixtures.JO)))
        .thenReturn(
            Optional.of(
                new AppointmentState(
                    APPOINTMENT,
                    Fixtures.ALICE,
                    Fixtures.HUDSON,
                    AppointmentStatus.CANCELLED,
                    4,
                    THURSDAY_9,
                    Fixtures.HUDSON_BAY_1,
                    Fixtures.JO)));

    assertThatThrownBy(() -> change(3, drag(THURSDAY_13, null, null)))
        .isInstanceOf(VersionConflictException.class);
  }
}
