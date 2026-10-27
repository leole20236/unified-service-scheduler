package com.keyloop.scheduler.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.common.api.ApiException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class CustomerServiceTest {

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final VehicleRepository vehicles = mock(VehicleRepository.class);
  private final CustomerService service = new CustomerService(customers, vehicles);

  private final UUID sub = UUID.randomUUID();
  private final CallerContext caller = new CallerContext(sub, Set.of(Role.CUSTOMER), Set.of());
  private final NewVehicleRequest request =
      new NewVehicleRequest("1FADP3F20EL000001", "AB 1", "Ford", "Focus", 2018, 1000);

  @Test
  void existingSubjectReturnsIdWithoutInsert() {
    UUID id = UUID.randomUUID();
    when(customers.findIdBySubject(sub)).thenReturn(Optional.of(id));

    assertThat(service.currentCustomer(caller)).isEqualTo(id);
    verify(customers, never()).insertForSubject(any(), any(), any());
  }

  @Test
  void unknownSubjectInsertsThenSelects() {
    UUID id = UUID.randomUUID();
    when(customers.findIdBySubject(sub)).thenReturn(Optional.empty(), Optional.of(id));

    assertThat(service.currentCustomer(caller)).isEqualTo(id);
    verify(customers).insertForSubject(any(), org.mockito.ArgumentMatchers.eq(sub), any());
  }

  @Test
  void vehicleNotOwnedIsNotFound() {
    UUID owner = UUID.randomUUID();
    UUID vehicle = UUID.randomUUID();
    when(vehicles.findOwned(owner, vehicle)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.requireOwnedVehicle(owner, vehicle))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.requireVehicleOf(owner, vehicle))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void ownedVehicleIsReturned() {
    UUID owner = UUID.randomUUID();
    VehicleView view = new VehicleView(UUID.randomUUID(), "V", "R", "M", "X", 2000, 1);
    when(vehicles.findOwned(owner, view.id())).thenReturn(Optional.of(view));

    assertThat(service.requireOwnedVehicle(owner, view.id())).isEqualTo(view);
  }

  @Test
  void duplicateVinMapsToVehicleAlreadyExists() {
    UUID id = UUID.randomUUID();
    when(customers.findIdBySubject(sub)).thenReturn(Optional.of(id));
    org.mockito.Mockito.doThrow(new DuplicateKeyException("dup"))
        .when(vehicles)
        .insert(any(), any(), any());

    assertThatThrownBy(() -> service.addMyVehicle(caller, request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.VEHICLE_ALREADY_EXISTS));
  }

  @Test
  void addMyVehicleReturnsStoredView() {
    UUID id = UUID.randomUUID();
    when(customers.findIdBySubject(sub)).thenReturn(Optional.of(id));
    when(vehicles.findOwned(org.mockito.ArgumentMatchers.eq(id), any()))
        .thenAnswer(
            i ->
                Optional.of(
                    new VehicleView(
                        i.getArgument(1), request.vin(), "AB 1", "Ford", "Focus", 2018, 1000)));

    VehicleView view = service.addMyVehicle(caller, request);
    assertThat(view.vin()).isEqualTo(request.vin());
  }

  @Test
  void dispatcherAddVehicleToUnknownCustomerIsNotFound() {
    UUID customer = UUID.randomUUID();
    when(customers.exists(customer)).thenReturn(false);

    assertThatThrownBy(() -> service.addVehicle(customer, request))
        .isInstanceOf(NotFoundException.class);
    verify(vehicles, never()).insert(any(), any(), any());
  }

  @Test
  void dispatcherAddVehicleDuplicateIsConflict() {
    UUID customer = UUID.randomUUID();
    when(customers.exists(customer)).thenReturn(true);
    org.mockito.Mockito.doThrow(new DuplicateKeyException("dup"))
        .when(vehicles)
        .insert(any(), any(), any());

    assertThatThrownBy(() -> service.addVehicle(customer, request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.VEHICLE_ALREADY_EXISTS));
  }

  @Test
  void shortSearchIsValidationError() {
    assertThatThrownBy(() -> service.search("ab", 20)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.search("  a ", 20)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.search(null, 20)).isInstanceOf(ValidationException.class);
    verify(customers, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void searchAttachesVehiclesInOneLookup() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    when(customers.search("abc", 20))
        .thenReturn(
            List.of(
                new CustomerView(c1, "One", null, null, List.of()),
                new CustomerView(c2, "Two", null, null, List.of())));
    VehicleView v = new VehicleView(UUID.randomUUID(), "V", "R", "M", "X", 2000, 1);
    when(vehicles.findByCustomers(List.of(c1, c2))).thenReturn(Map.of(c1, List.of(v)));

    List<CustomerView> result = service.search(" abc ", 20);

    assertThat(result.get(0).vehicles()).containsExactly(v);
    assertThat(result.get(1).vehicles()).isEmpty();
  }

  @Test
  void searchWithNoHitsSkipsVehicleLookup() {
    when(customers.search("abc", 20)).thenReturn(List.of());

    assertThat(service.search("abc", 20)).isEmpty();
    verify(vehicles, never()).findByCustomers(any());
  }

  @Test
  void walkInNeedsEmailOrPhone() {
    assertThatThrownBy(
            () -> service.createCustomer(caller, new NewCustomerRequest("Walk", " ", null)))
        .isInstanceOf(ValidationException.class);
    verify(customers, never()).insertWalkIn(any(), any(), any(), any(), any());
  }

  @Test
  void walkInIsCreatedWithTrimmedContact() {
    when(customers.find(any()))
        .thenAnswer(
            i -> Optional.of(new CustomerView(i.getArgument(0), "Walk", null, "123", List.of())));

    CustomerView view = service.createCustomer(caller, new NewCustomerRequest("Walk", "", " 123 "));

    verify(customers)
        .insertWalkIn(
            any(),
            org.mockito.ArgumentMatchers.eq("Walk"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("123"),
            org.mockito.ArgumentMatchers.eq(sub));
    assertThat(view.phone()).isEqualTo("123");
  }

  @Test
  void getCustomerUnknownIsNotFound() {
    UUID id = UUID.randomUUID();
    when(customers.find(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getCustomer(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void getCustomerIncludesVehicles() {
    UUID id = UUID.randomUUID();
    VehicleView v = new VehicleView(UUID.randomUUID(), "V", "R", "M", "X", 2000, 1);
    when(customers.find(id))
        .thenReturn(Optional.of(new CustomerView(id, "N", null, "1", List.of())));
    when(vehicles.findByCustomer(id)).thenReturn(List.of(v));

    assertThat(service.getCustomer(id).vehicles()).containsExactly(v);
  }

  @Test
  void myVehiclesListsForCurrentCustomer() {
    UUID id = UUID.randomUUID();
    when(customers.findIdBySubject(sub)).thenReturn(Optional.of(id));
    VehicleView v = new VehicleView(UUID.randomUUID(), "V", "R", "M", "X", 2000, 1);
    when(vehicles.findByCustomer(id)).thenReturn(List.of(v));

    assertThat(service.listMyVehicles(caller)).containsExactly(v);
    when(vehicles.findOwned(id, v.id())).thenReturn(Optional.of(v));
    assertThat(service.getMyVehicle(caller, v.id())).isEqualTo(v);
  }
}
