package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.customer.VehicleView;
import com.keyloop.scheduler.hold.HoldPayload;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionOperations;

/** The confirm path: {@code POST /me/appointments} with a {@code holdId}. */
class BookingServiceHoldPathTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final OffsetDateTime MONDAY_9 = OffsetDateTime.parse("2026-11-02T09:00:00Z");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final UUID HOLD = UUID.fromString("0192f0b9-0000-7000-8000-000000000001");
  private static final ResourcePair HELD = new ResourcePair(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM);
  private static final TimeRange OIL_AT_9 =
      new TimeRange(MONDAY_9.toInstant(), Instant.parse("2026-11-02T09:45:00Z"));

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
  private final SlotLedger ledger = mock(SlotLedger.class);
  private final List<AppointmentCommitted> committed = new ArrayList<>();

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
  private final List<ResourcePair> ranked =
      List.of(
          new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM),
          new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.TOM));

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
        committed::add,
        new ObjectMapper().findAndRegisterModules(),
        ledger);
  }

  private static BookingRequest request(OffsetDateTime start, List<UUID> services) {
    return new BookingRequest(
        Fixtures.RIVERSIDE,
        null,
        Fixtures.ALICE_FOCUS,
        services,
        start,
        null,
        null,
        null,
        HOLD,
        false);
  }

  private static BookingRequest oilAt9() {
    return request(MONDAY_9, List.of(Fixtures.OIL_CHANGE));
  }

  private static StoredHold hold(UUID userId, TimeRange period, List<UUID> services) {
    return new StoredHold(
        HOLD,
        userId,
        HELD,
        period,
        period,
        HoldPayload.of(Fixtures.ALICE_FOCUS, services).toJson(),
        NOW.plusSeconds(600));
  }

  private void holdIs(StoredHold stored) {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.RIVERSIDE, MONDAY)));
    when(ledger.getHold(Fixtures.RIVERSIDE, MONDAY, HOLD)).thenReturn(Optional.of(stored));
  }

  @BeforeEach
  void stub() {
    when(catalogs.get(Fixtures.RIVERSIDE)).thenReturn(catalog);
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(customers.requireOwnedVehicle(Fixtures.ALICE, Fixtures.ALICE_FOCUS))
        .thenReturn(
            new VehicleView(Fixtures.ALICE_FOCUS, "VIN", "ALI 1", "Ford", "Focus", 2018, 1));
    when(clock.now()).thenReturn(NOW);
    when(occupancy.load(any(), any())).thenReturn(Occupancy.empty());
    when(dailyOccupancy.forDay(any(), any(), any(), any())).thenReturn(Occupancy.empty());
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(ranked);
    when(idempotency.find(any(), anyString(), anyString())).thenReturn(Optional.empty());
    when(reservations.insertAll(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Map<UUID, ReservationRow> rows = new LinkedHashMap<>();
              List<ReservationRow> requested = inv.getArgument(2);
              requested.forEach(r -> rows.put(UUID.randomUUID(), r));
              return rows;
            });
  }

  @SuppressWarnings("unchecked")
  private List<List<ReservationRow>> inserts(int calls) {
    ArgumentCaptor<List<ReservationRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(reservations, times(calls)).insertAll(any(), any(), rows.capture());
    return rows.getAllValues();
  }

  @Test
  void matchingHoldBooksTheHeldPairWithoutRanking() {
    holdIs(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));

    BookingResult result = service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k");

    assertThat(result.httpStatus()).isEqualTo(201);
    assertThat(result.body().serviceBay().id()).isEqualTo(Fixtures.RIVERSIDE_BAY_2);
    assertThat(result.body().technician().id()).isEqualTo(Fixtures.TOM);
    assertThat(inserts(1).get(0))
        .extracting(ReservationRow::resourceId)
        .containsExactlyInAnyOrder(Fixtures.RIVERSIDE_BAY_2, Fixtures.TOM);
    verify(allocation, never()).rank(any(), any(), any(), any(), any());
    verify(occupancy, never()).load(any(), any());
    assertThat(committed).singleElement().satisfies(e -> assertThat(e.holdId()).isEqualTo(HOLD));
  }

  @Test
  void holdOfAnotherUserIsNotFound() {
    holdIs(hold(Fixtures.BOB_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));

    assertThatThrownBy(() -> service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k"))
        .isInstanceOfSatisfying(
            NotFoundException.class, e -> assertThat(e.getMessage()).isEqualTo("Hold not found."));
    verify(reservations, never()).insertAll(any(), any(), any());
  }

  @Test
  void differentStartIsHoldMismatch() {
    holdIs(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));

    assertThatThrownBy(
            () ->
                service()
                    .book(
                        alice,
                        BookingChannel.CUSTOMER,
                        request(
                            OffsetDateTime.parse("2026-11-02T10:00:00Z"),
                            List.of(Fixtures.OIL_CHANGE)),
                        "k"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "Your hold is for 09:00 on Mon 2 Nov with Oil change; this request asks"
                          + " for 10:00 on Mon 2 Nov with Oil change.");
            });
    verify(reservations, never()).insertAll(any(), any(), any());
  }

  @Test
  void differentServicesIsHoldMismatch() {
    holdIs(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));

    assertThatThrownBy(
            () ->
                service()
                    .book(
                        alice,
                        BookingChannel.CUSTOMER,
                        request(MONDAY_9, List.of(Fixtures.BRAKE_INSPECTION)),
                        "k"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH));
  }

  @Test
  void sameServicesInAnotherOrderStillMatch() {
    TimeRange both = new TimeRange(MONDAY_9.toInstant(), Instant.parse("2026-11-02T11:00:00Z"));
    holdIs(hold(Fixtures.ALICE_SUB, both, List.of(Fixtures.BRAKE_INSPECTION, Fixtures.OIL_CHANGE)));

    BookingResult result =
        service()
            .book(
                alice,
                BookingChannel.CUSTOMER,
                request(MONDAY_9, List.of(Fixtures.OIL_CHANGE, Fixtures.BRAKE_INSPECTION)),
                "k");

    assertThat(result.httpStatus()).isEqualTo(201);
  }

  @Test
  void holdAtAnotherDealershipIsHoldMismatch() {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.HUDSON, MONDAY)));
    when(ledger.getHold(Fixtures.HUDSON, MONDAY, HOLD))
        .thenReturn(Optional.of(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE))));

    assertThatThrownBy(() -> service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.HOLD_MISMATCH);
              assertThat(e.getMessage()).isEqualTo("Your hold is for another dealership.");
            });
  }

  @Test
  void expiredHoldFallsBackToRankedPairs() {
    when(ledger.locate(HOLD)).thenReturn(Optional.empty());

    BookingResult result = service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k");

    assertThat(result.httpStatus()).isEqualTo(201);
    verify(allocation).rank(any(), any(), any(), any(), any());
    assertThat(inserts(1).get(0))
        .extracting(ReservationRow::resourceId)
        .containsExactlyInAnyOrder(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM);
    assertThat(committed).singleElement().satisfies(e -> assertThat(e.holdId()).isNull());
  }

  @Test
  void holdGoneAfterLocateFallsBackToRankedPairs() {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.RIVERSIDE, MONDAY)));
    when(ledger.getHold(Fixtures.RIVERSIDE, MONDAY, HOLD)).thenReturn(Optional.empty());

    service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k");

    verify(allocation).rank(any(), any(), any(), any(), any());
  }

  @Test
  void unreadableLedgerFallsBackToRankedPairs() {
    when(ledger.locate(HOLD)).thenThrow(new RedisConnectionFailureException("down"));

    BookingResult result = service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k");

    assertThat(result.httpStatus()).isEqualTo(201);
    verify(allocation).rank(any(), any(), any(), any(), any());
  }

  @Test
  void takenHeldPairIsOneAttemptThenSlotUnavailableAndTheLedgerIsMarkedDirty() {
    holdIs(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));
    doThrow(new ReservationConflictException(Fixtures.TOM))
        .when(reservations)
        .insertAll(any(), any(), any());

    assertThatThrownBy(() -> service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k"))
        .isInstanceOf(SlotUnavailableException.class);

    inserts(1);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, MONDAY);
    verify(dailyOccupancy)
        .forDay(eq(Fixtures.RIVERSIDE), eq(MONDAY), any(), eq(Fixtures.ALICE_SUB));
    assertThat(committed).isEmpty();
  }

  @Test
  void conflictOnTheDirectPathAlsoMarksTheLedgerDirty() {
    BookingRequest direct =
        new BookingRequest(
            Fixtures.RIVERSIDE,
            null,
            Fixtures.ALICE_FOCUS,
            List.of(Fixtures.OIL_CHANGE),
            MONDAY_9,
            null,
            null,
            null,
            null,
            false);
    doThrow(new ReservationConflictException(Fixtures.SAM))
        .doAnswer(inv -> Map.of(UUID.randomUUID(), inv.<List<ReservationRow>>getArgument(2).get(0)))
        .when(reservations)
        .insertAll(any(), any(), any());

    BookingResult result = service().book(alice, BookingChannel.CUSTOMER, direct, "k");

    assertThat(result.httpStatus()).isEqualTo(201);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, MONDAY);
    verify(ledger, never()).locate(any());
  }

  @Test
  void ledgerFailureWhileMarkingDirtyDoesNotHideTheConflict() {
    holdIs(hold(Fixtures.ALICE_SUB, OIL_AT_9, List.of(Fixtures.OIL_CHANGE)));
    doThrow(new ReservationConflictException(Fixtures.TOM))
        .when(reservations)
        .insertAll(any(), any(), any());
    doThrow(new RedisConnectionFailureException("down")).when(ledger).markDirty(any(), any());

    assertThatThrownBy(() -> service().book(alice, BookingChannel.CUSTOMER, oilAt9(), "k"))
        .isInstanceOf(SlotUnavailableException.class);
  }
}
