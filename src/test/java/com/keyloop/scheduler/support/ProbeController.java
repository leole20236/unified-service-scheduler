package com.keyloop.scheduler.support;

import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints exercising the identity filter and the problem handler. */
@RestController
public class ProbeController {

  /** Body with one validated field. */
  public record Body(@Size(min = 17, max = 17) String vin) {}

  @GetMapping("/test/probe")
  Map<String, Object> echo(CallerContext caller) {
    return Map.of(
        "userId", caller.userId(),
        "roles", caller.roles(),
        "dealershipIds", caller.dealershipIds());
  }

  @GetMapping("/test/probe/slot")
  void slot() {
    throw new SlotUnavailableException(
        "No qualified technician and bay are free.",
        List.of(OffsetDateTime.parse("2026-11-02T09:15:00Z")),
        List.of());
  }

  @PostMapping("/test/probe/validate")
  void validate(@Valid @RequestBody Body body) {}

  @GetMapping("/test/probe/boom")
  void boom() {
    throw new IllegalStateException("secret internal detail");
  }

  @PatchMapping(value = "/test/probe", consumes = "application/merge-patch+json")
  void patch() {}

  @GetMapping("/test/probe/staff/{dealershipId}")
  void staff(CallerContext caller, @PathVariable UUID dealershipId) {
    DealershipAccess.requireStaff(caller, dealershipId);
  }

  @GetMapping("/test/probe/rule/{code}")
  void rule(@PathVariable String code) {
    throw new BusinessRuleException(code, "Rule broken.");
  }
}
