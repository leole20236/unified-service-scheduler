package com.keyloop.scheduler.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DbClockIT extends IntegrationTest {

  @Autowired private DbClock dbClock;

  @Test
  void nowIsCloseToDatabaseTime() {
    assertThat(Duration.between(dbClock.now(), Instant.now()).abs())
        .isLessThan(Duration.ofSeconds(5));
  }
}
