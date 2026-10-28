package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.InvalidTransitionException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.VersionConflictException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class CustomerCancelServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");
  private static final UUID RESERVATION_1 = UUID.fromString("0192f0b9-0000-7000-8000-0000000000b1");
  private static final UUID RESERVATION_2 = UUID.fromString("0192f0b9-0000-7000-8000-0000000000b2");
  private static final TimeRange WEDNESDAY_9 =
      new TimeRange(Instant.parse("2026-11-04T09:00:00Z"), Instant.parse("2026-11-04T09:45:00Z"));

  private final CustomerService customers = mock(CustomerService.class);
  private final CatalogService catalogs = mock(CatalogService.class);
  private final AppointmentRepository appointments = mock(AppointmentRepository.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final ResourceLocks locks = mock(ResourceLocks.class);
  private final AppointmentReadRepository reads = mock(AppointmentReadRepository.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final SlotLedger ledger = mock(SlotLedger.class);
  private final DbClock clock = mock(DbClock.class);
  private final List<String> trace = new ArrayList<>();
  private final CallerContext alice =
      new CallerContext(Fixtures.ALICE_SUB, Set.of(Role.CUSTOMER), Set.of());
  private final AppointmentView cancelledView = mock(AppointmentView.class);

  private final TransactionOperations transactions =
      new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          T result = action.doInTransaction(null);
          trace.add("commit");
          return result;
        }
      };

  private CustomerCancelService service() {
    return new CustomerCancelService(
        customers,
        catalogs,
        appointments,
        reservations,
        locks,
        reads,
        outbox,
        ledger,
        clock,
        transactions);
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

  @BeforeEach
  void stub() {
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(clock.now()).thenReturn(NOW);
    when(catalogs.get(Fixtures.RIVERSIDE))
        .thenReturn(CatalogFixtures.catalog(CatalogFixtures.riverside()));
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)));
    when(reads.find(APPOINTMENT)).thenReturn(Optional.of(cancelledView));
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(3)))
        .thenReturn(OptionalLong.of(4));
    when(reservations.deactivate(APPOINTMENT)).thenReturn(List.of(RESERVATION_1, RESERVATION_2));
    org.mockito.Mockito.doAnswer(
            inv -> {
              trace.add("ledger");
              return null;
            })
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
  }

  @Test
  void cancelsLocksBothResourcesWritesEventWithNewVersionAndUpdatesLedgerAfterCommit() {
    AppointmentView view = service().cancel(alice, APPOINTMENT);

    assertThat(view).isSameAs(cancelledView);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Collection<UUID>> locked =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(locks).lockAll(locked.capture());
    assertThat(locked.getValue()).containsExactlyInAnyOrder(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM);
    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            eq("Appointment"),
            eq(APPOINTMENT),
            eq(Fixtures.RIVERSIDE),
            eq("AppointmentCancelled"),
            eq(4L),
            event.capture());
    assertThat(event.getValue())
        .isEqualTo(
            new AppointmentCancelledEvent(
                APPOINTMENT,
                Fixtures.ALICE,
                WEDNESDAY_9.start(),
                WEDNESDAY_9.end(),
                Fixtures.RIVERSIDE_BAY_1,
                Fixtures.SAM,
                "Europe/London",
                "CANCELLED"));
    verify(ledger)
        .applyCommitted(
            Fixtures.RIVERSIDE,
            LocalDate.of(2026, 11, 4),
            List.of(RESERVATION_1, RESERVATION_2),
            Map.of());
    assertThat(trace).containsExactly("commit", "ledger");
  }

  @Test
  void zeroRowsWhenNotOwnedIs404() {
    when(appointments.markCancelled(any(), any(), any())).thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.BOB, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(NotFoundException.class);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
    verify(outbox, never())
        .append(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
  }

  @Test
  void missingAppointmentIs404() {
    when(appointments.findState(APPOINTMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(NotFoundException.class);
    verify(locks, never()).lockAll(any());
  }

  @Test
  void zeroRowsWithStaleIfMatchIs412() {
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(2)))
        .thenReturn(OptionalLong.empty());

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT, OptionalLong.of(2)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void zeroRowsOnCancelledAppointmentIs409() {
    when(appointments.markCancelled(any(), any(), any())).thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.CANCELLED, 4, WEDNESDAY_9)));

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void zeroRowsWithin24HoursIs422WithRelativeDetail() {
    TimeRange soon = new TimeRange(NOW.plusSeconds(5 * 3600), NOW.plusSeconds(5 * 3600 + 2700));
    when(appointments.markCancelled(any(), any(), any())).thenReturn(OptionalLong.empty());
    when(appointments.findState(APPOINTMENT))
        .thenReturn(Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, soon)));

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.CHANGE_WINDOW_CLOSED);
              assertThat(e.getMessage()).contains("starts in 5 hours");
            });
  }

  @Test
  void zeroRowsWithoutExplanationIsConcurrentChange412() {
    when(appointments.markCancelled(any(), any(), any())).thenReturn(OptionalLong.empty());

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void ledgerFailureAfterCommitIsLoggedNotThrown() {
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());

    assertThat(service().cancel(alice, APPOINTMENT)).isSameAs(cancelledView);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, LocalDate.of(2026, 11, 4));
  }

  @Test
  void markDirtyFailureIsSwallowedToo() {
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
    doThrow(new IllegalStateException("redis down")).when(ledger).markDirty(any(), any());

    assertThat(service().cancel(alice, APPOINTMENT)).isSameAs(cancelledView);
  }

  @Test
  void cancelQueuedBehindRescheduleDecidesOnTheMovedAppointment() {
    TimeRange thursday =
        new TimeRange(Instant.parse("2026-11-05T11:00:00Z"), Instant.parse("2026-11-05T11:45:00Z"));
    AppointmentState moved =
        new AppointmentState(
            APPOINTMENT,
            Fixtures.ALICE,
            Fixtures.RIVERSIDE,
            AppointmentStatus.BOOKED,
            4,
            thursday,
            Fixtures.RIVERSIDE_BAY_2,
            Fixtures.TOM);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)),
            Optional.of(moved));
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(3)))
        .thenReturn(OptionalLong.empty());
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(4)))
        .thenReturn(OptionalLong.of(5));

    service().cancel(alice, APPOINTMENT);

    ArgumentCaptor<java.util.Collection<UUID>> locked =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(locks, org.mockito.Mockito.times(2)).lockAll(locked.capture());
    assertThat(locked.getAllValues().get(1))
        .containsExactlyInAnyOrder(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM);
    verify(outbox)
        .append(
            eq("Appointment"),
            eq(APPOINTMENT),
            eq(Fixtures.RIVERSIDE),
            eq("AppointmentCancelled"),
            eq(5L),
            eq(
                new AppointmentCancelledEvent(
                    APPOINTMENT,
                    Fixtures.ALICE,
                    thursday.start(),
                    thursday.end(),
                    Fixtures.RIVERSIDE_BAY_2,
                    Fixtures.TOM,
                    "Europe/London",
                    "CANCELLED")));
    verify(ledger)
        .applyCommitted(
            eq(Fixtures.RIVERSIDE),
            eq(LocalDate.of(2026, 11, 5)),
            eq(List.of(RESERVATION_1, RESERVATION_2)),
            eq(Map.of()));
    verify(ledger, never()).applyCommitted(any(), eq(LocalDate.of(2026, 11, 4)), any(), any());
  }

  @Test
  void versionMissRestartsWithOneLockAllPerAttemptAndAbandonsTheFirstTransaction() {
    AppointmentState moved =
        new AppointmentState(
            APPOINTMENT,
            Fixtures.ALICE,
            Fixtures.RIVERSIDE,
            AppointmentStatus.BOOKED,
            4,
            WEDNESDAY_9,
            Fixtures.RIVERSIDE_BAY_2,
            Fixtures.TOM);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(state(Fixtures.ALICE, AppointmentStatus.BOOKED, 3, WEDNESDAY_9)),
            Optional.of(moved));
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(3)))
        .thenReturn(OptionalLong.empty());
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(4)))
        .thenReturn(OptionalLong.of(5));
    List<String> events = new ArrayList<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              events.add("lock" + ((java.util.Collection<?>) inv.getArgument(0)).size());
              return null;
            })
        .when(locks)
        .lockAll(any());
    TransactionOperations rollingBack =
        new TransactionOperations() {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            try {
              T result = action.doInTransaction(null);
              events.add("commit");
              return result;
            } catch (RuntimeException e) {
              events.add("rollback");
              throw e;
            }
          }
        };

    new CustomerCancelService(
            customers,
            catalogs,
            appointments,
            reservations,
            locks,
            reads,
            outbox,
            ledger,
            clock,
            rollingBack)
        .cancel(alice, APPOINTMENT);

    assertThat(events).containsExactly("lock2", "rollback", "lock2", "commit");
  }

  @Test
  void attemptsAreCappedThenTheDiagnosisIsReturned() {
    long[] version = {3};
    when(appointments.findState(APPOINTMENT))
        .thenAnswer(
            inv ->
                Optional.of(
                    state(Fixtures.ALICE, AppointmentStatus.BOOKED, version[0]++, WEDNESDAY_9)));
    when(appointments.markCancelled(any(), any(), any())).thenReturn(OptionalLong.empty());

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(VersionConflictException.class);

    verify(locks, org.mockito.Mockito.times(CustomerCancelService.MAX_ATTEMPTS)).lockAll(any());
    verify(outbox, never())
        .append(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
  }

  @Test
  void deadlockIsRetriedOnceFromTheTop() {
    when(appointments.markCancelled(APPOINTMENT, Fixtures.ALICE, OptionalLong.of(3)))
        .thenThrow(new RuntimeException(new java.sql.SQLException("deadlock", "40P01")))
        .thenReturn(OptionalLong.of(4));

    assertThat(service().cancel(alice, APPOINTMENT)).isSameAs(cancelledView);
    verify(locks, org.mockito.Mockito.times(2)).lockAll(any());
  }

  @Test
  void secondDeadlockPropagates() {
    when(appointments.markCancelled(any(), any(), any()))
        .thenThrow(new RuntimeException(new java.sql.SQLException("deadlock", "40P01")));

    assertThatThrownBy(() -> service().cancel(alice, APPOINTMENT))
        .isInstanceOf(RuntimeException.class);
    verify(locks, org.mockito.Mockito.times(2)).lockAll(any());
  }
}
