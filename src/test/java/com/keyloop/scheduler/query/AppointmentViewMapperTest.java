package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppointmentViewMapperTest {

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static AppointmentRecord record() {
    return new AppointmentRecord(
        UUID.fromString("0192f0c2-0000-7000-8000-000000000001"),
        "BOOKED",
        0,
        Fixtures.RIVERSIDE,
        "Riverside",
        "Europe/London",
        Fixtures.ALICE_FOCUS,
        "ALI 1",
        "Ford",
        "Focus",
        OffsetDateTime.parse("2026-07-06T10:00:00+01:00"),
        OffsetDateTime.parse("2026-07-06T12:00:00+01:00"),
        Fixtures.RIVERSIDE_BAY_3,
        "Bay 3",
        Fixtures.SAM,
        "Sam",
        List.of(
            new ServiceLine(2, "BRAKE_INSPECTION", "Brake inspection", 75),
            new ServiceLine(1, "OIL_CHANGE", "Oil change", 45)));
  }

  @Test
  void timesAreUtcInstants() throws Exception {
    AppointmentView view = AppointmentViewMapper.toView(record());

    assertThat(view.start()).isEqualTo(Instant.parse("2026-07-06T09:00:00Z"));
    assertThat(view.end()).isEqualTo(Instant.parse("2026-07-06T11:00:00Z"));
    String json = JSON.writeValueAsString(view);
    assertThat(json)
        .contains("\"start\":\"2026-07-06T09:00:00Z\"")
        .contains("\"end\":\"2026-07-06T11:00:00Z\"");
  }

  @Test
  void servicesAreOrderedByPosition() {
    AppointmentView view = AppointmentViewMapper.toView(record());

    assertThat(view.services())
        .extracting(AppointmentView.Service::code)
        .containsExactly("OIL_CHANGE", "BRAKE_INSPECTION");
    assertThat(view.services().get(1).durationMinutes()).isEqualTo(75);
  }

  @Test
  void nestedPartsAreFilled() {
    AppointmentView view = AppointmentViewMapper.toView(record());

    assertThat(view.status()).isEqualTo("BOOKED");
    assertThat(view.version()).isZero();
    assertThat(view.dealership())
        .isEqualTo(
            new AppointmentView.Dealership(Fixtures.RIVERSIDE, "Riverside", "Europe/London"));
    assertThat(view.vehicle())
        .isEqualTo(new AppointmentView.Vehicle(Fixtures.ALICE_FOCUS, "ALI 1", "Ford", "Focus"));
    assertThat(view.serviceBay())
        .isEqualTo(new AppointmentView.ServiceBay(Fixtures.RIVERSIDE_BAY_3, "Bay 3"));
    assertThat(view.technician()).isEqualTo(new AppointmentView.Technician(Fixtures.SAM, "Sam"));
  }
}
