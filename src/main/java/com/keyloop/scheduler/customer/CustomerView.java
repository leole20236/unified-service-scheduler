package com.keyloop.scheduler.customer;

import java.util.List;
import java.util.UUID;

/** A customer with their vehicles, as exposed to dispatchers. */
public record CustomerView(
    UUID id, String displayName, String email, String phone, List<VehicleView> vehicles) {

  /** Copy of this customer carrying the given vehicles. */
  public CustomerView withVehicles(List<VehicleView> list) {
    return new CustomerView(id, displayName, email, phone, list);
  }
}
