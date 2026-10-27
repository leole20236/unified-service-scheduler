package com.keyloop.scheduler.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class PgRangesTest {

  private static final TimeRange RANGE =
      new TimeRange(Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T11:00:00Z"));

  @Test
  void literalIsHalfOpenUtc() {
    assertThat(PgRanges.toLiteral(RANGE)).isEqualTo("[2026-11-02T09:00:00Z,2026-11-02T11:00:00Z)");
  }

  @Test
  void literalRoundTrips() {
    assertThat(PgRanges.parse(PgRanges.toLiteral(RANGE))).isEqualTo(RANGE);
  }

  @Test
  void parsesDatabaseOutput() {
    assertThat(PgRanges.parse("[\"2026-11-02 09:00:00+00\",\"2026-11-02 11:00:00+00\")"))
        .isEqualTo(RANGE);
    assertThat(PgRanges.parse("[\"2026-11-02 10:00:00+01\",\"2026-11-02 12:00:00+01\")"))
        .isEqualTo(RANGE);
  }

  @Test
  void parsesPgObject() throws SQLException {
    PGobject object = new PGobject();
    object.setType("tstzrange");
    object.setValue("[2026-11-02T09:00:00Z,2026-11-02T11:00:00Z)");
    assertThat(PgRanges.parse(object)).isEqualTo(RANGE);
  }

  @Test
  void malformedRejected() {
    assertThatThrownBy(() -> PgRanges.parse("(2026-11-02T09:00:00Z,2026-11-02T11:00:00Z)"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PgRanges.parse("[2026-11-02T09:00:00Z]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PgRanges.parse((String) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PgRanges.parse("x")).isInstanceOf(IllegalArgumentException.class);
  }
}
