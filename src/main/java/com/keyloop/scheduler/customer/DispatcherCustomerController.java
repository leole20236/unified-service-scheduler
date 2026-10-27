package com.keyloop.scheduler.customer;

import com.keyloop.scheduler.catalog.CatalogController.Items;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer lookup and walk-in registration for dispatchers. */
@RestController
@RequestMapping("/api/v1/dealerships/{dealershipId}/customers")
public class DispatcherCustomerController {

  private static final String ROOT = "/api/v1/dealerships/";

  private final CustomerService service;

  /** Creates the controller. */
  public DispatcherCustomerController(CustomerService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<Items<CustomerView>> search(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "20") @Min(1) @Max(20) int limit) {
    DealershipAccess.requireStaff(caller, dealershipId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Items<>(service.search(q, limit)));
  }

  @PostMapping
  ResponseEntity<CustomerView> create(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @Valid @RequestBody NewCustomerRequest body) {
    DealershipAccess.requireStaff(caller, dealershipId);
    CustomerView created = service.createCustomer(caller, body);
    return ResponseEntity.created(URI.create(customerUri(dealershipId, created.id())))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  @GetMapping("/{customerId}")
  ResponseEntity<CustomerView> get(
      CallerContext caller, @PathVariable UUID dealershipId, @PathVariable UUID customerId) {
    DealershipAccess.requireStaff(caller, dealershipId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.getCustomer(customerId));
  }

  @PostMapping("/{customerId}/vehicles")
  ResponseEntity<VehicleView> addVehicle(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID customerId,
      @Valid @RequestBody NewVehicleRequest body) {
    DealershipAccess.requireStaff(caller, dealershipId);
    VehicleView created = service.addVehicle(customerId, body);
    return ResponseEntity.created(
            URI.create(customerUri(dealershipId, customerId) + "/vehicles/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  private static String customerUri(UUID dealershipId, UUID customerId) {
    return ROOT + dealershipId + "/customers/" + customerId;
  }
}
