package com.keyloop.scheduler.common.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerIT extends IntegrationTest {

  private static final Map<String, String> TITLES =
      Map.ofEntries(
          Map.entry("VALIDATION_ERROR", "Request is invalid"),
          Map.entry("UNAUTHENTICATED", "Authentication required"),
          Map.entry("FORBIDDEN", "Not allowed"),
          Map.entry("NOT_FOUND", "Resource not found"),
          Map.entry("METHOD_NOT_ALLOWED", "Method not allowed"),
          Map.entry("NOT_ACCEPTABLE", "Not acceptable"),
          Map.entry("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"),
          Map.entry("SLOT_UNAVAILABLE", "Requested time is not available"),
          Map.entry("SLOT_HELD", "Time is held by a customer"),
          Map.entry("INVALID_TRANSITION", "Action not allowed in current status"),
          Map.entry("REQUEST_IN_PROGRESS", "Duplicate request in progress"),
          Map.entry("VEHICLE_ALREADY_EXISTS", "Vehicle already registered"),
          Map.entry("VERSION_CONFLICT", "Appointment was changed by someone else"),
          Map.entry("MISDIRECTED_REGION", "Wrong region"),
          Map.entry("OUTSIDE_BOOKING_WINDOW", "Time is outside the booking window"),
          Map.entry("OUTSIDE_OPENING_HOURS", "Outside opening hours"),
          Map.entry("CHANGE_WINDOW_CLOSED", "Too late to change"),
          Map.entry("SERVICE_NOT_OFFERED", "Service not offered here"),
          Map.entry("TECHNICIAN_NOT_QUALIFIED", "Technician lacks required skills"),
          Map.entry("START_FIXED", "Start time can no longer change"),
          Map.entry("NO_SHOW_TOO_EARLY", "Too early to mark no-show"),
          Map.entry("HOLD_MISMATCH", "Hold does not match the request"),
          Map.entry("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused"),
          Map.entry("PRECONDITION_REQUIRED", "Precondition header required"),
          Map.entry("RATE_LIMITED", "Too many requests"),
          Map.entry("INTERNAL_ERROR", "Unexpected error"),
          Map.entry("HOLDS_UNAVAILABLE", "Slot holds temporarily unavailable"),
          Map.entry("SERVICE_UNAVAILABLE", "Service temporarily unavailable"));

  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private ValidatableResponse problem(ValidatableResponse response, int status, String code) {
    return response
        .statusCode(status)
        .contentType(containsString("application/problem+json"))
        .body("code", equalTo(code))
        .body("traceId", not(blankOrNullString()));
  }

  @Test
  void slotUnavailableRendersAlternatives() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/slot").then(),
            409,
            "SLOT_UNAVAILABLE")
        .body("alternatives[0]", equalTo("2026-11-02T09:15:00Z"))
        .body("title", equalTo("Requested time is not available"));
  }

  @Test
  void validationErrorListsFields() {
    problem(
            given()
                .headers(Headers.customer(user))
                .contentType("application/json")
                .body("{\"vin\":\"short\"}")
                .post("/test/probe/validate")
                .then(),
            400,
            "VALIDATION_ERROR")
        .body("errors[0].field", equalTo("vin"))
        .body("errors[0].message", notNullValue());
  }

  @Test
  void unexpectedExceptionIs500WithoutStackTrace() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/boom").then(),
            500,
            "INTERNAL_ERROR")
        .body("$", not(hasKey("trace")))
        .body("$", not(hasKey("exception")))
        .body("detail", not(containsString("secret")));
  }

  @Test
  void unsupportedMediaTypeIsProblemJson() {
    problem(
        given()
            .headers(Headers.customer(user))
            .contentType("application/json")
            .body("{}")
            .patch("/test/probe")
            .then(),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void wrongMethodIsProblemJson() {
    problem(
        given().headers(Headers.customer(user)).delete("/test/probe").then(),
        405,
        "METHOD_NOT_ALLOWED");
  }

  @Test
  void unknownRouteIsProblemJson() {
    problem(
        given().headers(Headers.customer(user)).get("/api/v1/nowhere").then(), 404, "NOT_FOUND");
  }

  @Test
  void unacceptableMediaTypeIsProblemJson() {
    problem(
        given().headers(Headers.customer(user)).accept("text/csv").get("/test/probe").then(),
        406,
        "NOT_ACCEPTABLE");
  }

  @Test
  void everyResponseEchoesRequestId() {
    given()
        .headers(Headers.customer(user))
        .header("X-Request-Id", "req-abc")
        .get("/test/probe")
        .then()
        .header("X-Request-Id", equalTo("req-abc"));
    given()
        .headers(Headers.customer(user))
        .get("/test/probe")
        .then()
        .header("X-Request-Id", not(blankOrNullString()));
    given().get("/test/probe").then().statusCode(401).header("X-Request-Id", notNullValue());
  }

  @Test
  void titleIsFixedPerCode() {
    assertThat(TITLES.keySet())
        .containsExactlyInAnyOrderElementsOf(
            java.util.Arrays.stream(ProblemCodes.values()).map(Enum::name).toList());
    for (ProblemCodes code : ProblemCodes.values()) {
      given()
          .headers(Headers.customer(user))
          .get("/test/probe/code/" + code.name())
          .then()
          .statusCode(code.status())
          .contentType(containsString("application/problem+json"))
          .body("code", equalTo(code.name()))
          .body("title", equalTo(TITLES.get(code.name())));
    }
  }

  @Test
  void staffGuardRejectsForeignDealership() {
    UUID mine = UUID.randomUUID();
    given()
        .headers(Headers.dispatcher(user, mine))
        .get("/test/probe/staff/" + mine)
        .then()
        .statusCode(200);
    problem(
        given()
            .headers(Headers.dispatcher(user, mine))
            .get("/test/probe/staff/" + UUID.randomUUID())
            .then(),
        403,
        "FORBIDDEN");
  }

  @Test
  void missingPreconditionHeaderIs428() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/header").then(),
            428,
            "PRECONDITION_REQUIRED")
        .body("detail", equalTo("If-Match header is required for this request."));
  }

  @Test
  void otherMissingHeaderIs400() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/other-header").then(),
            400,
            "VALIDATION_ERROR")
        .body("errors[0].field", equalTo("X-Other"));
  }

  @Test
  void missingAndInvalidParametersAre400() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/param").then(),
            400,
            "VALIDATION_ERROR")
        .body("errors[0].field", equalTo("n"));
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/param?n=9").then(),
            400,
            "VALIDATION_ERROR")
        .body("errors[0].field", equalTo("n"));
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/param?n=abc").then(),
            400,
            "VALIDATION_ERROR")
        .body("errors[0].field", equalTo("n"));
  }

  @Test
  void responseStatusExceptionMapsToItsCode() {
    problem(
            given().headers(Headers.customer(user)).get("/test/probe/status").then(),
            404,
            "NOT_FOUND")
        .body("detail", equalTo("Nothing here."));
  }

  @Test
  void businessRuleIs422() {
    problem(
        given().headers(Headers.customer(user)).get("/test/probe/rule").then(),
        422,
        "HOLD_MISMATCH");
  }
}
