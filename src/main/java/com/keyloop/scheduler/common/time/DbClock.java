package com.keyloop.scheduler.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Business "now": the PostgreSQL primary's clock, never the JVM's. */
@Component
public class DbClock {

  private final JdbcTemplate jdbc;

  /**
   * Creates the clock directly over the primary pool, so it never follows the caller's transaction
   * (a read-only transaction would otherwise route to the replica's clock).
   */
  public DbClock(@Qualifier("primaryDataSource") DataSource primary) {
    this.jdbc = new JdbcTemplate(primary);
  }

  /** Current instant according to the primary database. */
  public Instant now() {
    return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
  }

  /** Diagnostic: the application_name of the connection this clock uses (identifies its pool). */
  public String servedBy() {
    return jdbc.queryForObject("SHOW application_name", String.class);
  }
}
