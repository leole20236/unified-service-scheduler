package com.keyloop.scheduler.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.IntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class CatalogServiceIT extends IntegrationTest {

  @Autowired private CatalogService catalogService;
  @Autowired private CatalogRepository repository;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper mapper;

  @Test
  void loadsActiveResourcesAndOpeningHours() {
    ResourceCatalog catalog = catalogService.get(Fixtures.RIVERSIDE);

    assertThat(catalog.dealership().zoneId()).isEqualTo(ZoneId.of("Europe/London"));
    assertThat(catalog.dealership().openingHours().get(DayOfWeek.MONDAY))
        .isEqualTo(new OpeningHours(LocalTime.of(8, 0), LocalTime.of(18, 0)));
    assertThat(catalog.dealership().openingHours()).doesNotContainKey(DayOfWeek.SUNDAY);
    assertThat(catalog.bays()).hasSize(3);
    assertThat(catalog.technicians()).hasSize(4);
    assertThat(catalog.technicians()).allSatisfy(t -> assertThat(t.skills()).isNotEmpty());
  }

  @Test
  void inactiveResourcesAreExcluded() {
    jdbc.update("UPDATE service_bay SET active = false WHERE id = ?", Fixtures.RIVERSIDE_BAY_3);
    jdbc.update("UPDATE technician SET active = false WHERE id = ?", Fixtures.TOM);
    try {
      ResourceCatalog catalog = catalogService.get(Fixtures.RIVERSIDE);
      assertThat(catalog.bays()).extracting(BayView::id).doesNotContain(Fixtures.RIVERSIDE_BAY_3);
      assertThat(catalog.technicians()).extracting(TechnicianView::id).doesNotContain(Fixtures.TOM);
    } finally {
      jdbc.update("UPDATE service_bay SET active = true WHERE id = ?", Fixtures.RIVERSIDE_BAY_3);
      jdbc.update("UPDATE technician SET active = true WHERE id = ?", Fixtures.TOM);
    }
  }

  @Test
  void secondCallServedFromRedis() throws Exception {
    String key = "catalog:" + Fixtures.RIVERSIDE;
    assertThat(redis.hasKey(key)).isFalse();

    ResourceCatalog first = catalogService.get(Fixtures.RIVERSIDE);

    assertThat(redis.hasKey(key)).isTrue();
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttl).isBetween(1L, 600L);
    assertThat(mapper.readValue(redis.opsForValue().get(key), ResourceCatalog.class))
        .isEqualTo(first);

    // Poison the cache entry's source: the DB change must not be visible while cached.
    jdbc.update("UPDATE service_bay SET active = false WHERE id = ?", Fixtures.RIVERSIDE_BAY_3);
    try {
      assertThat(catalogService.get(Fixtures.RIVERSIDE)).isEqualTo(first);
    } finally {
      jdbc.update("UPDATE service_bay SET active = true WHERE id = ?", Fixtures.RIVERSIDE_BAY_3);
    }
  }

  @Test
  void redisDownFallsBackToDatabase() {
    StringRedisTemplate broken =
        new StringRedisTemplate() {
          @Override
          public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("redis down");
          }
        };
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CatalogService degraded = new CatalogService(repository, broken, mapper, meters);

    assertThat(degraded.get(Fixtures.RIVERSIDE).bays()).hasSize(3);
    assertThat(meters.counter("scheduler.cache.errors", "cache", "catalog").count()).isEqualTo(1);
  }

  @Test
  void unknownIdIsNotFound() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalogService.get(UUID.randomUUID()))
        .isInstanceOf(com.keyloop.scheduler.common.api.NotFoundException.class);
  }
}
