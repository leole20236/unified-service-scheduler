package com.keyloop.scheduler.support;

import static com.keyloop.scheduler.support.Fixtures.SEED_CUSTOMERS;
import static com.keyloop.scheduler.support.Fixtures.SEED_VEHICLES;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.keyloop.scheduler.SchedulerApplication;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: real PostgreSQL and Redis (singleton containers shared by the
 * whole JVM) and a fresh state before every test.
 */
@SpringBootTest(classes = SchedulerApplication.class, webEnvironment = RANDOM_PORT)
public abstract class IntegrationTest {

  /**
   * Every cached Spring context keeps two full Hikari pools (20 + 30); a few test classes need a
   * context of their own (connection counting, short hold TTL, a Redis that is stopped), so the
   * server allows more than the default 100 clients.
   */
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine")
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400");

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);

  /** Harness-owned Redis connection with a generous timeout (the app's 100 ms stays untouched). */
  private static final StatefulRedisConnection<String, String> CLEANUP_REDIS;

  static {
    POSTGRES.start();
    REDIS.start();
    RedisClient client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(6379))
                .withTimeout(Duration.ofSeconds(2))
                .build());
    CLEANUP_REDIS = client.connect();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  CLEANUP_REDIS.close();
                  client.shutdown();
                }));
  }

  @LocalServerPort protected int port;

  @Autowired protected JdbcTemplate jdbc;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    for (String role : List.of("primary", "replica")) {
      registry.add("app.datasource." + role + ".url", POSTGRES::getJdbcUrl);
      registry.add("app.datasource." + role + ".username", POSTGRES::getUsername);
      registry.add("app.datasource." + role + ".password", POSTGRES::getPassword);
    }
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  /**
   * The database and Redis settings of the shared containers as {@code key=value} properties, for
   * tests that start a second application instance against the same infrastructure.
   */
  protected static String[] sharedInfrastructureProperties() {
    List<String> properties = new ArrayList<>();
    for (String role : List.of("primary", "replica")) {
      properties.add("app.datasource." + role + ".url=" + POSTGRES.getJdbcUrl());
      properties.add("app.datasource." + role + ".username=" + POSTGRES.getUsername());
      properties.add("app.datasource." + role + ".password=" + POSTGRES.getPassword());
    }
    properties.add("spring.data.redis.host=" + REDIS.getHost());
    properties.add("spring.data.redis.port=" + REDIS.getMappedPort(6379));
    return properties.toArray(String[]::new);
  }

  private void deleteExcept(String table, List<UUID> keep) {
    String placeholders = String.join(", ", Collections.nCopies(keep.size(), "?"));
    jdbc.update("DELETE FROM " + table + " WHERE id NOT IN (" + placeholders + ")", keep.toArray());
  }

  @BeforeEach
  void resetState() {
    List<String> tables =
        jdbc.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
              AND table_name NOT IN ('dealership', 'opening_hours', 'skill', 'service_type',
                                     'dealership_service', 'service_bay', 'technician',
                                     'technician_skill', 'customer', 'vehicle')
            """,
            String.class);
    if (!tables.isEmpty()) {
      jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
    }
    deleteExcept("vehicle", SEED_VEHICLES);
    deleteExcept("customer", SEED_CUSTOMERS);
    CLEANUP_REDIS.sync().flushall();
  }
}
