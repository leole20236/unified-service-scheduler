package com.keyloop.scheduler.customer;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeVehicleApiIT extends IntegrationTest {

  private static final String VEHICLES = "/api/v1/me/vehicles";

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static Map<String, Object> body(String vin) {
    return Map.of(
        "vin",
        vin,
        "registration",
        "NEW 1",
        "make",
        "Fiat",
        "model",
        "Panda",
        "modelYear",
        2021,
        "mileage",
        5);
  }

  @Test
  void aliceListsOnlyHerVehicles() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get(VEHICLES)
        .then()
        .statusCode(200)
        .header("Cache-Control", "no-store")
        .body("items.id", hasItem(Fixtures.ALICE_FOCUS.toString()))
        .body("items.id", not(hasItem(Fixtures.BOB_GOLF.toString())))
        .body("items[0].make", equalTo("Ford"))
        .body("items[0].modelYear", equalTo(2018));
  }

  @Test
  void newUserGetsProfileOnFirstCall() {
    UUID sub = UUID.randomUUID();
    given()
        .headers(Headers.customer(sub))
        .get(VEHICLES)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE idp_subject = ?", Integer.class, sub))
        .isEqualTo(1);
    given().headers(Headers.customer(sub)).get(VEHICLES).then().statusCode(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE idp_subject = ?", Integer.class, sub))
        .isEqualTo(1);
  }

  @Test
  void addVehicleReturns201AndIsReadable() {
    UUID sub = UUID.randomUUID();
    String location =
        given()
            .headers(Headers.customer(sub))
            .contentType(ContentType.JSON)
            .body(body("ZFA31200000000001"))
            .post(VEHICLES)
            .then()
            .statusCode(201)
            .header("Location", containsString("/api/v1/me/vehicles/"))
            .extract()
            .header("Location");
    given()
        .headers(Headers.customer(sub))
        .get(location)
        .then()
        .statusCode(200)
        .body("vin", equalTo("ZFA31200000000001"));
  }

  @Test
  void addVehicleValidatesVin() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .contentType(ContentType.JSON)
        .body(body("1FADP3F20EL00000"))
        .post(VEHICLES)
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"))
        .body("errors[0].field", equalTo("vin"));
  }

  @Test
  void duplicateVinIs409VehicleAlreadyExists() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .contentType(ContentType.JSON)
        .body(body("1FADP3F20EL000001"))
        .post(VEHICLES)
        .then()
        .statusCode(409)
        .body("code", equalTo("VEHICLE_ALREADY_EXISTS"));
  }

  @Test
  void otherCustomersVehicleIs404() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get(VEHICLES + "/{id}", Fixtures.BOB_GOLF)
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get(VEHICLES + "/{id}", Fixtures.ALICE_FOCUS)
        .then()
        .statusCode(200)
        .body("vin", equalTo("1FADP3F20EL000001"));
  }

  @Test
  void dispatcherRoleCannotUseMeEndpoints() {
    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.RIVERSIDE))
        .get(VEHICLES)
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }
}
