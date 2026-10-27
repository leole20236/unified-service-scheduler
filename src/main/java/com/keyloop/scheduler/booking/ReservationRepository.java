package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.time.TimeRange;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes and inspects resource reservations; the EXCLUDE constraint is the final arbiter. */
@Repository
public class ReservationRepository {

  /**
   * Insert order: by resource id as PostgreSQL orders uuids (unsigned bytes = lowercase hex text),
   * the same order {@link ResourceLocks} locks in. The order alone does not prevent waits: it is
   * the advisory locks taken first that queue concurrent writers of one resource (spec 8.1); the
   * fixed order just keeps rows and log output deterministic.
   */
  static final Comparator<ReservationRow> INSERT_ORDER =
      Comparator.comparing(ReservationRow::resourceId, ResourceLocks.UUID_ORDER);

  private final JdbcTemplate jdbc;
  private final ResourceLocks locks;

  /** Creates the repository over the routing data source. */
  public ReservationRepository(JdbcTemplate jdbc, ResourceLocks locks) {
    this.jdbc = jdbc;
    this.locks = locks;
  }

  /**
   * Inserts active APPOINTMENT reservations sorted by resource id. A refused row (SQLSTATE 23P01)
   * raises {@link ReservationConflictException} naming that resource; the caller's transaction is
   * then unusable and must roll back. Returns the rows by their new reservation id, in insert
   * order.
   */
  public Map<UUID, ReservationRow> insertAll(
      UUID appointmentId, UUID dealershipId, List<ReservationRow> rows) {
    locks.lockAll(rows.stream().map(ReservationRow::resourceId).toList());
    Map<UUID, ReservationRow> inserted = new LinkedHashMap<>();
    for (ReservationRow row : rows.stream().sorted(INSERT_ORDER).toList()) {
      UUID id = Ids.newId();
      String column =
          ReservationRow.BAY.equals(row.resourceType()) ? "service_bay_id" : "technician_id";
      try {
        jdbc.update(
            "INSERT INTO resource_reservation (id, dealership_id, "
                + column
                + ", appointment_id, kind, start_at, end_at) VALUES (?, ?, ?, ?, 'APPOINTMENT', ?,"
                + " ?)",
            id,
            dealershipId,
            row.resourceId(),
            appointmentId,
            Timestamp.from(row.period().start()),
            Timestamp.from(row.period().end()));
      } catch (DataAccessException e) {
        if (SqlStates.has(e, SqlStates.EXCLUSION_VIOLATION)) {
          throw new ReservationConflictException(row.resourceId());
        }
        throw e;
      }
      inserted.put(id, row);
    }
    return inserted;
  }

  /**
   * Ids of active appointments holding one of the technicians during {@code period} or one of the
   * bays during {@code bayPeriod} (for the dispatcher's {@code conflicts[]}).
   */
  public List<UUID> conflictingAppointments(
      UUID dealershipId,
      Collection<UUID> technicianIds,
      TimeRange period,
      Collection<UUID> bayIds,
      TimeRange bayPeriod) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT appointment_id FROM resource_reservation
        WHERE dealership_id = ? AND active AND appointment_id IS NOT NULL
          AND ((technician_id = ANY (?::uuid[])
                AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)'))
            OR (service_bay_id = ANY (?::uuid[])
                AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')))
        ORDER BY appointment_id
        """,
        UUID.class,
        dealershipId,
        technicianIds.stream().map(UUID::toString).toArray(String[]::new),
        Timestamp.from(period.start()),
        Timestamp.from(period.end()),
        bayIds.stream().map(UUID::toString).toArray(String[]::new),
        Timestamp.from(bayPeriod.start()),
        Timestamp.from(bayPeriod.end()));
  }
}
