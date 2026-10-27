package com.keyloop.scheduler.customer;

import com.keyloop.scheduler.catalog.CatalogController.Items;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.common.security.Role;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own vehicles. */
@RestController
@RequestMapping("/api/v1/me/vehicles")
public class MeVehicleController {

  private final CustomerService service;

  /** Creates the controller. */
  public MeVehicleController(CustomerService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<Items<VehicleView>> list(CallerContext caller) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Items<>(service.listMyVehicles(caller)));
  }

  @GetMapping("/{vehicleId}")
  ResponseEntity<VehicleView> get(CallerContext caller, @PathVariable UUID vehicleId) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.getMyVehicle(caller, vehicleId));
  }

  @PostMapping
  ResponseEntity<VehicleView> add(
      CallerContext caller, @Valid @RequestBody NewVehicleRequest body) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    VehicleView created = service.addMyVehicle(caller, body);
    return ResponseEntity.created(URI.create("/api/v1/me/vehicles/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }
}
