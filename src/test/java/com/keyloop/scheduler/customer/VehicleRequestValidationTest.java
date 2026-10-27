package com.keyloop.scheduler.customer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VehicleRequestValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private Set<String> invalidFields(NewVehicleRequest request) {
    return validator.validate(request).stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }

  private NewVehicleRequest with(String vin, String registration, Integer year, Integer mileage) {
    return new NewVehicleRequest(vin, registration, "Ford", "Focus", year, mileage);
  }

  @Test
  void validRequestHasNoViolations() {
    assertThat(invalidFields(with("1FADP3F20EL000001", "AB 1", 2018, 0))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1FADP3F20EL00000", // 16
        "1FADP3F20EL0000011", // 18
        "1FADP3F20EL00000I", // I
        "1FADP3F20EL00000O", // O
        "1FADP3F20EL00000Q", // Q
        "1fadp3f20el000001", // lower case
        ""
      })
  void badVinIsRejected(String vin) {
    assertThat(invalidFields(with(vin, "AB 1", 2018, 1))).containsExactly("vin");
  }

  @Test
  void missingVinIsRejected() {
    assertThat(invalidFields(with(null, "AB 1", 2018, 1))).containsExactly("vin");
  }

  @Test
  void registrationTooLongIsRejected() {
    assertThat(invalidFields(with("1FADP3F20EL000001", "X".repeat(16), 2018, 1)))
        .containsExactly("registration");
    assertThat(invalidFields(with("1FADP3F20EL000001", "X".repeat(15), 2018, 1))).isEmpty();
  }

  @Test
  void modelYearBounds() {
    assertThat(invalidFields(with("1FADP3F20EL000001", "A", 1949, 1))).containsExactly("modelYear");
    assertThat(invalidFields(with("1FADP3F20EL000001", "A", 2101, 1))).containsExactly("modelYear");
    assertThat(invalidFields(with("1FADP3F20EL000001", "A", 1950, 1))).isEmpty();
    assertThat(invalidFields(with("1FADP3F20EL000001", "A", 2100, 1))).isEmpty();
  }

  @Test
  void negativeMileageIsRejected() {
    assertThat(invalidFields(with("1FADP3F20EL000001", "A", 2018, -1))).containsExactly("mileage");
  }

  @Test
  void customerRequestNeedsDisplayName() {
    assertThat(
            validator.validate(new NewCustomerRequest(" ", "a@b.co", null)).stream()
                .map(v -> v.getPropertyPath().toString()))
        .containsExactly("displayName");
    assertThat(validator.validate(new NewCustomerRequest("N", "not-an-email", null))).hasSize(1);
  }
}
