package com.keyloop.scheduler.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

  private static final Instant NINE = Instant.parse("2026-11-02T09:00:00Z");

  private static TimeRange range(int fromMinute, int toMinute) {
    return new TimeRange(NINE.plusSeconds(fromMinute * 60L), NINE.plusSeconds(toMinute * 60L));
  }

  @Test
  void backToBackDoesNotOverlap() {
    assertThat(range(0, 60).overlaps(range(60, 120))).isFalse();
    assertThat(range(60, 120).overlaps(range(0, 60))).isFalse();
  }

  @Test
  void partialOverlapOverlaps() {
    assertThat(range(0, 60).overlaps(range(30, 90))).isTrue();
    assertThat(range(30, 90).overlaps(range(0, 60))).isTrue();
  }

  @Test
  void containedOverlaps() {
    assertThat(range(0, 120).overlaps(range(30, 60))).isTrue();
    assertThat(range(30, 60).overlaps(range(0, 120))).isTrue();
  }

  @Test
  void zeroLengthRejected() {
    assertThatThrownBy(() -> new TimeRange(NINE, NINE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TimeRange(NINE, NINE.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void extendEndAndMinutes() {
    TimeRange extended = range(0, 60).extendEnd(Duration.ofMinutes(10));
    assertThat(extended.minutes()).isEqualTo(70);
    assertThat(extended.start()).isEqualTo(NINE);
  }
}
