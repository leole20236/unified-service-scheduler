package com.keyloop.scheduler.customer;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatcherCustomerApiIT extends IntegrationTest {

  private static final String CUSTOMERS = "/api/v1/dealerships/{d}/customers";

  private final io.restassured.http.Headers dispatcher =
      Headers.dispatcher(UUID.randomUUID(), Fixtures.RIVERSIDE);

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  @Test
  void searchRequiresThreeChars() {
    given()
        .headers(dispatcher)
        .queryParam("q", "al")
        .get(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"))
        .body("errors[0].field", equalTo("q"));
    given().headers(dispatcher).get(CUSTOMERS, Fixtures.RIVERSIDE).then().statusCode(400);
  }

  @Test
  void searchFindsByRegistrationPrefix() {
    given()
        .headers(dispatcher)
        .queryParam("q", "ali")
        .get(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(Fixtures.ALICE.toString()))
        .body("items[0].displayName", equalTo("Alice"))
        .body("items[0].vehicles", hasSize(1))
        .body("items[0].vehicles[0].id", equalTo(Fixtures.ALICE_FOCUS.toString()));
  }

  @Test
  void searchFindsByEmailAndPhonePrefixAndEscapesWildcards() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO customer (id, display_name, email, phone, created_by)"
            + " VALUES (?, 'Carl', 'Carl.Smith@Example.com', '+4412345', ?)",
        id,
        UUID.randomUUID());
    for (String q : List.of("carl.s", "CARL.SMITH@", "+4412")) {
      given()
          .headers(dispatcher)
          .queryParam("q", q)
          .get(CUSTOMERS, Fixtures.RIVERSIDE)
          .then()
          .statusCode(200)
          .body("items.id", hasItem(id.toString()));
    }
    given()
        .headers(dispatcher)
        .queryParam("q", "%%%")
        .get(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
    given()
        .headers(dispatcher)
        .queryParam("q", "c_rl")
        .get(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
  }

  @Test
  void createWalkInCustomerWithoutLogin() {
    String location =
        given()
            .headers(dispatcher)
            .contentType(ContentType.JSON)
            .body(Map.of("displayName", "Walk In", "phone", "555-0100"))
            .post(CUSTOMERS, Fixtures.RIVERSIDE)
            .then()
            .statusCode(201)
            .body("displayName", equalTo("Walk In"))
            .body("vehicles", hasSize(0))
            .extract()
            .header("Location");
    UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    assertThat(location)
        .isEqualTo("/api/v1/dealerships/" + Fixtures.RIVERSIDE + "/customers/" + id);
    assertThat(jdbc.queryForObject("SELECT idp_subject FROM customer WHERE id = ?", UUID.class, id))
        .isNull();
    given()
        .headers(dispatcher)
        .get(CUSTOMERS + "/{id}", Fixtures.RIVERSIDE, id)
        .then()
        .statusCode(200)
        .body("phone", equalTo("555-0100"));
  }

  @Test
  void createRequiresEmailOrPhone() {
    given()
        .headers(dispatcher)
        .contentType(ContentType.JSON)
        .body(Map.of("displayName", "No Contact"))
        .post(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
  }

  @Test
  void addVehicleToCustomer() {
    String location =
        given()
            .headers(dispatcher)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "vin",
                    "ZFA31200000000009",
                    "registration",
                    "DSP 9",
                    "make",
                    "Fiat",
                    "model",
                    "Panda",
                    "modelYear",
                    2021,
                    "mileage",
                    5))
            .post(CUSTOMERS + "/{c}/vehicles", Fixtures.RIVERSIDE, Fixtures.BOB)
            .then()
            .statusCode(201)
            .extract()
            .header("Location");
    assertThat(location).contains("/customers/" + Fixtures.BOB + "/vehicles/");
    given()
        .headers(dispatcher)
        .get(CUSTOMERS + "/{c}", Fixtures.RIVERSIDE, Fixtures.BOB)
        .then()
        .statusCode(200)
        .body("vehicles", hasSize(2));
    given()
        .headers(dispatcher)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "vin",
                "ZFA31200000000009",
                "registration",
                "DSP 9",
                "make",
                "Fiat",
                "model",
                "Panda",
                "modelYear",
                2021,
                "mileage",
                5))
        .post(CUSTOMERS + "/{c}/vehicles", Fixtures.RIVERSIDE, Fixtures.BOB)
        .then()
        .statusCode(409)
        .body("code", equalTo("VEHICLE_ALREADY_EXISTS"));
    given()
        .headers(dispatcher)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "vin",
                "ZFA31200000000008",
                "registration",
                "DSP 8",
                "make",
                "Fiat",
                "model",
                "Panda",
                "modelYear",
                2021,
                "mileage",
                5))
        .post(CUSTOMERS + "/{c}/vehicles", Fixtures.RIVERSIDE, UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void unknownCustomerIs404() {
    given()
        .headers(dispatcher)
        .get(CUSTOMERS + "/{id}", Fixtures.RIVERSIDE, UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void foreignDealershipIs403() {
    given()
        .headers(dispatcher)
        .queryParam("q", "alice")
        .get(CUSTOMERS, Fixtures.HUDSON)
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }

  @Test
  void customerRoleIs403() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .queryParam("q", "alice")
        .get(CUSTOMERS, Fixtures.RIVERSIDE)
        .then()
        .statusCode(403);
  }
}
