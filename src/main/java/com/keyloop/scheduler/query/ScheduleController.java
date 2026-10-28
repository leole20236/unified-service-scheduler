package com.keyloop.scheduler.query;

import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dispatcher's Gantt board (spec 14.4, 14.5). */
@RestController
public class ScheduleController {

  private final ScheduleQuery query;

  /** Creates the controller. */
  public ScheduleController(ScheduleQuery query) {
    this.query = query;
  }

  @GetMapping("/api/v1/dealerships/{dealershipId}/schedule")
  ResponseEntity<ScheduleView> schedule(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(defaultValue = "BAY") GroupBy groupBy) {
    DealershipAccess.requireStaff(caller, dealershipId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(query.forDay(dealershipId, date, groupBy));
  }
}
