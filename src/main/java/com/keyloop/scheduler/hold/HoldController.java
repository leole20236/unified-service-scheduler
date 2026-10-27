package com.keyloop.scheduler.hold;

import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.common.security.Role;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's slot holds (spec 14.3): {@code /me/holds}. */
@RestController
@RequestMapping(HoldController.ROOT)
public class HoldController {

  static final String ROOT = "/api/v1/me/holds";

  private final HoldService service;

  /** Creates the controller. */
  public HoldController(HoldService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<HoldResource> create(CallerContext caller, @Valid @RequestBody HoldRequest body) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    HoldView view = service.create(caller, body);
    HoldResource hold =
        new HoldResource(
            view.holdId(),
            body.dealershipId(),
            body.serviceTypeIds(),
            view.start(),
            view.end(),
            view.expiresAt());
    return ResponseEntity.created(URI.create(ROOT + "/" + view.holdId()))
        .cacheControl(CacheControl.noStore())
        .body(hold);
  }

  @GetMapping("/{holdId}")
  ResponseEntity<HoldResource> get(CallerContext caller, @PathVariable UUID holdId) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.get(caller, holdId));
  }

  @DeleteMapping("/{holdId}")
  ResponseEntity<Void> delete(CallerContext caller, @PathVariable UUID holdId) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    service.release(caller, holdId);
    return ResponseEntity.noContent().build();
  }
}
