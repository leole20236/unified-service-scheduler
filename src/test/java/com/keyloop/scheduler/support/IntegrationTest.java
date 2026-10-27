package com.keyloop.scheduler.support;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.keyloop.scheduler.SchedulerApplication;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @LocalServerPort protected int port;

  @Autowired protected JdbcTemplate jdbc;

  @Autowired private RedisConnectionFactory redisConnectionFactory;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    for (String role : List.of("primary", "replica")) {
      registry.add("app.datasource." + role + ".url", POSTGRES::getJdbcUrl);
      registry.add("app.datasource." + role + ".username", POSTGRES::getUsername);
      registry.add("app.datasource." + role + ".password", POSTGRES::getPassword);
    }
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @BeforeEach
  void resetState() {
    List<String> tables =
        jdbc.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
              AND table_name IN ('appointment', 'appointment_service', 'resource_reservation',
                                 'outbox_event', 'idempotency_key', 'vehicle', 'customer')
            """,
            String.class);
    if (!tables.isEmpty()) {
      jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
    }
    try (var connection = redisConnectionFactory.getConnection()) {
      connection.serverCommands().flushAll();
    }
  }
}
