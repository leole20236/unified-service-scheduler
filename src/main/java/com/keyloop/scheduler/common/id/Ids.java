package com.keyloop.scheduler.common.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Generator for application-assigned identifiers. */
public final class Ids {

  private Ids() {}

  /** Returns a new time-ordered UUIDv7. */
  public static UUID newId() {
    return UuidCreator.getTimeOrderedEpoch();
  }
}
