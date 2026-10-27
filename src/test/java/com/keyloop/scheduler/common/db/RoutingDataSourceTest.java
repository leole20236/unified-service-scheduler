package com.keyloop.scheduler.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RoutingDataSourceTest {

  private final RoutingDataSourceConfig.RoutingDataSource routing =
      new RoutingDataSourceConfig.RoutingDataSource();

  @AfterEach
  void clean() {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void readOnlyTransactionRoutesToReplica() {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThat(routing.determineCurrentLookupKey()).isEqualTo(DataSourceRole.REPLICA);
    assertThat(RoutingDataSourceConfig.currentRole()).isEqualTo(DataSourceRole.REPLICA);
  }

  @Test
  void otherwiseRoutesToPrimary() {
    assertThat(routing.determineCurrentLookupKey()).isEqualTo(DataSourceRole.PRIMARY);
    assertThat(RoutingDataSourceConfig.currentRole()).isEqualTo(DataSourceRole.PRIMARY);
  }
}
