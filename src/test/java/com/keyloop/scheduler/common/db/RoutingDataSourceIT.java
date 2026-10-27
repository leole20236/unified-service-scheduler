package com.keyloop.scheduler.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(RoutingDataSourceIT.ProbeConfig.class)
class RoutingDataSourceIT extends IntegrationTest {

  @Autowired private Probe probe;

  @Test
  void readOnlyTransactionUsesReplica() {
    assertThat(probe.roleInReadOnly()).isEqualTo(DataSourceRole.REPLICA);
  }

  @Test
  void writeTransactionUsesPrimary() {
    assertThat(probe.roleInWrite()).isEqualTo(DataSourceRole.PRIMARY);
  }

  @Test
  void primaryReadUsesPrimary() {
    assertThat(probe.roleInPrimaryRead()).isEqualTo(DataSourceRole.PRIMARY);
  }

  @TestConfiguration
  static class ProbeConfig {
    @Bean
    Probe probe() {
      return new Probe();
    }
  }

  static class Probe {
    @Transactional(readOnly = true)
    public DataSourceRole roleInReadOnly() {
      return RoutingDataSourceConfig.currentRole();
    }

    @Transactional
    public DataSourceRole roleInWrite() {
      return RoutingDataSourceConfig.currentRole();
    }

    @PrimaryRead
    public DataSourceRole roleInPrimaryRead() {
      return RoutingDataSourceConfig.currentRole();
    }
  }
}
