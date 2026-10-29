package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-through of committed reservation changes to the Redis ledger (spec 8.6), grouped by
 * dealership-local day. Each reservation is written to every day its own period touches — not the
 * day the appointment started, which differs for a job resumed on a later day. A day whose write
 * fails is marked dirty (rebuilt from the primary before its next use); Redis failures never fail
 * the request.
 */
final class LedgerWrites {

  private static final Logger LOG = LoggerFactory.getLogger(LedgerWrites.class);

  private final SlotLedger ledger;

  LedgerWrites(SlotLedger ledger) {
    this.ledger = ledger;
  }

  /**
   * Applies the change: {@code removed} maps each removed (or replaced) reservation id to the
   * period it had before the change; {@code added} holds the reservations as they are now.
   */
  void apply(DealershipView d, Map<UUID, TimeRange> removed, Map<UUID, ReservationRow> added) {
    Map<LocalDate, List<UUID>> removals = new TreeMap<>();
    removed.forEach(
        (id, period) ->
            HoldGuard.dates(d, period)
                .forEach(date -> removals.computeIfAbsent(date, k -> new ArrayList<>()).add(id)));
    Map<LocalDate, Map<UUID, ReservationRow>> additions = new TreeMap<>();
    added.forEach(
        (id, row) ->
            HoldGuard.dates(d, row.period())
                .forEach(
                    date ->
                        additions.computeIfAbsent(date, k -> new LinkedHashMap<>()).put(id, row)));
    TreeSet<LocalDate> dates = new TreeSet<>(removals.keySet());
    dates.addAll(additions.keySet());
    for (LocalDate date : dates) {
      try {
        ledger.applyCommitted(
            d.id(),
            date,
            removals.getOrDefault(date, List.of()),
            additions.getOrDefault(date, Map.of()));
      } catch (RuntimeException e) {
        LOG.warn("Ledger write-through of {} on {} failed: {}", d.id(), date, e.toString());
        markDirty(d.id(), date);
      }
    }
  }

  /** Forces a rebuild of the day before its next use; a Redis failure is only logged. */
  void markDirty(UUID dealershipId, LocalDate date) {
    try {
      ledger.markDirty(dealershipId, date);
    } catch (RuntimeException e) {
      LOG.warn("Could not mark ledger of {} dirty: {}", dealershipId, e.toString());
    }
  }
}
