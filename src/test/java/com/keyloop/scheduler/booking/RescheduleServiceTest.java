package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.HoldsUnavailableException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.VersionConflictException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.hold.HoldPayload;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class RescheduleServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");
  private static final UUID HOLD = UUID.fromString("0192f0b9-0000-7000-8000-000000000001");
  private static final UUID RESERVATION_BAY =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b1");
  private static final UUID RESERVATION_TECH =
      UUID.fromString("0192f0b9-0000-7000-8000-0000000000b2");
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);
  private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 5);
  private static final TimeRange WEDNESDAY_9 =
      new TimeRange(Instant.parse("2026-11-04T09:00:00Z"), Instant.parse("2026-11-04T09:45:00Z"));
  private static final TimeRange THURSDAY_10 =
      new TimeRange(Instant.parse("2026-11-05T10:00:00Z"), Instant.parse("2026-11-05T10:45:00Z"));
  private static final ResourcePair HELD = new ResourcePair(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM);

  private final CustomerService customers = mock(CustomerService.class);
  private final CatalogService catalogs = mock(CatalogService.class);
  private final AppointmentRepository appointments = mock(AppointmentRepository.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final ResourceLocks locks = mock(ResourceLocks.class);
  private final AppointmentReadRepository reads = mock(AppointmentReadRepository.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final SlotLedger ledger = mock(SlotLedger.class);
  private final DbClock clock = mock(DbClock.class);
  private final OccupancySource dailyOccupancy = mock(OccupancySource.class);
  private final List<String> trace = new ArrayList<>();
  private final AppointmentView movedView = mock(AppointmentView.class);
  private final CallerContext alice =
      new CallerContext(Fixtures.ALICE_SUB, Set.of(Role.CUSTOMER), Set.of());
  private final ResourceCatalog catalog =
      new ResourceCatalog(
          CatalogFixtures.riverside(),
          List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
          List.of(
              new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1"),
              new BayView(Fixtures.RIVERSIDE_BAY_2, "Bay 2")),
          List.of(
              new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL", "BRAKES")),
              new TechnicianView(Fixtures.TOM, "Tom", Set.of("OIL", "BRAKES", "DIAG"))));
  private final Map<UUID, ReservationRow> movedRows = new LinkedHashMap<>();

  private final TransactionOperations transactions =
      new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          T result = action.doInTransaction(null);
          trace.add("commit");
          return result;
        }
      };

  private RescheduleService service() {
    return service(transactions);
  }

  private RescheduleService service(TransactionOperations tx) {
    return new RescheduleService(
        customers,
        catalogs,
        appointments,
        reservations,
        locks,
        reads,
        outbox,
        ledger,
        clock,
        dailyOccupancy,
        tx);
  }

  private static AppointmentState state(
      UUID customerId, AppointmentStatus status, long version, TimeRange period) {
    return new AppointmentState(
        APPOINTMENT,
        customerId,
        Fixtures.RIVERSIDE,
        status,
        version,
        period,
        Fixtures.RIVERSIDE_BAY_1,
        Fixtures.SAM);
  }

  private static StoredHold hold(UUID userId, TimeRange period, List<UUID> services) {
    return new StoredHold(
        HOLD,
        userId,
        HELD,
        period,
        period,
        HoldPayload.of(Fixtures.ALICE_FOCUS, services, APPOINTMENT).toJson(),
        NOW.plusSeconds(600));
  }

  private void holdIs(UUID dealership, LocalDate date, StoredHold stored) {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(dealership, date)));
    when(ledger.getHold(dealership, date, HOLD)).thenReturn(Optional.of(stored));
  }

  @BeforeEach
  void stub() {
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(clock.now()).thenReturn(NOW);
    when(catalogs.get(Fixtures.RIVERSIDE)).thenReturn(catalog);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)));
    when(appointments.serviceTypeIds(APPOINTMENT)).thenReturn(List.of(Fixtures.OIL_CHANGE));
    holdIs(
        Fixtures.RIVERSIDE,
        THURSDAY,
        hold(Fixtures.ALICE_SUB, THURSDAY_10, List.of(Fixtures.OIL_CHANGE)));
    when(appointments.markRescheduled(
            eq(APPOINTMENT),
            eq(Fixtures.ALICE),
            eq(3L),
            eq(HELD.bayId()),
            eq(HELD.technicianId()),
            any()))
        .thenReturn(OptionalLong.of(4));
    movedRows.put(
        RESERVATION_BAY, new ReservationRow(HELD.bayId(), ReservationRow.BAY, THURSDAY_10));
    movedRows.put(
        RESERVATION_TECH,
        new ReservationRow(HELD.technicianId(), ReservationRow.TECHNICIAN, THURSDAY_10));
    when(reservations.move(eq(APPOINTMENT), any())).thenReturn(movedRows);
    when(reads.find(APPOINTMENT)).thenReturn(Optional.of(movedView));
    when(dailyOccupancy.forDay(any(), any(), any(), any())).thenReturn(Occupancy.empty());
    doAnswer(
            inv -> {
              trace.add("ledger");
              return null;
            })
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
  }

  @Test
  void movesBothReservationsWritesEventWithNewVersionAndUpdatesLedgerAfterCommit() {
    AppointmentView view = service().customerReschedule(alice, APPOINTMENT, 3, HOLD);

    assertThat(view).isSameAs(movedView);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
    verify(locks).lockAll(locked.capture());
    assertThat(locked.getValue())
        .containsExactlyInAnyOrder(
            Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM, Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ReservationRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(reservations).move(eq(APPOINTMENT), rows.capture());
    assertThat(rows.getValue())
        .containsExactlyInAnyOrder(
            new ReservationRow(Fixtures.RIVERSIDE_BAY_2, ReservationRow.BAY, THURSDAY_10),
            new ReservationRow(Fixtures.TOM, ReservationRow.TECHNICIAN, THURSDAY_10));
    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            eq("Appointment"),
            eq(APPOINTMENT),
            eq(Fixtures.RIVERSIDE),
            eq("AppointmentRescheduled"),
            eq(4L),
            event.capture());
    assertThat(event.getValue())
        .isEqualTo(
            new AppointmentRescheduledEvent(
                APPOINTMENT,
                Fixtures.ALICE,
                WEDNESDAY_9.start(),
                WEDNESDAY_9.end(),
                Fixtures.RIVERSIDE_BAY_1,
                Fixtures.SAM,
                THURSDAY_10.start(),
                THURSDAY_10.end(),
                Fixtures.RIVERSIDE_BAY_2,
                Fixtures.TOM,
                "Europe/London",
                "BOOKED"));
    assertThat(trace).containsExactly("commit", "ledger", "ledger");
  }

  @Test
  void ledgerLeavesOldDayAddsNewDayAndReleasesTheConsumedHold() {
    service().customerReschedule(alice, APPOINTMENT, 3, HOLD);

    verify(ledger)
        .applyCommitted(
            Fixtures.RIVERSIDE, WEDNESDAY, List.of(RESERVATION_BAY, RESERVATION_TECH), Map.of());
    verify(ledger).applyCommitted(Fixtures.RIVERSIDE, THURSDAY, List.of(), movedRows);
    verify(ledger).release(Fixtures.RIVERSIDE, THURSDAY, HOLD, Fixtures.ALICE_SUB);
  }

  @Test
  void sameDayMoveIsOneLedgerUpdate() {
    TimeRange laterThatDay =
        new TimeRange(Instant.parse("2026-11-04T14:00:00Z"), Instant.parse("2026-11-04T14:45:00Z"));
    holdIs(
        Fixtures.RIVERSIDE,
        WEDNESDAY,
        hold(Fixtures.ALICE_SUB, laterThatDay, List.of(Fixtures.OIL_CHANGE)));

    service().customerReschedule(alice, APPOINTMENT, 3, HOLD);

    verify(ledger)
        .applyCommitted(
            Fixtures.RIVERSIDE, WEDNESDAY, List.of(RESERVATION_BAY, RESERVATION_TECH), movedRows);
    verify(ledger).release(Fixtures.RIVERSIDE, WEDNESDAY, HOLD, Fixtures.ALICE_SUB);
  }

  @Test
  void staleVersionIs412WithoutLockingOrWriting() {
    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 2, HOLD))
        .isInstanceOf(VersionConflictException.class);

    verify(locks, never()).lockAll(any());
    verify(appointments, never()).markRescheduled(any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void notOwnedBeforeTheUpdateIs404() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.BOB, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void zeroRowsUpdatedAndAppointmentExistsIs412() {
    when(appointments.markRescheduled(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)),
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 4, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(VersionConflictException.class);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
    verify(outbox, never()).append(any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void zeroRowsUpdatedAndNotOwnedIs404() {
    when(appointments.markRescheduled(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)),
            Optional.of(state(Fixtures.BOB, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void startingWithin24HoursIs422() {
    TimeRange soon = new TimeRange(NOW.plusSeconds(5 * 3600), NOW.plusSeconds(5 * 3600 + 2700));
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, soon)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.CHANGE_WINDOW_CLOSED));
    verify(locks, never()).lockAll(any());
  }

  @Test
  void nonBookedIs409() {
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.CHECKED_IN, 3, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.INVALID_TRANSITION));
  }

  @Test
  void someoneElsesHoldIs404() {
    holdIs(
        Fixtures.RIVERSIDE,
        THURSDAY,
        hold(Fixtures.BOB_SUB, THURSDAY_10, List.of(Fixtures.OIL_CHANGE)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(NotFoundException.class);
    verify(locks, never()).lockAll(any());
  }

  @Test
  void goneHoldIs404() {
    when(ledger.getHold(any(), any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void goneHoldAfterConcurrentRescheduleIs412() {
    when(ledger.getHold(any(), any(), any())).thenReturn(Optional.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)),
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 4, THURSDAY_10)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void unknownHoldLocationIs404() {
    when(ledger.locate(HOLD)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void holdForAnotherDealershipIsHoldMismatch() {
    holdIs(
        Fixtures.HUDSON,
        THURSDAY,
        hold(Fixtures.ALICE_SUB, THURSDAY_10, List.of(Fixtures.OIL_CHANGE)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH));
  }

  @Test
  void holdForOtherServicesIsHoldMismatch() {
    holdIs(
        Fixtures.RIVERSIDE,
        THURSDAY,
        hold(Fixtures.ALICE_SUB, THURSDAY_10, List.of(Fixtures.BRAKE_INSPECTION)));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH));
  }

  @Test
  void redisFailureReadingTheHoldIs503() {
    when(ledger.locate(HOLD)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(HoldsUnavailableException.class);
  }

  @Test
  void otherFailuresReadingTheHoldPropagate() {
    when(ledger.locate(HOLD)).thenThrow(new IllegalStateException("bug"));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusedReservationIs409WithAlternativesAndMarksTheLedgerDirty() {
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenThrow(new ReservationConflictException(Fixtures.TOM));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(SlotUnavailableException.class)
        .satisfies(
            e -> assertThat(((SlotUnavailableException) e).extras()).containsKey("alternatives"));
    verify(ledger).markDirty(Fixtures.RIVERSIDE, THURSDAY);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
  }

  @Test
  void markDirtyFailureDoesNotHideTheConflict() {
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenThrow(new ReservationConflictException(Fixtures.TOM));
    doThrow(new IllegalStateException("redis down")).when(ledger).markDirty(any(), any());

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(SlotUnavailableException.class);
  }

  @Test
  void deadlockIsRetriedOnce() {
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenThrow(new RuntimeException(new SQLException("deadlock", "40P01")))
        .thenReturn(movedRows);

    assertThat(service().customerReschedule(alice, APPOINTMENT, 3, HOLD)).isSameAs(movedView);
  }

  @Test
  void secondDeadlockPropagates() {
    when(reservations.move(eq(APPOINTMENT), any()))
        .thenThrow(new RuntimeException(new SQLException("deadlock", "40P01")));

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void ledgerFailureAfterCommitIsNotAnError() {
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .release(any(), any(), any(), any());

    assertThat(service().customerReschedule(alice, APPOINTMENT, 3, HOLD)).isSameAs(movedView);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, WEDNESDAY);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, THURSDAY);
  }

  @Test
  void holdNotTakenForThisAppointmentIsHoldMismatch() {
    StoredHold plain =
        new StoredHold(
            HOLD,
            Fixtures.ALICE_SUB,
            HELD,
            THURSDAY_10,
            THURSDAY_10,
            HoldPayload.of(Fixtures.ALICE_FOCUS, List.of(Fixtures.OIL_CHANGE)).toJson(),
            NOW.plusSeconds(600));
    holdIs(Fixtures.RIVERSIDE, THURSDAY, plain);

    assertThatThrownBy(() -> service().customerReschedule(alice, APPOINTMENT, 3, HOLD))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH));
  }
}
