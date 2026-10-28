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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.SlotHeldException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionOperations;

class BlockServiceTest {

  /** Riverside, London: 09:00-10:00 local on Wednesday 4 Nov 2026 (GMT). */
  private static final Instant NINE = Instant.parse("2026-11-04T09:00:00Z");

  private static final Instant TEN = Instant.parse("2026-11-04T10:00:00Z");
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);

  private final CatalogService catalogs = mock(CatalogService.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final ResourceLocks locks = mock(ResourceLocks.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final SlotLedger ledger = mock(SlotLedger.class);
  private final CallerContext dispatcher =
      new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of(Fixtures.RIVERSIDE));

  private BlockService service() {
    return new BlockService(
        catalogs, reservations, locks, outbox, ledger, TransactionOperations.withoutTransaction());
  }

  @BeforeEach
  void stub() {
    when(catalogs.get(Fixtures.RIVERSIDE))
        .thenReturn(
            new ResourceCatalog(
                CatalogFixtures.riverside(),
                List.of(CatalogFixtures.OIL_CHANGE),
                List.of(new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
                List.of(new TechnicianView(Fixtures.SAM, "Sam", Set.of(Fixtures.OIL)))));
  }

  private static BlockRequest request(
      String type, UUID resource, Instant start, Instant end, String reason) {
    return new BlockRequest(
        type,
        resource,
        start == null ? null : OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
        end == null ? null : OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
        reason,
        false);
  }

  private BlockView create(BlockRequest req) {
    return service().create(dispatcher, Fixtures.RIVERSIDE, req);
  }

  @Test
  void reasonIsRequiredAndAtMost200Characters() {
    assertThatThrownBy(() -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "  ")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "x".repeat(201))))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e ->
                assertThat(((ValidationException) e).extras().toString()).contains("field=reason"));
    verify(reservations, never()).insertBlock(any(), any(), any(), any());
  }

  @Test
  void endMustBeAfterStart() {
    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, TEN, NINE, "Lift service")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, NINE, "Lift service")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void missingFieldsAndUnknownTypesAre400() {
    assertThatThrownBy(() -> create(request("CAR", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "x")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> create(request(null, Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "x")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> create(request("BAY", null, NINE, TEN, "x")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, null, TEN, "x")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, null, "x")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void unknownResourceIs404() {
    assertThatThrownBy(() -> create(request("BAY", Fixtures.SAM, NINE, TEN, "x")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> create(request("TECHNICIAN", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "x")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createLocksInsertsWritesTheEventAndMirrorsEveryCoveredDay() {
    Instant tomorrowTen = TEN.plusSeconds(24 * 3600);

    BlockView block = create(request("technician", Fixtures.SAM, NINE, tomorrowTen, " Training "));

    assertThat(block.resourceType()).isEqualTo("TECHNICIAN");
    assertThat(block.reason()).isEqualTo("Training");
    TimeRange period = new TimeRange(NINE, tomorrowTen);
    ReservationRow row = new ReservationRow(Fixtures.SAM, ReservationRow.TECHNICIAN, period);
    verify(locks).lockAll(List.of(Fixtures.SAM));
    verify(reservations).insertBlock(block.id(), Fixtures.RIVERSIDE, row, "Training");
    ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            eq("ResourceBlock"),
            eq(block.id()),
            eq(Fixtures.RIVERSIDE),
            eq("ResourceBlockCreated"),
            eq(0L),
            data.capture());
    assertThat(((ResourceBlockEvent) data.getValue()).reason()).isEqualTo("Training");
    verify(ledger)
        .applyCommitted(Fixtures.RIVERSIDE, WEDNESDAY, List.of(), Map.of(block.id(), row));
    verify(ledger)
        .applyCommitted(
            Fixtures.RIVERSIDE, WEDNESDAY.plusDays(1), List.of(), Map.of(block.id(), row));
  }

  @Test
  void overAppointmentsIs409ListingTheConflicts() {
    UUID conflicting = UUID.randomUUID();
    doThrow(new ReservationConflictException(Fixtures.RIVERSIDE_BAY_1))
        .when(reservations)
        .insertBlock(any(), any(), any(), any());
    when(reservations.conflictingAppointments(
            eq(Fixtures.RIVERSIDE),
            eq(List.of()),
            any(),
            eq(List.of(Fixtures.RIVERSIDE_BAY_1)),
            any()))
        .thenReturn(List.of(conflicting));

    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "Lift service")))
        .isInstanceOf(SlotUnavailableException.class)
        .satisfies(
            e -> {
              assertThat(((SlotUnavailableException) e).extras())
                  .containsEntry("conflicts", List.of(conflicting));
              assertThat(e.getMessage())
                  .isEqualTo(
                      "Bay 1 is not free between 09:00 on Wed 4 Nov and 10:00 on Wed 4 Nov"
                          + " (Europe/London); move the conflicting appointments first.");
            });
    verify(ledger).markDirty(Fixtures.RIVERSIDE, WEDNESDAY);
    verify(ledger, never()).applyCommitted(any(), any(), any(), any());
  }

  @Test
  void overLiveHoldIsSlotHeldUnlessOverridden() {
    UUID holdId = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    when(ledger.heldBy(eq(Fixtures.RIVERSIDE), eq(WEDNESDAY), anyMap()))
        .thenReturn(Map.of(Fixtures.RIVERSIDE_BAY_1, List.of(holdId)));
    when(ledger.getHold(Fixtures.RIVERSIDE, WEDNESDAY, holdId))
        .thenReturn(
            Optional.of(
                new StoredHold(
                    holdId,
                    holder,
                    new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM),
                    new TimeRange(NINE, TEN),
                    new TimeRange(NINE, TEN),
                    "{}",
                    Instant.parse("2026-11-04T08:41:00Z"))));

    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "Lift service")))
        .isInstanceOf(SlotHeldException.class)
        .hasMessage(
            "A customer is holding this time until 08:41 (Europe/London). Resend with"
                + " overrideHold=true to take it.");
    verify(locks, never()).lockAll(any());

    service()
        .create(
            dispatcher,
            Fixtures.RIVERSIDE,
            new BlockRequest(
                "BAY",
                Fixtures.RIVERSIDE_BAY_1,
                OffsetDateTime.ofInstant(NINE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(TEN, ZoneOffset.UTC),
                "Lift service",
                true));
    verify(ledger).release(Fixtures.RIVERSIDE, WEDNESDAY, holdId, holder);
  }

  @Test
  void createRetriesDeadlockOnce() {
    doThrow(deadlock()).doNothing().when(reservations).insertBlock(any(), any(), any(), any());

    create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "Lift service"));

    doThrow(deadlock()).when(reservations).insertBlock(any(), any(), any(), any());
    assertThatThrownBy(
            () -> create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "Lift service")))
        .isInstanceOf(CannotAcquireLockException.class);
  }

  @Test
  void removeDeactivatesWritesTheEventAndLeavesTheLedger() {
    UUID blockId = UUID.randomUUID();
    TimeRange period = new TimeRange(NINE, TEN);
    when(reservations.findActiveBlock(Fixtures.RIVERSIDE, blockId))
        .thenReturn(
            Optional.of(
                new BlockRecord(
                    blockId,
                    new ReservationRow(Fixtures.RIVERSIDE_BAY_1, ReservationRow.BAY, period),
                    "Lift service")));
    when(reservations.deactivateBlock(blockId)).thenReturn(true);

    service().remove(dispatcher, Fixtures.RIVERSIDE, blockId);

    verify(locks).lockAll(List.of(Fixtures.RIVERSIDE_BAY_1));
    verify(outbox)
        .append(
            eq("ResourceBlock"),
            eq(blockId),
            eq(Fixtures.RIVERSIDE),
            eq("ResourceBlockRemoved"),
            eq(1L),
            any());
    verify(ledger).applyCommitted(Fixtures.RIVERSIDE, WEDNESDAY, List.of(blockId), Map.of());
  }

  @Test
  void removeUnknownOrAlreadyRemovedIs404() {
    UUID blockId = UUID.randomUUID();
    when(reservations.findActiveBlock(Fixtures.RIVERSIDE, blockId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().remove(dispatcher, Fixtures.RIVERSIDE, blockId))
        .isInstanceOf(NotFoundException.class);

    when(reservations.findActiveBlock(Fixtures.RIVERSIDE, blockId))
        .thenReturn(
            Optional.of(
                new BlockRecord(
                    blockId,
                    new ReservationRow(
                        Fixtures.SAM, ReservationRow.TECHNICIAN, new TimeRange(NINE, TEN)),
                    "x")));
    when(reservations.deactivateBlock(blockId)).thenReturn(false);
    assertThatThrownBy(() -> service().remove(dispatcher, Fixtures.RIVERSIDE, blockId))
        .isInstanceOf(NotFoundException.class);
    verify(outbox, never()).append(any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void ledgerFailuresNeverFailTheRequest() {
    doThrow(new IllegalStateException("redis down"))
        .when(ledger)
        .applyCommitted(any(), any(), any(), any());
    doThrow(new IllegalStateException("redis down")).when(ledger).markDirty(any(), any());
    when(ledger.heldBy(any(), any(), anyMap()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

    BlockView block = create(request("BAY", Fixtures.RIVERSIDE_BAY_1, NINE, TEN, "Lift service"));

    assertThat(block.start()).isEqualTo(NINE);
    verify(ledger).markDirty(Fixtures.RIVERSIDE, WEDNESDAY);
  }

  private static CannotAcquireLockException deadlock() {
    return new CannotAcquireLockException(
        "deadlock", new java.sql.SQLException("deadlock detected", "40P01"));
  }
}
