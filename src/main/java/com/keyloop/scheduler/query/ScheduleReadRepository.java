package com.keyloop.scheduler.query;

import com.keyloop.scheduler.common.time.TimeRange;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the Gantt board's rows from whichever database the caller's transaction is on. */
@Repository
public class ScheduleReadRepository {

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public ScheduleReadRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Live appointments (not cancelled, not no-show) that may be drawn on the day: those whose period
   * overlaps it (the lower bound on {@code start_at} keeps {@code appointment_dealership_day}
   * usable) and those with an active reservation on it (a job resumed days after it started), with
   * the periods of their active technician and bay reservations.
   */
  public List<ScheduleAppointmentRow> appointments(UUID dealershipId, TimeRange day) {
    Timestamp from = Timestamp.from(day.start());
    Timestamp to = Timestamp.from(day.end());
    return jdbc.query(
        """
        SELECT a.id, a.version, a.status, a.hold_reason, a.service_bay_id, a.technician_id,
               a.start_at, a.end_at, a.worked_minutes, v.registration, v.make, v.model,
               (SELECT coalesce(sum(s.duration_minutes), 0) FROM appointment_service s
                WHERE s.appointment_id = a.id) AS planned_minutes,
               (SELECT array_agg(t.code ORDER BY s.position)
                FROM appointment_service s JOIN service_type t ON t.id = s.service_type_id
                WHERE s.appointment_id = a.id) AS codes,
               seg.technician_from, seg.technician_to, seg.bay_from, seg.bay_to
        FROM appointment a
        JOIN vehicle v ON v.id = a.vehicle_id
        LEFT JOIN LATERAL (
          SELECT max(r.start_at) FILTER (WHERE r.technician_id IS NOT NULL) AS technician_from,
                 max(r.end_at) FILTER (WHERE r.technician_id IS NOT NULL) AS technician_to,
                 max(r.start_at) FILTER (WHERE r.service_bay_id IS NOT NULL) AS bay_from,
                 max(r.end_at) FILTER (WHERE r.service_bay_id IS NOT NULL) AS bay_to
          FROM resource_reservation r WHERE r.appointment_id = a.id AND r.active) seg ON true
        WHERE a.dealership_id = ? AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
          AND ((a.start_at >= ? AND a.start_at < ? AND a.end_at > ?)
            OR a.id IN (SELECT r.appointment_id FROM resource_reservation r
                        WHERE r.dealership_id = ? AND r.active AND r.appointment_id IS NOT NULL
                          AND r.period && tstzrange(?::timestamptz, ?::timestamptz, '[)')))
        ORDER BY a.start_at, a.id
        """,
        (rs, n) ->
            new ScheduleAppointmentRow(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getString("status"),
                rs.getString("hold_reason"),
                rs.getObject("service_bay_id", UUID.class),
                rs.getObject("technician_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getInt("planned_minutes"),
                rs.getInt("worked_minutes"),
                rs.getString("registration"),
                rs.getString("make"),
                rs.getString("model"),
                codes(rs.getArray("codes")),
                instant(rs.getTimestamp("technician_from")),
                instant(rs.getTimestamp("technician_to")),
                instant(rs.getTimestamp("bay_from")),
                instant(rs.getTimestamp("bay_to"))),
        dealershipId,
        Timestamp.from(day.start().minusSeconds(24 * 3600)),
        to,
        from,
        dealershipId,
        from,
        to);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  /** Active blocks overlapping the day, by start. */
  public List<ScheduleBlockRow> blocks(UUID dealershipId, TimeRange day) {
    return jdbc.query(
        """
        SELECT id, service_bay_id, technician_id, start_at, end_at, reason
        FROM resource_reservation
        WHERE dealership_id = ? AND active AND kind = 'BLOCK'
          AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        ORDER BY start_at, id
        """,
        (rs, n) ->
            new ScheduleBlockRow(
                rs.getObject("id", UUID.class),
                rs.getObject("service_bay_id", UUID.class),
                rs.getObject("technician_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getString("reason")),
        dealershipId,
        Timestamp.from(day.start()),
        Timestamp.from(day.end()));
  }

  /** The database's clock, from the same database as the rows. */
  public Instant now() {
    return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
  }

  private static List<String> codes(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
