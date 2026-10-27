package com.keyloop.scheduler.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(RoutingDataSourceIT.ProbeConfig.class)
class RoutingDataSourceIT extends IntegrationTest {

  private static final String PRIMARY = "scheduler-primary";
  private static final String REPLICA = "scheduler-replica";

  @Autowired private Probe probe;

  @Test
  void readOnlyTransactionUsesReplica() {
    assertThat(probe.roleInReadOnly()).isEqualTo(DataSourceRole.REPLICA);
    assertThat(probe.poolInReadOnly()).isEqualTo(REPLICA);
  }

  @Test
  void writeTransactionUsesPrimary() {
    assertThat(probe.roleInWrite()).isEqualTo(DataSourceRole.PRIMARY);
    assertThat(probe.poolInWrite()).isEqualTo(PRIMARY);
  }

  @Test
  void primaryReadUsesPrimary() {
    assertThat(probe.roleInPrimaryRead()).isEqualTo(DataSourceRole.PRIMARY);
    assertThat(probe.poolInPrimaryRead()).isEqualTo(PRIMARY);
  }

  @Test
  void primaryReadNestedInReadOnlyUsesPrimary() {
    assertThat(probe.poolInReadOnlyThenPrimaryRead()).containsExactly(REPLICA, PRIMARY);
  }

  @Test
  void dbClockInsideReadOnlyTransactionIsServedByPrimary() {
    assertThat(probe.dbClockPoolInReadOnly()).isEqualTo(PRIMARY);
  }

  @TestConfiguration
  static class ProbeConfig {
    @Bean
    Probe probe(JdbcTemplate jdbc, Inner inner, DbClock dbClock) {
      return new Probe(jdbc, inner, dbClock);
    }

    @Bean
    Inner inner(JdbcTemplate jdbc) {
      return new Inner(jdbc);
    }
  }

  static class Inner {
    private final JdbcTemplate jdbc;

    Inner(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @PrimaryRead
    public String poolInPrimaryRead() {
      return jdbc.queryForObject("SHOW application_name", String.class);
    }
  }

  static class Probe {
    private final JdbcTemplate jdbc;
    private final Inner inner;
    private final DbClock dbClock;

    Probe(JdbcTemplate jdbc, Inner inner, DbClock dbClock) {
      this.jdbc = jdbc;
      this.inner = inner;
      this.dbClock = dbClock;
    }

    private String pool() {
      return jdbc.queryForObject("SHOW application_name", String.class);
    }

    @Transactional(readOnly = true)
    public DataSourceRole roleInReadOnly() {
      return RoutingDataSourceConfig.currentRole();
    }

    @Transactional(readOnly = true)
    public String poolInReadOnly() {
      return pool();
    }

    @Transactional
    public DataSourceRole roleInWrite() {
      return RoutingDataSourceConfig.currentRole();
    }

    @Transactional
    public String poolInWrite() {
      return pool();
    }

    @PrimaryRead
    public DataSourceRole roleInPrimaryRead() {
      return RoutingDataSourceConfig.currentRole();
    }

    @PrimaryRead
    public String poolInPrimaryRead() {
      return pool();
    }

    @Transactional(readOnly = true)
    public java.util.List<String> poolInReadOnlyThenPrimaryRead() {
      return java.util.List.of(pool(), inner.poolInPrimaryRead());
    }

    @Transactional(readOnly = true)
    public String dbClockPoolInReadOnly() {
      dbClock.now();
      return dbClock.servedBy();
    }
  }
}
