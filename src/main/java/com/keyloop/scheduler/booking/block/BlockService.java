package com.keyloop.scheduler.booking.block;

import com.keyloop.scheduler.booking.core.BookingConfig;
import com.keyloop.scheduler.booking.core.DispatcherResources;
import com.keyloop.scheduler.booking.core.HoldGuard;
import com.keyloop.scheduler.booking.core.LedgerWrites;
import com.keyloop.scheduler.booking.core.ReservationRepository;
import com.keyloop.scheduler.booking.core.ResourceLocks;
import com.keyloop.scheduler.booking.domain.BlockRecord;
import com.keyloop.scheduler.booking.domain.ReservationConflictException;
import com.keyloop.scheduler.booking.domain.ReservationRow;
import com.keyloop.scheduler.booking.domain.SlotMessages;
import com.keyloop.scheduler.booking.event.ResourceBlockEvent;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Resource blocks (spec 6.5): a bay or technician taken out of service for a period. A block is a
 * reservation row of kind BLOCK, so the same EXCLUDE constraint keeps it and appointments apart; a
 * block over existing appointments is refused with 409 listing them. Live customer holds are
 * checked first (SLOT_HELD unless overridden); the Redis ledger follows after commit.
 */
@Service
public class BlockService {

  static final int MAX_REASON = 200;

  private static final String AGGREGATE = "ResourceBlock";
  private static final String CREATED = "ResourceBlockCreated";
  private static final String REMOVED = "ResourceBlockRemoved";

  private final CatalogService catalogs;
  private final ReservationRepository reservations;
  private final ResourceLocks locks;
  private final OutboxWriter outbox;
  private final TransactionOperations transactions;
  private final HoldGuard holds;
  private final LedgerWrites ledgerWrites;

  /** Creates the service; {@code transactions} runs each write in a new transaction. */
  public BlockService(
      CatalogService catalogs,
      ReservationRepository reservations,
      ResourceLocks locks,
      OutboxWriter outbox,
      SlotLedger ledger,
      @Qualifier(BookingConfig.BOOKING_TRANSACTIONS) TransactionOperations transactions) {
    this.catalogs = catalogs;
    this.reservations = reservations;
    this.locks = locks;
    this.outbox = outbox;
    this.transactions = transactions;
    this.holds = new HoldGuard(ledger);
    this.ledgerWrites = new LedgerWrites(ledger);
  }

  /**
   * Blocks the resource. Invalid input ⇒ 400; unknown resource ⇒ 404; a live hold ⇒ 409 SLOT_HELD
   * (unless overridden); overlapping appointments or blocks ⇒ 409 SLOT_UNAVAILABLE with {@code
   * conflicts}.
   */
  public BlockView create(CallerContext caller, UUID dealershipId, BlockRequest req) {
    final String type = resourceType(req.resourceType());
    final String reason = reason(req.reason());
    if (req.resourceId() == null) {
      throw DispatcherResources.invalid("resourceId", "must not be null");
    }
    if (req.start() == null || req.end() == null) {
      throw DispatcherResources.invalid(req.start() == null ? "start" : "end", "must not be null");
    }
    if (!req.end().toInstant().isAfter(req.start().toInstant())) {
      throw DispatcherResources.invalid("end", "must be after start");
    }
    ResourceCatalog catalog = catalogs.get(dealershipId);
    DealershipView d = catalog.dealership();
    String name =
        ReservationRow.BAY.equals(type)
            ? DispatcherResources.bay(catalog, req.resourceId()).name()
            : DispatcherResources.technician(catalog, req.resourceId()).displayName();
    TimeRange period = new TimeRange(req.start().toInstant(), req.end().toInstant());
    ReservationRow row = new ReservationRow(req.resourceId(), type, period);
    holds.clear(d, Map.of(row.resourceId(), period), req.overrideHold());
    UUID id = Ids.newId();
    boolean deadlockRetried = false;
    while (true) {
      try {
        transactions.executeWithoutResult(
            status -> {
              locks.lockAll(List.of(row.resourceId()));
              reservations.insertBlock(id, dealershipId, row, reason);
              outbox.append(AGGREGATE, id, dealershipId, CREATED, 0, event(id, row, reason, d));
            });
        break;
      } catch (ReservationConflictException e) {
        markDirty(d, period);
        throw unavailable(d, row, name);
      } catch (RuntimeException e) {
        if (!deadlockRetried && SqlStates.has(e, SqlStates.DEADLOCK_DETECTED)) {
          deadlockRetried = true;
          continue;
        }
        throw e;
      }
    }
    writeThrough(d, period, List.of(), Map.of(id, row));
    return new BlockView(id, type, row.resourceId(), period.start(), period.end(), reason);
  }

  /** Removes an active block of the dealership; unknown or already removed ⇒ 404. */
  public void remove(CallerContext caller, UUID dealershipId, UUID blockId) {
    BlockRecord block =
        reservations
            .findActiveBlock(dealershipId, blockId)
            .orElseThrow(BlockService::blockNotFound);
    DealershipView d = catalogs.get(dealershipId).dealership();
    transactions.executeWithoutResult(
        status -> {
          locks.lockAll(List.of(block.row().resourceId()));
          if (!reservations.deactivateBlock(blockId)) {
            throw blockNotFound();
          }
          outbox.append(
              AGGREGATE,
              blockId,
              dealershipId,
              REMOVED,
              1,
              event(blockId, block.row(), block.reason(), d));
        });
    writeThrough(d, block.row().period(), List.of(blockId), Map.of());
  }

  private static NotFoundException blockNotFound() {
    return new NotFoundException("Resource block not found.");
  }

  private static ResourceBlockEvent event(
      UUID id, ReservationRow row, String reason, DealershipView d) {
    return new ResourceBlockEvent(
        id,
        row.resourceType(),
        row.resourceId(),
        row.period().start(),
        row.period().end(),
        reason,
        d.zoneId().getId());
  }

  private static String resourceType(String value) {
    String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!ReservationRow.BAY.equals(type) && !ReservationRow.TECHNICIAN.equals(type)) {
      throw DispatcherResources.invalid("resourceType", "must be BAY or TECHNICIAN");
    }
    return type;
  }

  private static String reason(String value) {
    String reason = value == null ? "" : value.trim();
    if (reason.isEmpty() || reason.length() > MAX_REASON) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(
              Map.of(
                  "field", "reason", "message", "is required, at most " + MAX_REASON + " chars")));
    }
    return reason;
  }

  /** The refusal naming the appointments in the way (read after the rollback). */
  private SlotUnavailableException unavailable(
      DealershipView d, ReservationRow row, String resourceName) {
    boolean bay = ReservationRow.BAY.equals(row.resourceType());
    List<UUID> conflicts =
        reservations.conflictingAppointments(
            d.id(),
            bay ? List.of() : List.of(row.resourceId()),
            row.period(),
            bay ? List.of(row.resourceId()) : List.of(),
            row.period());
    return new SlotUnavailableException(
        resourceName
            + " is not free between "
            + SlotMessages.at(row.period().start().atZone(d.zoneId()).toLocalDateTime())
            + " and "
            + SlotMessages.at(row.period().end().atZone(d.zoneId()).toLocalDateTime())
            + " ("
            + d.zoneId().getId()
            + "); move the conflicting appointments first.",
        List.of(),
        conflicts);
  }

  /** After commit: the block enters (or leaves) the ledger of every day it covers. */
  private void writeThrough(
      DealershipView d, TimeRange period, List<UUID> removed, Map<UUID, ReservationRow> added) {
    Map<UUID, TimeRange> removedPeriods = new LinkedHashMap<>();
    removed.forEach(id -> removedPeriods.put(id, period));
    ledgerWrites.apply(d, removedPeriods, added);
  }

  private void markDirty(DealershipView d, TimeRange period) {
    HoldGuard.dates(d, period).forEach(date -> ledgerWrites.markDirty(d.id(), date));
  }
}
