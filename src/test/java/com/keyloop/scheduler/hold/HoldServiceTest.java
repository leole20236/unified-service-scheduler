package com.keyloop.scheduler.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.booking.AllocationService;
import com.keyloop.scheduler.booking.Occupancy;
import com.keyloop.scheduler.booking.ResourcePair;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.HoldsUnavailableException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.customer.VehicleView;
import com.keyloop.scheduler.hold.SlotLedger.HeldPair;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

class HoldServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final OffsetDateTime MONDAY_9 = OffsetDateTime.parse("2026-11-02T09:00:00Z");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Instant EXPIRES = Instant.parse("2026-11-01T10:10:00Z");
  private static final UUID HOLD = UUID.fromString("0192f0b9-0000-7000-8000-000000000001");
  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");
  private static final ResourcePair PAIR = new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM);
  private static final TimeRange PERIOD =
      new TimeRange(MONDAY_9.toInstant(), Instant.parse("2026-11-02T09:45:00Z"));

  private final SlotLedger ledger = mock(SlotLedger.class);
  private final AllocationService allocation = mock(AllocationService.class);
  private final CatalogService catalogs = mock(CatalogService.class);
  private final CustomerService customers = mock(CustomerService.class);
  private final com.keyloop.scheduler.booking.AppointmentRepository appointments =
      mock(com.keyloop.scheduler.booking.AppointmentRepository.class);
  private final com.keyloop.scheduler.booking.ReservationRepository reservations =
      mock(com.keyloop.scheduler.booking.ReservationRepository.class);
  private final HoldService service =
      new HoldService(ledger, allocation, catalogs, customers, appointments, reservations);

  private final CallerContext alice =
      new CallerContext(Fixtures.ALICE_SUB, Set.of(Role.CUSTOMER), Set.of());
  private final ResourceCatalog catalog =
      new ResourceCatalog(
          CatalogFixtures.riverside(),
          List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
          List.of(new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
          List.of(new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL", "BRAKES"))));
  private final VehicleView focus =
      new VehicleView(Fixtures.ALICE_FOCUS, "VIN", "ALI 1", "Ford", "Focus", 2018, 1);

  private static HoldRequest request(OffsetDateTime start, List<UUID> services) {
    return new HoldRequest(Fixtures.RIVERSIDE, Fixtures.ALICE_FOCUS, services, start, null);
  }

  private static HoldRequest oilAt(OffsetDateTime start) {
    return request(start, List.of(Fixtures.OIL_CHANGE));
  }

  @BeforeEach
  void stub() {
    when(customers.findMyVehicle(alice, Fixtures.ALICE_FOCUS)).thenReturn(Optional.of(focus));
    when(catalogs.get(Fixtures.RIVERSIDE)).thenReturn(catalog);
    when(ledger.now()).thenReturn(NOW);
    when(ledger.occupancy(any(), any(), anyBoolean(), any())).thenReturn(Occupancy.empty());
    when(allocation.rank(any(), any(), any(), any(), any())).thenReturn(List.of(PAIR));
  }

  @Test
  void grantedHoldReturnsItsTimesAndExpiry() {
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenReturn(Optional.of(new HeldPair(HOLD, PAIR, EXPIRES)));

    HoldView view = service.create(alice, oilAt(MONDAY_9));

    assertThat(view.holdId()).isEqualTo(HOLD);
    assertThat(view.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-11-01T10:10:00Z"));
    assertThat(view.start()).isEqualTo(MONDAY_9);
    assertThat(view.end()).isEqualTo(OffsetDateTime.parse("2026-11-02T09:45:00Z"));
    verify(ledger)
        .ensureLoaded(
            Fixtures.RIVERSIDE, MONDAY, SlotLedger.dayWindow(CatalogFixtures.riverside(), MONDAY));
    verify(ledger).occupancy(Fixtures.RIVERSIDE, MONDAY, true, Fixtures.ALICE_SUB);
    verify(ledger)
        .acquire(
            eq(Fixtures.RIVERSIDE),
            eq(MONDAY),
            eq(Fixtures.ALICE_SUB),
            eq(List.of(PAIR)),
            eq(PERIOD),
            eq(PERIOD),
            eq(HoldPayload.of(Fixtures.ALICE_FOCUS, List.of(Fixtures.OIL_CHANGE)).toJson()),
            eq(java.util.Set.of()));
    verify(customers, never()).getMyVehicle(any(), any());
  }

  @Test
  void dayWindowCoversTheLocalDayPlusTheBayBuffer() {
    TimeRange window = SlotLedger.dayWindow(CatalogFixtures.hudson(), MONDAY);

    assertThat(window.start()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
    assertThat(window.end()).isEqualTo(Instant.parse("2026-11-03T05:10:00Z"));
  }

  @Test
  void noFreePairIsSlotUnavailableWithAlternativesFromTheLedger() {
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenReturn(Optional.empty());
    Occupancy samBusyTillTen =
        new Occupancy(
            Map.of(
                Fixtures.SAM,
                List.of(
                    new TimeRange(MONDAY_9.toInstant(), Instant.parse("2026-11-02T10:00:00Z")))));
    when(ledger.occupancy(Fixtures.RIVERSIDE, MONDAY, true, Fixtures.ALICE_SUB))
        .thenReturn(samBusyTillTen);

    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOfSatisfying(
            SlotUnavailableException.class,
            e -> {
              assertThat(e.getMessage())
                  .isEqualTo(
                      "No qualified technician and bay are free at 09:00 on Mon 2 Nov"
                          + " (Europe/London).");
              assertThat(e.extras().get("alternatives"))
                  .isEqualTo(
                      List.of(
                          Instant.parse("2026-11-02T10:00:00Z"),
                          Instant.parse("2026-11-02T10:15:00Z"),
                          Instant.parse("2026-11-02T10:30:00Z"),
                          Instant.parse("2026-11-02T10:45:00Z"),
                          Instant.parse("2026-11-02T11:00:00Z")));
              assertThat(e.extras()).doesNotContainKey("conflicts");
            });
  }

  @Test
  void redisConnectionFailureIsHoldsUnavailable() {
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOfSatisfying(
            HoldsUnavailableException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCodes.HOLDS_UNAVAILABLE);
              assertThat(e.getMessage())
                  .isEqualTo("Holds are unavailable right now; you can still book directly.");
              assertThat(e.retryAfterSeconds()).isEqualTo(30);
            });
  }

  @Test
  void redisTimeoutsAndScriptErrorsAreHoldsUnavailable() {
    when(ledger.now()).thenThrow(new QueryTimeoutException("slow"));
    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOf(HoldsUnavailableException.class);

    doReturn(NOW).when(ledger).now();
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenThrow(new RedisSystemException("ERR script", new RuntimeException()));
    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOf(HoldsUnavailableException.class);
  }

  @Test
  void ledgerThatCannotBeRebuiltIsHoldsUnavailable() {
    doThrow(new LedgerUnavailableException("overtaken"))
        .when(ledger)
        .ensureLoaded(any(), any(), any());

    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOf(HoldsUnavailableException.class);
    verify(ledger, never()).acquire(any(), any(), any(), any(), any(), any(), anyString(), any());
  }

  @Test
  void oneTimeoutIsRetried() {
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenThrow(new QueryTimeoutException("slow"))
        .thenReturn(Optional.of(new HeldPair(HOLD, PAIR, EXPIRES)));

    assertThat(service.create(alice, oilAt(MONDAY_9)).holdId()).isEqualTo(HOLD);
    verify(ledger, times(2)).acquire(any(), any(), any(), any(), any(), any(), anyString(), any());
  }

  @Test
  void connectionFailuresAreNotRetried() {
    when(ledger.locate(HOLD)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> service.get(alice, HOLD))
        .isInstanceOf(HoldsUnavailableException.class);
    verify(ledger, times(1)).locate(HOLD);
  }

  @Test
  void otherFailuresAreNotDisguisedAsHoldsUnavailable() {
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenThrow(new IllegalStateException("bug"));

    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidServicesNeverReachTheLedger() {
    assertThatThrownBy(() -> service.create(alice, request(MONDAY_9, List.of())))
        .isInstanceOf(ValidationException.class);

    verifyNoInteractions(ledger, allocation);
  }

  @Test
  void startOutsideTheBookingWindowNeverAcquires() {
    assertThatThrownBy(
            () -> service.create(alice, oilAt(OffsetDateTime.parse("2026-11-01T11:00:00Z"))))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.OUTSIDE_BOOKING_WINDOW));

    verify(ledger, never()).ensureLoaded(any(), any(), any());
    verify(ledger, never()).acquire(any(), any(), any(), any(), any(), any(), anyString(), any());
  }

  @Test
  void vehicleMissingOnTheReplicaIsCheckedOnThePrimary() {
    when(customers.findMyVehicle(alice, Fixtures.ALICE_FOCUS)).thenReturn(Optional.empty());
    when(customers.getMyVehicle(alice, Fixtures.ALICE_FOCUS))
        .thenThrow(new NotFoundException("Vehicle not found."));

    assertThatThrownBy(() -> service.create(alice, oilAt(MONDAY_9)))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(ledger);
  }

  private StoredHold stored(UUID userId) {
    return new StoredHold(
        HOLD,
        userId,
        PAIR,
        PERIOD,
        PERIOD,
        HoldPayload.of(Fixtures.ALICE_FOCUS, List.of(Fixtures.OIL_CHANGE)).toJson(),
        EXPIRES);
  }

  @Test
  void getReturnsTheCallersOwnHold() {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.RIVERSIDE, MONDAY)));
    when(ledger.getHold(Fixtures.RIVERSIDE, MONDAY, HOLD))
        .thenReturn(Optional.of(stored(Fixtures.ALICE_SUB)));

    HoldResource hold = service.get(alice, HOLD);

    assertThat(hold)
        .isEqualTo(
            new HoldResource(
                HOLD,
                Fixtures.RIVERSIDE,
                List.of(Fixtures.OIL_CHANGE),
                MONDAY_9,
                OffsetDateTime.parse("2026-11-02T09:45:00Z"),
                OffsetDateTime.parse("2026-11-01T10:10:00Z")));
  }

  @Test
  void getOfSomeoneElsesMissingOrExpiredHoldIsNotFound() {
    when(ledger.locate(HOLD)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(alice, HOLD)).isInstanceOf(NotFoundException.class);

    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.RIVERSIDE, MONDAY)));
    when(ledger.getHold(Fixtures.RIVERSIDE, MONDAY, HOLD)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(alice, HOLD)).isInstanceOf(NotFoundException.class);

    when(ledger.getHold(Fixtures.RIVERSIDE, MONDAY, HOLD))
        .thenReturn(Optional.of(stored(Fixtures.BOB_SUB)));
    assertThatThrownBy(() -> service.get(alice, HOLD))
        .isInstanceOfSatisfying(
            NotFoundException.class, e -> assertThat(e.getMessage()).isEqualTo("Hold not found."));
  }

  @Test
  void releaseOnlyEverReleasesTheCallersHold() {
    when(ledger.locate(HOLD)).thenReturn(Optional.of(new HoldLocation(Fixtures.RIVERSIDE, MONDAY)));

    service.release(alice, HOLD);

    verify(ledger).release(Fixtures.RIVERSIDE, MONDAY, HOLD, Fixtures.ALICE_SUB);
  }

  @Test
  void releaseOfAnUnknownHoldDoesNothing() {
    when(ledger.locate(HOLD)).thenReturn(Optional.empty());

    service.release(alice, HOLD);

    verify(ledger, never()).release(any(), any(), any(), any());
  }

  @Test
  void redisFailureOnGetOrReleaseIsHoldsUnavailable() {
    when(ledger.locate(HOLD)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> service.get(alice, HOLD))
        .isInstanceOf(HoldsUnavailableException.class);
    assertThatThrownBy(() -> service.release(alice, HOLD))
        .isInstanceOf(HoldsUnavailableException.class);
  }

  @Test
  void payloadRoundTrips() {
    HoldPayload payload = HoldPayload.of(Fixtures.ALICE_FOCUS, List.of(Fixtures.OIL_CHANGE));

    assertThat(HoldPayload.parse(payload.toJson())).isEqualTo(payload);
  }

  private static com.keyloop.scheduler.booking.AppointmentState appointment(
      UUID customer, UUID dealership, com.keyloop.scheduler.booking.AppointmentStatus status) {
    return new com.keyloop.scheduler.booking.AppointmentState(
        APPOINTMENT,
        customer,
        dealership,
        status,
        0,
        PERIOD,
        Fixtures.RIVERSIDE_BAY_1,
        Fixtures.SAM);
  }

  private HoldRequest rescheduleRequest() {
    return new HoldRequest(
        Fixtures.RIVERSIDE,
        Fixtures.ALICE_FOCUS,
        List.of(Fixtures.OIL_CHANGE),
        MONDAY_9,
        APPOINTMENT);
  }

  @Test
  void rescheduleHoldIgnoresTheOwnReservationsAndRemembersTheAppointment() {
    UUID reservation = UUID.randomUUID();
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                appointment(
                    Fixtures.ALICE,
                    Fixtures.RIVERSIDE,
                    com.keyloop.scheduler.booking.AppointmentStatus.BOOKED)));
    when(reservations.activeIds(APPOINTMENT)).thenReturn(List.of(reservation));
    when(ledger.occupancy(any(), any(), eq(true), any(), any())).thenReturn(Occupancy.empty());
    when(ledger.acquire(any(), any(), any(), any(), any(), any(), anyString(), any()))
        .thenReturn(Optional.of(new HeldPair(HOLD, PAIR, Instant.parse("2026-11-01T10:10:00Z"))));

    service.create(alice, rescheduleRequest());

    verify(ledger)
        .acquire(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(
                HoldPayload.of(Fixtures.ALICE_FOCUS, List.of(Fixtures.OIL_CHANGE), APPOINTMENT)
                    .toJson()),
            eq(java.util.Set.of(reservation)));
    verify(ledger).occupancy(any(), any(), eq(true), any(), eq(java.util.Set.of(reservation)));
  }

  @Test
  void rescheduleHoldForOthersOrMissingAppointmentIs404() {
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                appointment(
                    Fixtures.BOB,
                    Fixtures.RIVERSIDE,
                    com.keyloop.scheduler.booking.AppointmentStatus.BOOKED)),
            Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.create(alice, rescheduleRequest()))
        .isInstanceOf(NotFoundException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.create(alice, rescheduleRequest()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void rescheduleHoldNeedsBookedAppointmentAtTheSameDealership() {
    when(customers.currentCustomer(alice)).thenReturn(Fixtures.ALICE);
    when(appointments.findState(APPOINTMENT))
        .thenReturn(
            Optional.of(
                appointment(
                    Fixtures.ALICE,
                    Fixtures.RIVERSIDE,
                    com.keyloop.scheduler.booking.AppointmentStatus.CANCELLED)),
            Optional.of(
                appointment(
                    Fixtures.ALICE,
                    Fixtures.HUDSON,
                    com.keyloop.scheduler.booking.AppointmentStatus.BOOKED)));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.create(alice, rescheduleRequest()))
        .isInstanceOf(com.keyloop.scheduler.common.api.InvalidTransitionException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.create(alice, rescheduleRequest()))
        .isInstanceOf(com.keyloop.scheduler.common.api.BusinessRuleException.class);
  }
}
