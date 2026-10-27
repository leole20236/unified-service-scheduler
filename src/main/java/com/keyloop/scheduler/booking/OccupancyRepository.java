package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.db.PrimaryRead;
import com.keyloop.scheduler.common.time.TimeRange;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads active reservations from the primary. */
@Repository
public class OccupancyRepository {

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public OccupancyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Active reservations overlapping the window. The range predicate keeps the GiST index {@code
   * reservation_dealership_window} usable.
   */
  @PrimaryRead
  public Occupancy load(UUID dealershipId, TimeRange window) {
    Map<UUID, List<TimeRange>> byResource = new HashMap<>();
    jdbc.query(
        """
        SELECT resource_id, start_at, end_at FROM resource_reservation
        WHERE dealership_id = ? AND active
          AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """,
        rs -> {
          byResource
              .computeIfAbsent(rs.getObject("resource_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new TimeRange(
                      rs.getTimestamp("start_at").toInstant(),
                      rs.getTimestamp("end_at").toInstant()));
        },
        dealershipId,
        Timestamp.from(window.start()),
        Timestamp.from(window.end()));
    return new Occupancy(byResource);
  }

  /**
   * Active reservations overlapping the window with their ids, for rebuilding the Redis slot
   * ledger. Always from the primary: a lagging replica would bake stale data into the ledger.
   */
  @PrimaryRead
  public List<ReservedInterval> intervals(UUID dealershipId, TimeRange window) {
    return jdbc.query(
        """
        SELECT id, resource_id, start_at, end_at FROM resource_reservation
        WHERE dealership_id = ? AND active
          AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """,
        (rs, n) ->
            new ReservedInterval(
                rs.getObject("id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                new TimeRange(
                    rs.getTimestamp("start_at").toInstant(),
                    rs.getTimestamp("end_at").toInstant())),
        dealershipId,
        Timestamp.from(window.start()),
        Timestamp.from(window.end()));
  }
}
