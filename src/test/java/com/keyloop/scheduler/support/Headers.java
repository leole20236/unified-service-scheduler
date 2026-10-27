package com.keyloop.scheduler.support;

import io.restassured.http.Header;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/** Gateway identity headers for RestAssured requests. */
public final class Headers {

  private Headers() {}

  /** Headers of a customer caller. */
  public static io.restassured.http.Headers customer(UUID sub) {
    return new io.restassured.http.Headers(
        new Header("X-User-Id", sub.toString()), new Header("X-User-Roles", "CUSTOMER"));
  }

  /** Headers of a dispatcher caller acting for the given dealerships. */
  public static io.restassured.http.Headers dispatcher(UUID sub, UUID... dealerships) {
    return new io.restassured.http.Headers(
        new Header("X-User-Id", sub.toString()),
        new Header("X-User-Roles", "DISPATCHER"),
        new Header(
            "X-Dealership-Ids",
            Arrays.stream(dealerships).map(UUID::toString).collect(Collectors.joining(","))));
  }
}
