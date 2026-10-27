package com.keyloop.scheduler.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared catalog reads, open to any authenticated role. */
@RestController
@RequestMapping("/api/v1/dealerships")
public class CatalogController {

  private static final String CACHE = "private, max-age=300";

  private final CatalogService catalog;

  /** Creates the controller. */
  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  /** Wrapped collection. */
  public record Items<T>(List<T> items) {}

  /** Dealership summary. */
  public record DealershipSummary(UUID id, String name, String timeZone) {}

  /** Service summary. */
  public record ServiceSummary(UUID id, String code, String name, int durationMinutes) {}

  @GetMapping
  ResponseEntity<Items<DealershipSummary>> dealerships() {
    List<DealershipSummary> items =
        catalog.listDealerships().stream()
            .map(d -> new DealershipSummary(d.id(), d.name(), d.zoneId().getId()))
            .toList();
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE).body(new Items<>(items));
  }

  @GetMapping("/{dealershipId}/services")
  ResponseEntity<Items<ServiceSummary>> services(@PathVariable UUID dealershipId) {
    List<ServiceSummary> items =
        catalog.get(dealershipId).services().stream()
            .map(s -> new ServiceSummary(s.id(), s.code(), s.name(), s.durationMinutes()))
            .toList();
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE).body(new Items<>(items));
  }
}
