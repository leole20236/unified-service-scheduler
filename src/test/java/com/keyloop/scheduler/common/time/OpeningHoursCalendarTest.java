package com.keyloop.scheduler.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.CatalogFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OpeningHoursCalendarTest {

  @Test
  void sundayClosedForRiverside() {
    assertThat(OpeningHoursCalendar.window(CatalogFixtures.riverside(), LocalDate.of(2026, 11, 1)))
        .isEmpty();
  }

  @Test
  void windowUsesDealershipZone() {
    var d = CatalogFixtures.riverside();
    assertThat(OpeningHoursCalendar.window(d, LocalDate.of(2026, 11, 2)))
        .contains(
            new TimeRange(
                Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T18:00:00Z")));
    assertThat(OpeningHoursCalendar.window(d, LocalDate.of(2026, 7, 6)))
        .contains(
            new TimeRange(
                Instant.parse("2026-07-06T07:00:00Z"), Instant.parse("2026-07-06T17:00:00Z")));
  }

  @Test
  void springForwardWindowIsShorter() {
    var window =
        OpeningHoursCalendar.window(CatalogFixtures.nightOwl(), LocalDate.of(2027, 3, 28))
            .orElseThrow();
    assertThat(window.minutes()).isEqualTo(120);
  }

  @Test
  void localDateAndOffsetUseZone() {
    var d = CatalogFixtures.hudson();
    Instant t = Instant.parse("2026-11-03T03:30:00Z");
    assertThat(OpeningHoursCalendar.localDate(d, t)).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(OpeningHoursCalendar.toDealershipOffset(d, t))
        .isEqualTo(OffsetDateTime.of(2026, 11, 2, 22, 30, 0, 0, ZoneOffset.ofHours(-5)));
  }
}
