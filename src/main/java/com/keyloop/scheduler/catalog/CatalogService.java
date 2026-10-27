package com.keyloop.scheduler.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.common.api.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Cache-aside access to dealership catalogs: Redis first, the replica on miss or Redis failure. */
@Service
public class CatalogService {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogService.class);
  private static final Duration TTL = Duration.ofMinutes(10);

  private final CatalogRepository repository;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final MeterRegistry meters;

  /** Creates the service. */
  public CatalogService(
      CatalogRepository repository,
      StringRedisTemplate redis,
      ObjectMapper mapper,
      MeterRegistry meters) {
    this.repository = repository;
    this.redis = redis;
    this.mapper = mapper;
    this.meters = meters;
  }

  /** Returns the catalog of a dealership; unknown ids raise {@link NotFoundException}. */
  public ResourceCatalog get(UUID dealershipId) {
    String key = "catalog:" + dealershipId;
    String json;
    try {
      json = redis.opsForValue().get(key);
    } catch (RuntimeException e) {
      cacheError("read", key, e);
      return load(dealershipId); // Redis is unreachable: do not try to write either
    }
    if (json != null) {
      try {
        return mapper.readValue(json, ResourceCatalog.class);
      } catch (JsonProcessingException e) {
        cacheError("decode", key, e);
      }
    }
    ResourceCatalog loaded = load(dealershipId);
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(loaded), TTL);
    } catch (JsonProcessingException | RuntimeException e) {
      cacheError("write", key, e);
    }
    return loaded;
  }

  /** Lists all dealerships. */
  public List<DealershipView> listDealerships() {
    return repository.listDealerships();
  }

  private ResourceCatalog load(UUID dealershipId) {
    return repository
        .load(dealershipId)
        .orElseThrow(() -> new NotFoundException("Dealership not found."));
  }

  private void cacheError(String operation, String key, Exception e) {
    meters.counter("scheduler.cache.errors", "cache", "catalog").increment();
    LOG.warn("Catalog cache {} failed for {}: {}", operation, key, e.toString());
  }
}
