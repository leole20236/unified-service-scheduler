package com.keyloop.scheduler.schema;

import static com.keyloop.scheduler.support.Fixtures.ALICE;
import static com.keyloop.scheduler.support.Fixtures.ALICE_FOCUS;
import static com.keyloop.scheduler.support.Fixtures.BOB_GOLF;
import static com.keyloop.scheduler.support.Fixtures.HUDSON_BAY_1;
import static com.keyloop.scheduler.support.Fixtures.RIVERSIDE;
import static com.keyloop.scheduler.support.Fixtures.RIVERSIDE_BAY_1;
import static com.keyloop.scheduler.support.Fixtures.RIVERSIDE_BAY_2;
import static com.keyloop.scheduler.support.Fixtures.SAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ReservationConstraintIT extends IntegrationTest {

  @Autowired private DataSource dataSource;

  private static final Instant DAY = Instant.parse("2030-01-07T00:00:00Z");

  private static Instant at(int hour, int minute) {
    return DAY.plusSeconds(hour * 3600L + minute * 60L);
  }

  private UUID insertBayBlock(UUID bay, Instant start, Instant end, boolean active) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO resource_reservation
          (id, dealership_id, service_bay_id, kind, start_at, end_at, active)
        VALUES (?, ?, ?, 'BLOCK', ?, ?, ?)
        """,
        id,
        RIVERSIDE,
        bay,
        Timestamp.from(start),
        Timestamp.from(end),
        active);
    return id;
  }

  private static String sqlState(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof SQLException e) {
        return e.getSQLState();
      }
    }
    return null;
  }

  @Test
  void overlappingActiveReservationsAreRejected() {
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(9, 45), true);

    assertThatThrownBy(() -> insertBayBlock(RIVERSIDE_BAY_1, at(9, 30), at(10, 45), true))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
  }

  @Test
  void backToBackReservationsAreAllowed() {
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(9, 45), true);
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 45), at(11, 0), true);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_reservation", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void inactiveReservationDoesNotBlock() {
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(9, 45), false);
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 30), at(10, 45), true);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_reservation", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void differentResourcesMayOverlap() {
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(9, 45), true);
    insertBayBlock(RIVERSIDE_BAY_2, at(9, 0), at(9, 45), true);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_reservation", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void bayAndTechnicianBothSetRejected() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO resource_reservation
                      (id, dealership_id, service_bay_id, technician_id, kind, start_at, end_at)
                    VALUES (?, ?, ?, ?, 'BLOCK', ?, ?)
                    """,
                    UUID.randomUUID(),
                    RIVERSIDE,
                    RIVERSIDE_BAY_1,
                    SAM,
                    Timestamp.from(at(9, 0)),
                    Timestamp.from(at(10, 0))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  void neitherSetRejected() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO resource_reservation (id, dealership_id, kind, start_at, end_at)
                    VALUES (?, ?, 'BLOCK', ?, ?)
                    """,
                    UUID.randomUUID(),
                    RIVERSIDE,
                    Timestamp.from(at(9, 0)),
                    Timestamp.from(at(10, 0))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  void bayOfOtherDealershipRejected() {
    assertThatThrownBy(() -> insertAppointment(RIVERSIDE, ALICE, ALICE_FOCUS, HUDSON_BAY_1))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
  }

  @Test
  void vehicleOfOtherCustomerRejected() {
    assertThatThrownBy(() -> insertAppointment(RIVERSIDE, ALICE, BOB_GOLF, RIVERSIDE_BAY_1))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
  }

  @Test
  void validAppointmentIsAccepted() {
    insertAppointment(RIVERSIDE, ALICE, ALICE_FOCUS, RIVERSIDE_BAY_1);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class)).isEqualTo(1);
  }

  private void insertAppointment(UUID dealership, UUID customer, UUID vehicle, UUID bay) {
    jdbc.update(
        """
        INSERT INTO appointment
          (id, dealership_id, customer_id, vehicle_id, service_bay_id, technician_id,
           start_at, end_at, status, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BOOKED', ?)
        """,
        UUID.randomUUID(),
        dealership,
        customer,
        vehicle,
        bay,
        SAM,
        Timestamp.from(at(9, 0)),
        Timestamp.from(at(10, 0)),
        customer);
  }

  @Test
  void overlapQueryUsesGistIndex() {
    jdbc.execute("SET enable_seqscan = off");
    try {
      String plan =
          jdbc.queryForObject(
              """
              EXPLAIN (FORMAT JSON)
              SELECT id FROM resource_reservation
              WHERE dealership_id = ? AND active
                AND period && tstzrange(?::timestamptz, ?::timestamptz, '[)')
              """,
              String.class,
              RIVERSIDE,
              Timestamp.from(at(9, 0)),
              Timestamp.from(at(18, 0)));
      assertThat(plan).contains("reservation_dealership_window");
    } finally {
      jdbc.execute("RESET enable_seqscan");
    }
  }

  /**
   * pgjdbc announces the JVM zone at connect time, so a non-UTC JVM would give non-UTC sessions;
   * the pool's connection-init-sql must pin them to UTC.
   */
  @Test
  void databaseRunsInUtc() {
    HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    try {
      pool.softEvictConnections();

      assertThat(jdbc.queryForObject("SHOW timezone", String.class)).isEqualTo("UTC");
    } finally {
      TimeZone.setDefault(original);
      pool.softEvictConnections();
    }
  }

  @Test
  void overlappingTechnicianReservationsAreRejected() {
    insertTechnicianBlock(SAM, at(9, 0), at(9, 45));

    assertThatThrownBy(() -> insertTechnicianBlock(SAM, at(9, 30), at(10, 30)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
  }

  @Test
  void bayAndTechnicianWithSamePeriodAreBothAccepted() {
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(9, 45), true);
    insertTechnicianBlock(SAM, at(9, 0), at(9, 45));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_reservation", Integer.class))
        .isEqualTo(2);
  }

  private void insertTechnicianBlock(UUID technician, Instant start, Instant end) {
    jdbc.update(
        """
        INSERT INTO resource_reservation
          (id, dealership_id, technician_id, kind, start_at, end_at)
        VALUES (?, ?, ?, 'BLOCK', ?, ?)
        """,
        UUID.randomUUID(),
        RIVERSIDE,
        technician,
        Timestamp.from(start),
        Timestamp.from(end));
  }

  @Test
  void reactivatingIntoAnOverlapIsRejected() {
    UUID inactive = insertBayBlock(RIVERSIDE_BAY_1, at(9, 0), at(10, 0), false);
    insertBayBlock(RIVERSIDE_BAY_1, at(9, 30), at(10, 30), true);

    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE resource_reservation SET active = true WHERE id = ?", inactive))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
  }
}
