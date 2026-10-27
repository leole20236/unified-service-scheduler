package com.keyloop.scheduler.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SqlStatesTest {

  @Test
  void findsStateAnywhereInTheCauseChain() {
    Exception e =
        new DataIntegrityViolationException(
            "outer", new RuntimeException("mid", new SQLException("conflict", "23P01")));

    assertThat(SqlStates.has(e, SqlStates.EXCLUSION_VIOLATION)).isTrue();
    assertThat(SqlStates.has(e, SqlStates.DEADLOCK_DETECTED)).isFalse();
  }

  @Test
  void followsChainedSqlExceptions() {
    SQLException batch = new SQLException("batch", "08000");
    batch.setNextException(new SQLException("real", "40P01"));

    assertThat(SqlStates.has(batch, "08000")).isTrue();
    assertThat(SqlStates.has(new RuntimeException(batch), SqlStates.DEADLOCK_DETECTED)).isTrue();
  }

  @Test
  void noSqlExceptionMeansNoState() {
    assertThat(SqlStates.has(new RuntimeException("plain"), SqlStates.UNIQUE_VIOLATION)).isFalse();
    assertThat(SqlStates.has(null, SqlStates.UNIQUE_VIOLATION)).isFalse();
  }

  @Test
  void cyclicCauseChainDoesNotLoop() {
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    assertThat(SqlStates.has(a, SqlStates.UNIQUE_VIOLATION)).isFalse();
  }
}
