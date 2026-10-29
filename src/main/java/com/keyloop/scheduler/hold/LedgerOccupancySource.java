package com.keyloop.scheduler.hold;

import com.keyloop.scheduler.booking.core.DbOccupancySource;
import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.booking.domain.OccupancySource;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.time.OpeningHoursCalendar;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Availability from the slot ledger (spec 9.3): confirmed reservations plus other customers' live
 * holds, so a held slot looks taken. Redis failing ⇒ the database source (slower, still correct).
 */
@Component
@Primary
public class LedgerOccupancySource implements OccupancySource {

  private static final Logger LOG = LoggerFactory.getLogger(LedgerOccupancySource.class);

  private final SlotLedger ledger;
  private final DbOccupancySource database;

  /** Creates the source. */
  public LedgerOccupancySource(SlotLedger ledger, DbOccupancySource database) {
    this.ledger = ledger;
    this.database = database;
  }

  @Override
  public Occupancy forDay(UUID dealershipId, LocalDate date, DealershipView dealership) {
    return forDay(dealershipId, date, dealership, null);
  }

  @Override
  public Occupancy forDay(
      UUID dealershipId, LocalDate date, DealershipView dealership, UUID viewerId) {
    if (OpeningHoursCalendar.window(dealership, date).isEmpty()) {
      return Occupancy.empty();
    }
    try {
      ledger.ensureLoaded(dealershipId, date, SlotLedger.dayWindow(dealership, date));
      return ledger.occupancy(dealershipId, date, true, viewerId);
    } catch (RuntimeException e) {
      if (!LedgerFailures.isRedisFailure(e)) {
        throw e;
      }
      LOG.warn("Slot ledger unavailable for {} on {}: {}", dealershipId, date, e.toString());
      return database.forDay(dealershipId, date, dealership);
    }
  }
}
