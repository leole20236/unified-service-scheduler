package com.keyloop.scheduler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.keyloop.scheduler.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class ApplicationStartsIT extends IntegrationTest {

  @Test
  void actuatorHealthIsUp() {
    given()
        .port(port)
        .when()
        .get("/actuator/health")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }
}
