package com.keyloop.scheduler.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keyloop.scheduler.common.api.NotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CatalogServiceTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-d000-000000000001");
  private static final String KEY = "catalog:" + ID;

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final CatalogRepository repository = mock(CatalogRepository.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values = mockValues();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private CatalogService service;

  @SuppressWarnings("unchecked")
  private static ValueOperations<String, String> mockValues() {
    return mock(ValueOperations.class);
  }

  private static ResourceCatalog catalog() {
    return new ResourceCatalog(
        new DealershipView(
            ID,
            "Riverside",
            ZoneId.of("Europe/London"),
            5,
            Map.of(DayOfWeek.MONDAY, new OpeningHours(LocalTime.of(8, 0), LocalTime.of(18, 0)))),
        List.of(new ServiceTypeView(UUID.randomUUID(), "OIL_CHANGE", "Oil change", 45, "OIL")),
        List.of(new BayView(UUID.randomUUID(), "Bay 1")),
        List.of(new TechnicianView(UUID.randomUUID(), "Sam", Set.of("OIL"))));
  }

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    service = new CatalogService(repository, redis, mapper, meters);
  }

  private double errors() {
    var counter = meters.find("scheduler.cache.errors").tag("cache", "catalog").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void cacheHitDoesNotTouchRepository() throws Exception {
    ResourceCatalog expected = catalog();
    when(values.get(KEY)).thenReturn(mapper.writeValueAsString(expected));

    assertThat(service.get(ID)).isEqualTo(expected);
    verify(repository, never()).load(any());
  }

  @Test
  void missLoadsAndStoresWithTenMinuteTtl() throws Exception {
    ResourceCatalog expected = catalog();
    when(values.get(KEY)).thenReturn(null);
    when(repository.load(ID)).thenReturn(Optional.of(expected));

    assertThat(service.get(ID)).isEqualTo(expected);
    verify(values)
        .set(eq(KEY), eq(mapper.writeValueAsString(expected)), eq(Duration.ofSeconds(600)));
  }

  @Test
  void redisReadErrorFallsBackToRepositoryAndCountsError() {
    ResourceCatalog expected = catalog();
    when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
    when(repository.load(ID)).thenReturn(Optional.of(expected));

    assertThat(service.get(ID)).isEqualTo(expected);
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void redisWriteErrorStillReturnsCatalogAndCountsError() {
    ResourceCatalog expected = catalog();
    when(values.get(KEY)).thenReturn(null);
    when(repository.load(ID)).thenReturn(Optional.of(expected));
    org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));

    assertThat(service.get(ID)).isEqualTo(expected);
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void corruptCacheEntryIsTreatedAsMiss() {
    ResourceCatalog expected = catalog();
    when(values.get(KEY)).thenReturn("{not json");
    when(repository.load(ID)).thenReturn(Optional.of(expected));

    assertThat(service.get(ID)).isEqualTo(expected);
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void unknownDealershipIsNotFound() {
    when(values.get(KEY)).thenReturn(null);
    when(repository.load(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(ID)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void listsDealershipsFromRepository() {
    DealershipView view = catalog().dealership();
    when(repository.listDealerships()).thenReturn(List.of(view));

    assertThat(service.listDealerships()).containsExactly(view);
  }
}
