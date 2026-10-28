package com.keyloop.scheduler.support;

import static org.assertj.core.api.Assertions.fail;

import com.keyloop.scheduler.common.time.TimeRange;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The zero-double-booking invariants, checked straight against the database. */
public final class NoDoubleBookingAssert {

  private NoDoubleBookingAssert() {}

  /**
   * Fails, listing the offending rows, if (a) two active reservations on one resource overlap, or
   * (b) a BOOKED / CHECKED_IN / IN_PROGRESS appointment does not have exactly one active bay and
   * one active technician reservation on its own bay and technician, or its technician
   * reservation's {@code [start_at, end_at)} differs from the appointment's. A resumed job's
   * technician reservation covers only the current work segment, so it may instead start at the
   * appointment's {@code started_at} (spec 6.2, RESUME).
   */
  public static void verify(JdbcClient jdbc) {
    List<Map<String, Object>> overlaps =
        jdbc.sql(
                """
                SELECT a.resource_id, a.id AS first_id, a.start_at AS first_start,
                       a.end_at AS first_end, b.id AS second_id, b.start_at AS second_start,
                       b.end_at AS second_end
                FROM resource_reservation a
                JOIN resource_reservation b
                  ON a.resource_id = b.resource_id AND a.id < b.id AND a.period && b.period
                WHERE a.active AND b.active
                """)
            .query()
            .listOfRows();
    if (!overlaps.isEmpty()) {
      fail("Overlapping active reservations on one resource: " + overlaps);
    }
    List<Map<String, Object>> broken =
        jdbc.sql(
                """
                SELECT ap.id, ap.status, ap.start_at, ap.end_at,
                       count(r.id) FILTER (WHERE r.service_bay_id IS NOT NULL) AS bays,
                       count(r.id) FILTER (WHERE r.technician_id IS NOT NULL) AS technicians,
                       count(r.id) FILTER (WHERE r.service_bay_id IS NOT NULL
                                             AND r.service_bay_id <> ap.service_bay_id)
                         AS foreign_bays,
                       count(r.id) FILTER (WHERE r.technician_id IS NOT NULL
                                             AND (r.technician_id <> ap.technician_id
                                                  OR (r.start_at <> ap.start_at
                                                      AND r.start_at IS DISTINCT FROM ap.started_at)
                                                  OR r.end_at <> ap.end_at))
                         AS mismatched_technicians
                FROM appointment ap
                LEFT JOIN resource_reservation r ON r.appointment_id = ap.id AND r.active
                WHERE ap.status IN ('BOOKED', 'CHECKED_IN', 'IN_PROGRESS')
                GROUP BY ap.id, ap.status, ap.start_at, ap.end_at, ap.started_at
                HAVING count(r.id) FILTER (WHERE r.service_bay_id IS NOT NULL) <> 1
                    OR count(r.id) FILTER (WHERE r.technician_id IS NOT NULL) <> 1
                    OR count(r.id) FILTER (WHERE r.service_bay_id IS NOT NULL
                                             AND r.service_bay_id <> ap.service_bay_id) > 0
                    OR count(r.id) FILTER (WHERE r.technician_id IS NOT NULL
                                             AND (r.technician_id <> ap.technician_id
                                                  OR (r.start_at <> ap.start_at
                                                      AND r.start_at IS DISTINCT FROM ap.started_at)
                                                  OR r.end_at <> ap.end_at)) > 0
                """)
            .query()
            .listOfRows();
    if (!broken.isEmpty()) {
      fail("Active appointments whose reservations are wrong: " + broken);
    }
  }

  /** Number of active reservations on the resource overlapping the range. */
  public static int countActive(JdbcClient jdbc, UUID resourceId, TimeRange r) {
    return jdbc.sql(
            """
            SELECT count(*) FROM resource_reservation
            WHERE resource_id = ? AND active
              AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')
            """)
        .params(resourceId, Timestamp.from(r.start()), Timestamp.from(r.end()))
        .query(Integer.class)
        .single();
  }
}
