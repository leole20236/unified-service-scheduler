package com.keyloop.scheduler.customer;

import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.api.VehicleAlreadyExistsException;
import com.keyloop.scheduler.common.db.PrimaryRead;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.security.CallerContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer profiles and vehicles. */
@Service
public class CustomerService {

  private static final int MIN_QUERY = 3;

  private final CustomerRepository customers;
  private final VehicleRepository vehicles;

  /** Creates the service. */
  public CustomerService(CustomerRepository customers, VehicleRepository vehicles) {
    this.customers = customers;
    this.vehicles = vehicles;
  }

  /**
   * The caller's customer id, creating the profile on first use. Writes, so it runs on the primary.
   */
  @Transactional
  public UUID currentCustomer(CallerContext caller) {
    return customers
        .findIdBySubject(caller.userId())
        .orElseGet(
            () -> {
              customers.insertForSubject(Ids.newId(), caller.userId(), caller.userId());
              return customers.findIdBySubject(caller.userId()).orElseThrow();
            });
  }

  /** The caller's vehicles (read from the primary: it is their own data). */
  @Transactional
  public List<VehicleView> listMyVehicles(CallerContext caller) {
    return vehicles.findByCustomer(currentCustomer(caller));
  }

  /** One of the caller's vehicles. */
  @Transactional
  public VehicleView getMyVehicle(CallerContext caller, UUID vehicleId) {
    return requireOwnedVehicle(currentCustomer(caller), vehicleId);
  }

  /**
   * The caller's vehicle as the replica sees it, without creating a profile: for hot paths that
   * must not touch the primary (holds). Empty may just mean replica lag; callers then ask {@link
   * #getMyVehicle}.
   */
  @Transactional(readOnly = true)
  public Optional<VehicleView> findMyVehicle(CallerContext caller, UUID vehicleId) {
    return customers
        .findIdBySubject(caller.userId())
        .flatMap(customerId -> vehicles.findOwned(customerId, vehicleId));
  }

  /** Adds a vehicle to the caller. */
  @Transactional
  public VehicleView addMyVehicle(CallerContext caller, NewVehicleRequest request) {
    return insertVehicle(currentCustomer(caller), request);
  }

  /** The vehicle if it belongs to the customer, else 404 (never reveals other customers). */
  public VehicleView requireOwnedVehicle(UUID customerId, UUID vehicleId) {
    return vehicles
        .findOwned(customerId, vehicleId)
        .orElseThrow(() -> new NotFoundException("Vehicle " + vehicleId + " was not found."));
  }

  /** Dispatcher variant of {@link #requireOwnedVehicle}; same rule. */
  public VehicleView requireVehicleOf(UUID customerId, UUID vehicleId) {
    return requireOwnedVehicle(customerId, vehicleId);
  }

  /** Dispatcher: adds a vehicle to a customer. */
  @Transactional
  public VehicleView addVehicle(UUID customerId, NewVehicleRequest request) {
    if (!customers.exists(customerId)) {
      throw customerNotFound(customerId);
    }
    return insertVehicle(customerId, request);
  }

  /** Dispatcher: creates a customer without a login. */
  @Transactional
  public CustomerView createCustomer(CallerContext caller, NewCustomerRequest request) {
    String email = blankToNull(request.email());
    String phone = blankToNull(request.phone());
    if (email == null && phone == null) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "email", "message", "email or phone is required")));
    }
    UUID id = Ids.newId();
    customers.insertWalkIn(id, request.displayName().trim(), email, phone, caller.userId());
    return customers.find(id).orElseThrow().withVehicles(List.of());
  }

  /** Dispatcher: a customer with vehicles, read from the primary so a new customer is visible. */
  @PrimaryRead
  public CustomerView getCustomer(UUID customerId) {
    CustomerView customer =
        customers.find(customerId).orElseThrow(() -> customerNotFound(customerId));
    return customer.withVehicles(vehicles.findByCustomer(customerId));
  }

  /** Dispatcher: prefix search; vehicles of the whole result page are fetched in one query. */
  @Transactional(readOnly = true)
  public List<CustomerView> search(String query, int limit) {
    String q = query == null ? "" : query.trim();
    if (q.length() < MIN_QUERY) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "q", "message", "must be at least 3 characters")));
    }
    List<CustomerView> found = customers.search(q, limit);
    if (found.isEmpty()) {
      return found;
    }
    Map<UUID, List<VehicleView>> byCustomer =
        vehicles.findByCustomers(found.stream().map(CustomerView::id).toList());
    return found.stream()
        .map(c -> c.withVehicles(byCustomer.getOrDefault(c.id(), List.of())))
        .toList();
  }

  private VehicleView insertVehicle(UUID customerId, NewVehicleRequest request) {
    UUID id = Ids.newId();
    try {
      vehicles.insert(id, customerId, request);
    } catch (DuplicateKeyException e) {
      throw new VehicleAlreadyExistsException("This customer already has a vehicle with that VIN.");
    }
    return requireOwnedVehicle(customerId, id);
  }

  private static NotFoundException customerNotFound(UUID customerId) {
    return new NotFoundException("Customer " + customerId + " was not found.");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
