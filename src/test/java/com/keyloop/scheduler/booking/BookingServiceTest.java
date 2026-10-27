package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.RequestInProgressException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.customer.VehicleView;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

class BookingServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final OffsetDateTime MONDAY_9 = OffsetDateTime.parse("2026-11-02T09:00:00Z");
  private static final String KEY = "key-1";
  private static final UUID DISPATCHER_SUB = UUID.randomUUID();
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final CatalogService catalogs = mock(CatalogService.class);
  private final CustomerService customers = mock(CustomerService.class);
  private final AllocationService allocation = mock(AllocationService.class);
  private final OccupancyRepository occupancy = mock(OccupancyRepository.class);
  private final OccupancySource dailyOccupancy = mock(OccupancySource.class);
  private final DbClock clock = mock(DbClock.class);
  private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
  private final AppointmentRepository appointments = mock(AppointmentRepository.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final List<AppointmentCommitted> committed = new ArrayList<>();
  private BookingListener listener = committed::add;

  private final CallerContext customer =
      new CallerContext(Fixtures.ALICE_SUB, Set.of(Role.CUSTOMER), Set.of());
  private final CallerContext dispatcher =
      new CallerContext(DISPATCHER_SUB, Set.of(Role.DISPATCHER), Set.of(Fixtures.RIVERSIDE));

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

  private final List<ResourcePair> ranked =
      List.of(
          new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM),
          new ResourcePair(Fixtures.RIVERSIDE_BAY_2, Fixtures.SAM),
          new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.TOM),
          new ResourcePair(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM));

  private BookingService service() {
    return new BookingService(
        catalogs,
        customers,
        allocation,
        occupancy,
        dailyOccupancy,
        clock,
        TransactionOperations.withoutTransaction(),
        idempotency,
        appointments,
        reservations,
        outbox,
        listener,
        JSON);
  }

  private static BookingRequest request(OffsetDateTime start) {
    return new BookingRequest(
        Fixtures.RIVERSIDE,
        null,
        Fixtures.ALICE_FOCUS,
        List.of(Fixtures.OIL_CHANGE, Fixtures.BRAKE_INSPECTION),
        start,
        "Squeak",
        null,
        null,
        null,
        false);
  }

  private static BookingRequest dispatcherRequest(UUID bay, UUID technician) {
    return new BookingRequest(
        Fixtures.RIVERSIDE,
        Fixtures.ALICE,
        Fixtures.ALICE_FOCUS,
        List.of(Fixtures.OIL_CHANGE),
        MONDAY_9,
        null,
        bay,
        technician,
        null,
        false);
  }

  private static Map<UUID, ReservationRow> inserted() {
    return new LinkedHashMap<>(Map.of(UUID.randomUUID(), row(Fixtures.SAM)));
  }

  private static ReservationRow row(UUID resource) {
    return new ReservationRow(
        resource,
        ReservationRow.TECHNICIAN,
        new TimeRange(MONDAY_9.toInstant(), MONDAY_9.toInstant().plusSeconds(3600)));
  }

  private static CannotAcquireLockException deadlock() {
    return new CannotAcquireLockException(
        "deadlock", new SQLException("deadlock detected", "40P01"));
  }

  @SuppressWarnings("unchecked")
  private List<ReservationRow> rowsOfCall(int index, int calls) {
    ArgumentCaptor<List<ReservationRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(reservations, times(calls)).insertAll(any(), eq(Fixtures.RIVERSIDE), rows.capture());
    return rows.getAllValues().get(index);
  }

  @BeforeEach
  void stub() {
    when(catalogs.get(Fixtures.RIVERSIDE)).thenReturn(catalog);
    when(customers.currentCustomer(customer)).thenReturn(Fixtures.ALICE);
    VehicleView focus =
        new VehicleView(Fixtures.ALICE_FOCUS, "VIN", "ALI 1", "Ford", "Focus", 2018, 1);
    when(customers.requireOwnedVehicle(Fixtures.ALICE, Fixtures.ALICE_FOCUS)).thenReturn(focus);
    when(customers.requireVehicleOf(Fixtures.ALICE, Fixtures.ALICE_FOCUS)).thenReturn(focus);
    when(clock.now()).thenReturn(NOW);
    when(occupancy.load(any(), any())).thenReturn(Occupancy.empty());
    when(dailyOccupancy.forDay(any(), any(), any())).thenReturn(Occupancy.empty());
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(ranked);
    when(idempotency.find(any(), anyString(), anyString())).thenReturn(Optional.empty());
    when(reservations.insertAll(any(), any(), any())).thenReturn(inserted());
  }

  @Test
  void firstPairSucceedsWithOneInsertAndOneListenerCallAfterCommit() {
    BookingResult result =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(result.httpStatus()).isEqualTo(201);
    assertThat(result.replayed()).isFalse();
    AppointmentView view = result.body();
    assertThat(view.status()).isEqualTo("BOOKED");
    assertThat(view.version()).isZero();
    assertThat(view.start()).isEqualTo(MONDAY_9.toInstant());
    assertThat(view.end()).isEqualTo(Instant.parse("2026-11-02T11:00:00Z"));
    assertThat(view.technician().displayName()).isEqualTo("Sam");
    assertThat(view.serviceBay().name()).isEqualTo("Bay 1");
    assertThat(view.vehicle().registration()).isEqualTo("ALI 1");
    assertThat(view.dealership().timeZone()).isEqualTo("Europe/London");
    assertThat(view.services())
        .extracting(AppointmentView.Service::code)
        .containsExactly("OIL_CHANGE", "BRAKE_INSPECTION");

    List<ReservationRow> rows = rowsOfCall(0, 1);
    TimeRange period = new TimeRange(view.start(), view.end());
    assertThat(rows)
        .containsExactlyInAnyOrder(
            new ReservationRow(Fixtures.RIVERSIDE_BAY_1, ReservationRow.BAY, period),
            new ReservationRow(Fixtures.SAM, ReservationRow.TECHNICIAN, period));
    ArgumentCaptor<NewAppointment> appointment = ArgumentCaptor.forClass(NewAppointment.class);
    InOrder order = inOrder(idempotency, appointments, reservations, outbox);
    order.verify(idempotency).begin(eq(Fixtures.ALICE_SUB), eq(KEY), anyString());
    order.verify(appointments).insert(appointment.capture());
    order.verify(reservations).insertAll(eq(view.id()), eq(Fixtures.RIVERSIDE), any());
    order
        .verify(outbox)
        .append(
            eq("Appointment"),
            eq(view.id()),
            eq(Fixtures.RIVERSIDE),
            eq("AppointmentBooked"),
            eq(0L),
            any(AppointmentBookedEvent.class));
    order.verify(idempotency).complete(Fixtures.ALICE_SUB, KEY, 201, view);
    assertThat(appointment.getValue().customerId()).isEqualTo(Fixtures.ALICE);
    assertThat(appointment.getValue().createdBy()).isEqualTo(Fixtures.ALICE_SUB);
    assertThat(appointment.getValue().customerNotes()).isEqualTo("Squeak");
    assertThat(committed).hasSize(1);
    assertThat(committed.get(0).appointmentId()).isEqualTo(view.id());
    assertThat(committed.get(0).dealershipId()).isEqualTo(Fixtures.RIVERSIDE);
    assertThat(committed.get(0).localDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(committed.get(0).reservations()).hasSize(1);
  }

  @Test
  void bayReservationIncludesTheBuffer() {
    ResourceCatalog hudson =
        new ResourceCatalog(
            CatalogFixtures.hudson(),
            catalog.services(),
            List.of(new BayView(Fixtures.HUDSON_BAY_1, "Bay 1")),
            List.of(new TechnicianView(Fixtures.JO, "Jo", Set.of("OIL"))));
    when(catalogs.get(Fixtures.HUDSON)).thenReturn(hudson);
    when(allocation.rank(eq(hudson), any(), any(), any(), any()))
        .thenReturn(List.of(new ResourcePair(Fixtures.HUDSON_BAY_1, Fixtures.JO)));
    BookingRequest r =
        new BookingRequest(
            Fixtures.HUDSON,
            null,
            Fixtures.ALICE_FOCUS,
            List.of(Fixtures.OIL_CHANGE),
            OffsetDateTime.parse("2026-11-02T10:00:00-05:00"),
            null,
            null,
            null,
            null,
            false);

    service().book(customer, BookingChannel.CUSTOMER, r, KEY);

    ArgumentCaptor<List<ReservationRow>> rows = rowsCaptor();
    verify(reservations).insertAll(any(), eq(Fixtures.HUDSON), rows.capture());
    Instant start = Instant.parse("2026-11-02T15:00:00Z");
    assertThat(rows.getValue())
        .containsExactlyInAnyOrder(
            new ReservationRow(
                Fixtures.HUDSON_BAY_1,
                ReservationRow.BAY,
                new TimeRange(start, start.plusSeconds(55 * 60))),
            new ReservationRow(
                Fixtures.JO,
                ReservationRow.TECHNICIAN,
                new TimeRange(start, start.plusSeconds(45 * 60))));
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<List<ReservationRow>> rowsCaptor() {
    return ArgumentCaptor.forClass(List.class);
  }

  @Test
  void conflictOnFirstPairTriesNextPairWithoutTheConflictingResource() {
    when(reservations.insertAll(any(), any(), any()))
        .thenThrow(new ReservationConflictException(Fixtures.SAM))
        .thenReturn(inserted());

    BookingResult result =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(result.body().technician().id()).isEqualTo(Fixtures.TOM);
    assertThat(result.body().serviceBay().id()).isEqualTo(Fixtures.RIVERSIDE_BAY_1);
    assertThat(rowsOfCall(1, 2))
        .extracting(ReservationRow::resourceId)
        .doesNotContain(Fixtures.SAM)
        .contains(Fixtures.TOM);
    assertThat(committed).hasSize(1);
  }

  @Test
  void threeConflictsAreSlotUnavailableWithAlternativesAndNoListenerCall() {
    List<ResourcePair> five = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      five.add(new ResourcePair(UUID.randomUUID(), UUID.randomUUID()));
    }
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(five);
    when(reservations.insertAll(any(), any(), any()))
        .thenThrow(new ReservationConflictException(five.get(0).bayId()))
        .thenThrow(new ReservationConflictException(five.get(1).bayId()))
        .thenThrow(new ReservationConflictException(five.get(2).bayId()));

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOfSatisfying(
            SlotUnavailableException.class,
            e -> {
              assertThat(e.getMessage())
                  .isEqualTo(
                      "No qualified technician and bay are free at 09:00 on Mon 2 Nov"
                          + " (Europe/London).");
              assertThat(e.extras()).doesNotContainKey("conflicts");
              @SuppressWarnings("unchecked")
              List<Instant> alternatives = (List<Instant>) e.extras().get("alternatives");
              assertThat(alternatives)
                  .hasSize(5)
                  .first()
                  .isEqualTo(Instant.parse("2026-11-02T09:15:00Z"));
            });
    verify(reservations, times(3)).insertAll(any(), any(), any());
    verify(idempotency, never()).complete(any(), any(), anyInt(), any());
    assertThat(committed).isEmpty();
  }

  @Test
  void noCandidatesIsSlotUnavailableWithoutAnyInsert() {
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(List.of());

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOf(SlotUnavailableException.class);
    verifyNoInteractions(appointments, reservations, outbox);
    verify(idempotency, never()).begin(any(), any(), any());
    assertThat(committed).isEmpty();
  }

  @Test
  void deadlockOnceIsRetriedWithTheSamePair() {
    when(reservations.insertAll(any(), any(), any())).thenThrow(deadlock()).thenReturn(inserted());

    BookingResult result =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(result.httpStatus()).isEqualTo(201);
    assertThat(rowsOfCall(1, 2)).isEqualTo(rowsOfCall(0, 2));
    assertThat(committed).hasSize(1);
  }

  @Test
  void deadlockTwiceIsPropagated() {
    when(reservations.insertAll(any(), any(), any())).thenThrow(deadlock()).thenThrow(deadlock());

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOf(CannotAcquireLockException.class);
    verify(reservations, times(2)).insertAll(any(), any(), any());
    assertThat(committed).isEmpty();
  }

  @Test
  void otherDatabaseFailuresPropagateWithoutRetry() {
    when(reservations.insertAll(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("fk"));

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(reservations, times(1)).insertAll(any(), any(), any());
  }

  @Test
  void storedResponseIsReplayedWithoutAllocating() throws Exception {
    AppointmentView stored =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY).body();
    committed.clear();
    org.mockito.Mockito.clearInvocations(
        catalogs, allocation, reservations, appointments, outbox, idempotency);
    when(idempotency.find(eq(Fixtures.ALICE_SUB), eq(KEY), anyString()))
        .thenReturn(Optional.of(new StoredResponse(201, JSON.writeValueAsString(stored))));

    BookingResult replay =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.httpStatus()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(stored);
    verifyNoInteractions(catalogs, allocation, reservations, appointments, outbox);
    assertThat(committed).isEmpty();
  }

  @Test
  void hashIsComputedFromChannelAndRequest() {
    service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    verify(idempotency)
        .find(Fixtures.ALICE_SUB, KEY, RequestHash.of(BookingChannel.CUSTOMER, request(MONDAY_9)));
  }

  @Test
  void concurrentDuplicateReplaysTheWinnersResponse() throws Exception {
    AppointmentView winner =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY).body();
    committed.clear();
    doThrow(new DuplicateRequestException()).when(idempotency).begin(any(), any(), any());
    when(idempotency.find(eq(Fixtures.ALICE_SUB), eq(KEY), anyString()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(new StoredResponse(201, JSON.writeValueAsString(winner))));

    BookingResult result =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(result.replayed()).isTrue();
    assertThat(result.body().id()).isEqualTo(winner.id());
    assertThat(committed).isEmpty();
  }

  @Test
  void duplicateWithoutStoredResponseIsInProgress() {
    doThrow(new DuplicateRequestException()).when(idempotency).begin(any(), any(), any());

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOfSatisfying(
            RequestInProgressException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
  }

  @Test
  void unreadableStoredResponseFailsLoudly() {
    when(idempotency.find(any(), anyString(), anyString()))
        .thenReturn(Optional.of(new StoredResponse(201, "{not json")));

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void policyViolationTouchesNoRepository() {
    OffsetDateTime tooSoon = OffsetDateTime.parse("2026-11-01T11:00:00Z");

    assertThatThrownBy(
            () -> service().book(customer, BookingChannel.CUSTOMER, request(tooSoon), KEY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.OUTSIDE_BOOKING_WINDOW));
    verifyNoInteractions(occupancy, dailyOccupancy, allocation, appointments, reservations, outbox);
    verify(idempotency, never()).begin(any(), any(), any());
  }

  @Test
  void idempotencyKeyMustBeOneToSixtyFourCharacters() {
    for (String bad : new String[] {null, "", " ", "x".repeat(65)}) {
      assertThatThrownBy(
              () -> service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), bad))
          .isInstanceOf(ValidationException.class);
    }
    assertThat(
            service()
                .book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), "x".repeat(64))
                .httpStatus())
        .isEqualTo(201);
  }

  @Test
  void dispatcherBooksForNamedCustomerWithPinsAndOwnPolicy() {
    when(allocation.rank(any(), any(), any(), any(), any()))
        .thenReturn(List.of(new ResourcePair(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM)));
    // 15 minutes ago is fine for a walk-in
    BookingRequest walkIn =
        new BookingRequest(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            List.of(Fixtures.OIL_CHANGE),
            OffsetDateTime.parse("2026-11-02T09:00:00Z"),
            null,
            Fixtures.RIVERSIDE_BAY_2,
            Fixtures.TOM,
            null,
            false);
    when(clock.now()).thenReturn(Instant.parse("2026-11-02T09:10:00Z"));

    BookingResult result = service().book(dispatcher, BookingChannel.DISPATCHER, walkIn, KEY);

    assertThat(result.body().technician().id()).isEqualTo(Fixtures.TOM);
    verify(allocation)
        .rank(
            eq(catalog),
            any(),
            any(),
            eq(Optional.of(Fixtures.RIVERSIDE_BAY_2)),
            eq(Optional.of(Fixtures.TOM)));
    verify(customers).requireVehicleOf(Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    verify(customers, never()).currentCustomer(any());
    ArgumentCaptor<NewAppointment> appointment = ArgumentCaptor.forClass(NewAppointment.class);
    verify(appointments).insert(appointment.capture());
    assertThat(appointment.getValue().createdBy()).isEqualTo(DISPATCHER_SUB);
    assertThat(appointment.getValue().customerId()).isEqualTo(Fixtures.ALICE);
  }

  @Test
  void dispatcherSlotUnavailableNamesConflicts() {
    UUID conflicting = UUID.randomUUID();
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reservations.conflictingAppointments(any(), any(), any(), any(), any()))
        .thenReturn(List.of(conflicting));

    assertThatThrownBy(
            () ->
                service()
                    .book(
                        dispatcher,
                        BookingChannel.DISPATCHER,
                        dispatcherRequest(null, Fixtures.TOM),
                        KEY))
        .isInstanceOfSatisfying(
            SlotUnavailableException.class,
            e -> assertThat(e.extras()).containsEntry("conflicts", List.of(conflicting)));
    TimeRange period =
        new TimeRange(MONDAY_9.toInstant(), MONDAY_9.toInstant().plusSeconds(45 * 60));
    verify(reservations)
        .conflictingAppointments(
            Fixtures.RIVERSIDE,
            List.of(Fixtures.TOM),
            period,
            List.of(Fixtures.RIVERSIDE_BAY_1, Fixtures.RIVERSIDE_BAY_2),
            period);
  }

  @Test
  void dispatcherConflictsWithoutPinsCoverQualifiedTechniciansAndPinnedBay() {
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reservations.conflictingAppointments(any(), any(), any(), any(), any()))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service()
                    .book(
                        dispatcher,
                        BookingChannel.DISPATCHER,
                        dispatcherRequest(Fixtures.RIVERSIDE_BAY_1, null),
                        KEY))
        .isInstanceOf(SlotUnavailableException.class);
    verify(reservations)
        .conflictingAppointments(
            eq(Fixtures.RIVERSIDE),
            eq(List.of(Fixtures.SAM, Fixtures.TOM)),
            any(),
            eq(List.of(Fixtures.RIVERSIDE_BAY_1)),
            any());
  }

  @Test
  void unknownPinnedTechnicianIsNotFound() {
    assertThatThrownBy(
            () ->
                service()
                    .book(
                        dispatcher,
                        BookingChannel.DISPATCHER,
                        dispatcherRequest(null, Fixtures.JO),
                        KEY))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            e -> assertThat(e.getMessage()).isEqualTo("Technician not found."));
    verifyNoInteractions(allocation, reservations);
  }

  @Test
  void unknownPinnedBayIsNotFound() {
    assertThatThrownBy(
            () ->
                service()
                    .book(
                        dispatcher,
                        BookingChannel.DISPATCHER,
                        dispatcherRequest(Fixtures.HUDSON_BAY_1, null),
                        KEY))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            e -> assertThat(e.getMessage()).isEqualTo("Service bay not found."));
  }

  @Test
  void failingListenerDoesNotFailTheCommittedBooking() {
    listener =
        event -> {
          throw new IllegalStateException("redis down");
        };

    BookingResult result =
        service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    assertThat(result.httpStatus()).isEqualTo(201);
  }

  @Test
  void occupancyForRankingCoversTheWholeLocalDayOnThePrimary() {
    service().book(customer, BookingChannel.CUSTOMER, request(MONDAY_9), KEY);

    verify(occupancy)
        .load(
            Fixtures.RIVERSIDE,
            new TimeRange(
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z")));
  }
}
