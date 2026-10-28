package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.api.SlotHeldException;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.LedgerFailures;
import com.keyloop.scheduler.hold.SlotLedger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatcher writes against live customer holds (spec 8.4, 8.6): before the database is touched,
 * the target interval of every resource is checked in the Redis ledger. A live hold of a customer
 * refuses the write with {@code 409 SLOT_HELD}, unless the dispatcher overrides it, which releases
 * the hold (that customer's confirm then meets the database). Holds only shape the user experience:
 * when Redis cannot answer, the write goes ahead and the database decides as always.
 */
final class HoldGuard {

  /** Holds exist at most 60 days ahead; a long block is checked for this many days at most. */
  static final int MAX_DAYS = 62;

  private static final Logger LOG = LoggerFactory.getLogger(HoldGuard.class);
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

  /** A live hold over some of the targets. */
  record Held(LocalDate date, UUID holdId, UUID userId, Instant expiresAt, Set<UUID> resources) {}

  private final SlotLedger ledger;

  HoldGuard(SlotLedger ledger) {
    this.ledger = ledger;
  }

  /**
   * Refuses with SLOT_HELD when a live hold overlaps a target (resource ⇒ period); with {@code
   * override} those holds are released instead.
   */
  void clear(DealershipView d, Map<UUID, TimeRange> targets, boolean override) {
    List<Held> held = find(d, targets);
    if (held.isEmpty()) {
      return;
    }
    if (!override) {
      throw refusal(d, held);
    }
    release(d, held);
  }

  /** Live holds overlapping the targets; empty when Redis cannot answer. */
  List<Held> find(DealershipView d, Map<UUID, TimeRange> targets) {
    Map<LocalDate, Map<UUID, TimeRange>> byDate = new TreeMap<>();
    targets.forEach(
        (resource, period) -> {
          for (LocalDate date : dates(d, period)) {
            byDate.computeIfAbsent(date, k -> new LinkedHashMap<>()).put(resource, period);
          }
        });
    List<Held> held = new ArrayList<>();
    try {
      for (Map.Entry<LocalDate, Map<UUID, TimeRange>> day : byDate.entrySet()) {
        LocalDate date = day.getKey();
        Map<UUID, Set<UUID>> resourcesByHold = new LinkedHashMap<>();
        ledger
            .heldBy(d.id(), date, day.getValue())
            .forEach(
                (resource, holdIds) ->
                    holdIds.forEach(
                        id ->
                            resourcesByHold
                                .computeIfAbsent(id, k -> new LinkedHashSet<>())
                                .add(resource)));
        resourcesByHold.forEach(
            (holdId, resources) ->
                ledger
                    .getHold(d.id(), date, holdId)
                    .ifPresent(
                        h ->
                            held.add(
                                new Held(date, holdId, h.userId(), h.expiresAt(), resources))));
      }
    } catch (RuntimeException e) {
      if (!LedgerFailures.isRedisFailure(e)) {
        throw e;
      }
      LOG.warn("Live holds of {} unreadable, writing without the check: {}", d.id(), e.toString());
      return List.of();
    }
    return held;
  }

  /** The SLOT_HELD problem naming when the first of the holds expires, in local time. */
  SlotHeldException refusal(DealershipView d, List<Held> held) {
    Instant until = held.stream().map(Held::expiresAt).min(Comparator.naturalOrder()).orElseThrow();
    return new SlotHeldException(
        "A customer is holding this time until "
            + CLOCK.format(until.atZone(d.zoneId()))
            + " ("
            + d.zoneId().getId()
            + "). Resend with overrideHold=true to take it.");
  }

  /** Releases the holds on behalf of their owners; a Redis failure is only logged. */
  void release(DealershipView d, List<Held> held) {
    for (Held h : held) {
      try {
        ledger.release(d.id(), h.date(), h.holdId(), h.userId());
        LOG.info("Hold {} of dealership {} overridden by a dispatcher", h.holdId(), d.id());
      } catch (RuntimeException e) {
        if (!LedgerFailures.isRedisFailure(e)) {
          throw e;
        }
        LOG.warn("Could not release overridden hold {}: {}", h.holdId(), e.toString());
      }
    }
  }

  /** The dealership-local dates the period touches (at most {@link #MAX_DAYS}). */
  static List<LocalDate> dates(DealershipView d, TimeRange period) {
    LocalDate first = period.start().atZone(d.zoneId()).toLocalDate();
    LocalDate last = period.end().minusNanos(1).atZone(d.zoneId()).toLocalDate();
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate date = first; !date.isAfter(last) && dates.size() < MAX_DAYS; ) {
      dates.add(date);
      date = date.plusDays(1);
    }
    return dates;
  }
}
