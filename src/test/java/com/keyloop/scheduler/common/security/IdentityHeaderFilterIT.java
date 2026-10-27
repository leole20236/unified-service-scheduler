package com.keyloop.scheduler.common.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityHeaderFilterIT extends IntegrationTest {

  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private RequestSpecification withHeaders(String id, String roles) {
    RequestSpecification spec = given();
    if (id != null) {
      spec = spec.header("X-User-Id", id);
    }
    if (roles != null) {
      spec = spec.header("X-User-Roles", roles);
    }
    return spec;
  }

  private void assertUnauthenticated(RequestSpecification spec) {
    spec.get("/test/probe")
        .then()
        .statusCode(401)
        .contentType(containsString("application/problem+json"))
        .body("code", equalTo("UNAUTHENTICATED"))
        .body("traceId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString()));
  }

  @Test
  void missingUserIdIs401() {
    assertUnauthenticated(withHeaders(null, "CUSTOMER"));
  }

  @Test
  void malformedUserIdIs401() {
    assertUnauthenticated(withHeaders("nope", "CUSTOMER"));
  }

  @Test
  void unknownRoleIs401() {
    assertUnauthenticated(withHeaders(user.toString(), "ADMIN"));
  }

  @Test
  void customerHeadersResolve() {
    given()
        .headers(Headers.customer(user))
        .get("/test/probe")
        .then()
        .statusCode(200)
        .body("userId", equalTo(user.toString()))
        .body("roles", containsInAnyOrder("CUSTOMER"));
  }

  @Test
  void dispatcherWithDealershipsResolves() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    given()
        .headers(Headers.dispatcher(user, a, b))
        .get("/test/probe")
        .then()
        .statusCode(200)
        .body("roles", containsInAnyOrder("DISPATCHER"))
        .body("dealershipIds", hasItem(a.toString()))
        .body("dealershipIds", hasItem(b.toString()));
  }

  @Test
  void actuatorHealthNeedsNoHeaders() {
    given().get("/actuator/health").then().statusCode(200);
  }
}
