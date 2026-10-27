package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Availability for customers and dispatchers; always live, never cached. */
@RestController
@RequestMapping("/api/v1/dealerships/{dealershipId}/availability")
public class AvailabilityController {

  private final AvailabilityService service;

  /** Creates the controller. */
  public AvailabilityController(AvailabilityService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<AvailabilityView> availability(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @RequestParam(required = false) String serviceTypeIds,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    AvailabilityView view = service.find(caller, dealershipId, parseIds(serviceTypeIds), from, to);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
  }

  private static List<UUID> parseIds(String raw) {
    if (raw == null || raw.isBlank()) {
      throw invalid("serviceTypeIds is required: 1 to 5 comma-separated ids.");
    }
    List<UUID> ids = new ArrayList<>();
    for (String part : raw.split(",", -1)) {
      try {
        ids.add(UUID.fromString(part.trim()));
      } catch (IllegalArgumentException e) {
        throw invalid("'" + part.trim() + "' is not a valid service id.");
      }
    }
    return ids;
  }

  private static ValidationException invalid(String message) {
    return new ValidationException(
        "1 field is invalid.", List.of(Map.of("field", "serviceTypeIds", "message", message)));
  }
}
