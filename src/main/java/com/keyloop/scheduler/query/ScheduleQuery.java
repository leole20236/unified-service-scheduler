package com.keyloop.scheduler.query;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispatcher's Gantt board (spec 9.1, 14.5). A read-only transaction, so it is served by the
 * replica: the board polls, and a few hundred milliseconds of lag are harmless because every write
 * from it carries {@code If-Match} and the database decides.
 */
@Service
@Transactional(readOnly = true)
public class ScheduleQuery {

  private final CatalogService catalogs;
  private final ScheduleReadRepository rows;

  /** Creates the query. */
  public ScheduleQuery(CatalogService catalogs, ScheduleReadRepository rows) {
    this.catalogs = catalogs;
    this.rows = rows;
  }

  /** The board of one dealership-local date; unknown dealership ⇒ 404. */
  public ScheduleView forDay(UUID dealershipId, LocalDate date, GroupBy groupBy) {
    ResourceCatalog catalog = catalogs.get(dealershipId);
    ZoneId zone = catalog.dealership().zoneId();
    TimeRange day =
        new TimeRange(
            date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    return ScheduleViewMapper.toView(
        catalog,
        date,
        groupBy,
        rows.appointments(dealershipId, day),
        rows.blocks(dealershipId, day),
        rows.now());
  }
}
