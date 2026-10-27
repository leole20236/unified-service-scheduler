package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.support.Fixtures;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestHashTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private static BookingRequest parse(String json) throws Exception {
    return JSON.readValue(json, BookingRequest.class);
  }

  private static BookingRequest request(String start) {
    return new BookingRequest(
        Fixtures.RIVERSIDE,
        null,
        Fixtures.ALICE_FOCUS,
        List.of(Fixtures.OIL_CHANGE, Fixtures.BRAKE_INSPECTION),
        OffsetDateTime.parse(start),
        "Squeak",
        null,
        null,
        null,
        false);
  }

  @Test
  void fieldOrderDoesNotChangeTheHash() throws Exception {
    String a =
        "{\"dealershipId\":\""
            + Fixtures.RIVERSIDE
            + "\",\"vehicleId\":\""
            + Fixtures.ALICE_FOCUS
            + "\",\"serviceTypeIds\":[\""
            + Fixtures.OIL_CHANGE
            + "\"],\"start\":\"2026-11-02T09:00:00Z\",\"customerNotes\":\"hi\"}";
    String b =
        "{\"customerNotes\":\"hi\",\"start\":\"2026-11-02T09:00:00Z\",\"serviceTypeIds\":[\""
            + Fixtures.OIL_CHANGE
            + "\"],\"vehicleId\":\""
            + Fixtures.ALICE_FOCUS
            + "\",\"dealershipId\":\""
            + Fixtures.RIVERSIDE
            + "\"}";

    String hash = RequestHash.of(BookingChannel.CUSTOMER, parse(a));

    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(RequestHash.of(BookingChannel.CUSTOMER, parse(b))).isEqualTo(hash);
  }

  @Test
  void differentStartChangesTheHash() {
    assertThat(RequestHash.of(BookingChannel.CUSTOMER, request("2026-11-02T09:00:00Z")))
        .isNotEqualTo(RequestHash.of(BookingChannel.CUSTOMER, request("2026-11-02T09:15:00Z")));
  }

  @Test
  void sameInstantInAnotherOffsetIsTheSameRequest() {
    assertThat(RequestHash.of(BookingChannel.CUSTOMER, request("2026-11-02T09:00:00Z")))
        .isEqualTo(RequestHash.of(BookingChannel.CUSTOMER, request("2026-11-02T10:00:00+01:00")));
  }

  @Test
  void routeIsPartOfTheHash() {
    BookingRequest r = request("2026-11-02T09:00:00Z");

    assertThat(RequestHash.of(BookingChannel.CUSTOMER, r))
        .isNotEqualTo(RequestHash.of(BookingChannel.DISPATCHER, r));
  }

  @Test
  void serviceOrderMatters() {
    BookingRequest r = request("2026-11-02T09:00:00Z");
    BookingRequest swapped =
        new BookingRequest(
            r.dealershipId(),
            r.customerId(),
            r.vehicleId(),
            List.of(Fixtures.BRAKE_INSPECTION, Fixtures.OIL_CHANGE),
            r.start(),
            r.customerNotes(),
            r.serviceBayId(),
            r.technicianId(),
            r.holdId(),
            r.overrideHold());

    assertThat(RequestHash.of(BookingChannel.CUSTOMER, r))
        .isNotEqualTo(RequestHash.of(BookingChannel.CUSTOMER, swapped));
  }
}
