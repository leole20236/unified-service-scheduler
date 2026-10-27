package com.keyloop.scheduler.booking;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Randomness for allocation tie-breaks. */
@Configuration
class AllocationConfig {

  /**
   * Delegates to {@link ThreadLocalRandom} per call: {@code SplittableRandom} is not thread-safe
   * and a shared instance would need locking, whereas the thread-local generator is lock-free and
   * contention-free across request threads (tie-breaks need spread, not reproducibility).
   */
  @Bean
  RandomGenerator allocationRandom() {
    return () -> ThreadLocalRandom.current().nextLong();
  }
}
