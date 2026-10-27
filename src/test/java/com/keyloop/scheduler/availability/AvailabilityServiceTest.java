package com.keyloop.scheduler.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.booking.Occupancy;
import com.keyloop.scheduler.booking.OccupancySource;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

  private final OccupancySource occupancy = mock(OccupancySource.class);
  private final CatalogService catalogs = mock(CatalogService.class);
  private final DbClock clock = mock(DbClock.class);
  private final AvailabilityService service = new AvailabilityService(occupancy, catalogs, clock);

  private final CallerContext customer =
      new CallerContext(Fixtures.ALICE_SUB, Set.of(Role.CUSTOMER), Set.of());
  private final CallerContext dispatcher =
      new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of(Fixtures.RIVERSIDE));

  @BeforeEach
  void stub() {
    ResourceCatalog catalog =
        new ResourceCatalog(
            CatalogFixtures.riverside(),
            List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
            List.of(new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
            List.of(new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL", "BRAKES"))));
    when(catalogs.get(Fixtures.RIVERSIDE)).thenReturn(catalog);
    when(clock.now()).thenReturn(NOW);
    when(occupancy.forDay(eq(Fixtures.RIVERSIDE), any(), any())).thenReturn(Occupancy.empty());
  }

  @Test
  void customerEarliestIsNowPlusTwoHours() {
    AvailabilityView view =
        service.find(customer, Fixtures.RIVERSIDE, List.of(Fixtures.OIL_CHANGE), MONDAY, MONDAY);

    // NOW is 08:00 GMT, so the first customer start is 10:00
    assertThat(view.days().get(0).starts().get(0)).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    assertThat(view.durationMinutes()).isEqualTo(45);
    assertThat(view.timeZone()).isEqualTo("Europe/London");
    assertThat(view.dealershipId()).isEqualTo(Fixtures.RIVERSIDE);
  }

  @Test
  void dispatcherEarliestIsNowMinusFifteenMinutes() {
    AvailabilityView view =
        service.find(dispatcher, Fixtures.RIVERSIDE, List.of(Fixtures.OIL_CHANGE), MONDAY, MONDAY);

    assertThat(view.days().get(0).starts().get(0)).isEqualTo(Instant.parse("2026-11-02T08:00:00Z"));
  }

  @Test
  void oneOccupancyLookupPerDateAndEveryDateListed() {
    AvailabilityView view =
        service.find(
            customer,
            Fixtures.RIVERSIDE,
            List.of(Fixtures.OIL_CHANGE),
            MONDAY,
            MONDAY.plusDays(13));

    assertThat(view.days()).hasSize(14);
    assertThat(view.days().get(6).date()).isEqualTo(LocalDate.of(2026, 11, 8));
    assertThat(view.days().get(6).starts()).isEmpty(); // Sunday is closed
    verify(occupancy, times(14)).forDay(eq(Fixtures.RIVERSIDE), any(), any());
  }

  @Test
  void invertedRangeIsRejected() {
    assertThatThrownBy(
            () ->
                service.find(
                    customer,
                    Fixtures.RIVERSIDE,
                    List.of(Fixtures.OIL_CHANGE),
                    MONDAY,
                    MONDAY.minusDays(1)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rangeOverFourteenDaysIsRejected() {
    assertThatThrownBy(
            () ->
                service.find(
                    customer,
                    Fixtures.RIVERSIDE,
                    List.of(Fixtures.OIL_CHANGE),
                    MONDAY,
                    MONDAY.plusDays(14)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void invalidServiceListsAreRejected() {
    assertThatThrownBy(() -> service.find(customer, Fixtures.RIVERSIDE, List.of(), MONDAY, MONDAY))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.find(
                    customer,
                    Fixtures.RIVERSIDE,
                    List.of(Fixtures.OIL_CHANGE, Fixtures.OIL_CHANGE),
                    MONDAY,
                    MONDAY))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void serviceNotOfferedIsRejected() {
    assertThatThrownBy(
            () ->
                service.find(
                    customer, Fixtures.RIVERSIDE, List.of(UUID.randomUUID()), MONDAY, MONDAY))
        .isInstanceOf(BusinessRuleException.class);
  }
}
