package com.keyloop.scheduler.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void generatesVersion7Ids() {
    assertThat(Ids.newId().version()).isEqualTo(7);
  }

  @Test
  void idsAreMonotonicAcrossCalls() {
    UUID[] ids = IntStream.range(0, 1000).mapToObj(i -> Ids.newId()).toArray(UUID[]::new);
    for (int i = 1; i < ids.length; i++) {
      assertThat(ids[i].compareTo(ids[i - 1])).isPositive();
    }
  }
}
