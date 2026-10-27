package com.keyloop.scheduler.catalog;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogApiIT extends IntegrationTest {

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  @Test
  void listsSeededDealerships() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get("/api/v1/dealerships")
        .then()
        .statusCode(200)
        .header("Cache-Control", "private, max-age=300")
        .body("items", hasSize(3))
        .body("items.find { it.name == 'Riverside' }.timeZone", equalTo("Europe/London"))
        .body("items.find { it.name == 'Riverside' }.id", equalTo(Fixtures.RIVERSIDE.toString()));
  }

  @Test
  void servicesForRiverside() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get("/api/v1/dealerships/{id}/services", Fixtures.RIVERSIDE)
        .then()
        .statusCode(200)
        .header("Cache-Control", "private, max-age=300")
        .body("items", hasSize(3))
        .body("items.code", containsInAnyOrder("OIL_CHANGE", "BRAKE_INSPECTION", "DIAGNOSTIC"))
        .body("items.find { it.code == 'OIL_CHANGE' }.durationMinutes", equalTo(45));
  }

  @Test
  void dealershipOverrideDurationIsApplied() {
    jdbc.update(
        "UPDATE dealership_service SET duration_override_minutes = 50"
            + " WHERE dealership_id = ? AND service_type_id = ?",
        Fixtures.RIVERSIDE,
        Fixtures.OIL_CHANGE);
    try {
      given()
          .headers(Headers.customer(Fixtures.ALICE_SUB))
          .get("/api/v1/dealerships/{id}/services", Fixtures.RIVERSIDE)
          .then()
          .statusCode(200)
          .body("items.find { it.code == 'OIL_CHANGE' }.durationMinutes", equalTo(50));
    } finally {
      jdbc.update(
          "UPDATE dealership_service SET duration_override_minutes = NULL"
              + " WHERE dealership_id = ? AND service_type_id = ?",
          Fixtures.RIVERSIDE,
          Fixtures.OIL_CHANGE);
    }
  }

  @Test
  void unknownDealershipIs404() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get("/api/v1/dealerships/{id}/services", UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  @Test
  void dispatcherCanReadServices() {
    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.HUDSON))
        .get("/api/v1/dealerships/{id}/services", Fixtures.RIVERSIDE)
        .then()
        .statusCode(200);
  }

  @Test
  void unauthenticatedIs401() {
    given().get("/api/v1/dealerships").then().statusCode(401);
  }
}
