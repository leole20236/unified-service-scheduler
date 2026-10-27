package com.keyloop.scheduler.common.time;

import com.keyloop.scheduler.common.db.PrimaryRead;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Business "now": the PostgreSQL primary's clock, never the JVM's. */
@Component
public class DbClock {

  private final JdbcTemplate jdbc;

  /** Creates the clock over the given JDBC template. */
  public DbClock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Current instant according to the primary database. */
  @PrimaryRead
  public Instant now() {
    return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
  }
}
