package com.keyloop.scheduler.common.db;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Routes read-only transactions to the replica pool and everything else to the primary. */
@Configuration(proxyBeanMethods = false)
public class RoutingDataSourceConfig {

  /** Applied to every new connection of both pools: the database session always runs in UTC. */
  static final String INIT_SQL = "SET TIME ZONE 'UTC'";

  private static final int PRIMARY_POOL_SIZE = 20;
  private static final int REPLICA_POOL_SIZE = 30;

  /** The role the current transaction would be routed to. */
  public static DataSourceRole currentRole() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ? DataSourceRole.REPLICA
        : DataSourceRole.PRIMARY;
  }

  /** Connection settings of the primary. */
  @Bean
  @ConfigurationProperties("app.datasource.primary")
  public DataSourceProperties primaryProperties() {
    return new DataSourceProperties();
  }

  /** Connection settings of the replica. */
  @Bean
  @ConfigurationProperties("app.datasource.replica")
  public DataSourceProperties replicaProperties() {
    return new DataSourceProperties();
  }

  /** Primary pool; also the only pool Flyway migrates. */
  @Bean(destroyMethod = "close")
  @FlywayDataSource
  public HikariDataSource primaryDataSource(
      @Qualifier("primaryProperties") DataSourceProperties properties) {
    return pool(properties, "primary", PRIMARY_POOL_SIZE);
  }

  /** Replica pool for read-only transactions. */
  @Bean(destroyMethod = "close")
  public HikariDataSource replicaDataSource(
      @Qualifier("replicaProperties") DataSourceProperties properties) {
    return pool(properties, "replica", REPLICA_POOL_SIZE);
  }

  /** Routing data source used by the application, connecting lazily. */
  @Bean
  @Primary
  public DataSource dataSource(
      @Qualifier("primaryDataSource") DataSource primary,
      @Qualifier("replicaDataSource") DataSource replica) {
    RoutingDataSource routing = new RoutingDataSource();
    routing.setTargetDataSources(
        Map.of(DataSourceRole.PRIMARY, primary, DataSourceRole.REPLICA, replica));
    routing.setDefaultTargetDataSource(primary);
    routing.afterPropertiesSet();
    return new LazyConnectionDataSourceProxy(routing);
  }

  private static HikariDataSource pool(DataSourceProperties properties, String name, int size) {
    HikariDataSource pool =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    pool.setPoolName(name);
    pool.setMaximumPoolSize(size);
    pool.setConnectionInitSql(INIT_SQL);
    return pool;
  }

  /** Picks the target by the read-only flag of the current transaction. */
  static class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
      return currentRole();
    }
  }
}
