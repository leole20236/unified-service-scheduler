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
   * Insert order: by resource id as PostgreSQL orders uuids (unsigned bytes = lowercase hex text).
   * Every transaction takes its index entries in the same order, so two bookings never wait on each
   * other in a cycle (spec 8.7 #4).
   */
  static final Comparator<ReservationRow> LOCK_ORDER =
      Comparator.comparing(r -> r.resourceId().toString());

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public ReservationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts active APPOINTMENT reservations sorted by resource id. A refused row (SQLSTATE 23P01)
   * raises {@link ReservationConflictException} naming that resource; the caller's transaction is
   * then unusable and must roll back. Returns the rows by their new reservation id, in insert
   * order.
   */
  public Map<UUID, ReservationRow> insertAll(
      UUID appointmentId, UUID dealershipId, List<ReservationRow> rows) {
    lockResources(rows);
    Map<UUID, ReservationRow> inserted = new LinkedHashMap<>();
    for (ReservationRow row : rows.stream().sorted(LOCK_ORDER).toList()) {
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
   * Takes a transaction-scoped advisory lock per resource, in ascending lock-key order.
   *
   * <p>Why: PostgreSQL adds a row's index entry <em>before</em> checking the EXCLUDE constraint, so
   * two transactions inserting overlapping reservations for one resource at the same moment each
   * find the other's uncommitted entry and wait for the other transaction to end: a deadlock
   * (40P01) that the detector breaks only after {@code deadlock_timeout}, whatever the insert
   * order. Queueing writers of the same resource on a lock first means the exclusion check only
   * ever sees committed rows, so a race ends in a clean 23P01 or success. The lock only orders
   * writers; EXCLUDE remains the guarantee. Locks are released at commit or rollback. A hash
   * collision between two resources merely serialises them; sorting by key keeps the order global.
   */
  private void lockResources(List<ReservationRow> rows) {
    long[] keys =
        rows.stream().mapToLong(r -> lockKey(r.resourceId())).sorted().distinct().toArray();
    for (long key : keys) {
      jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", key);
    }
  }

  /** 64-bit advisory lock key of a resource. */
  static long lockKey(UUID resourceId) {
    return resourceId.getMostSignificantBits() ^ resourceId.getLeastSignificantBits();
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
