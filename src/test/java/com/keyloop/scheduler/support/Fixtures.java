package com.keyloop.scheduler.support;

import java.util.List;
import java.util.UUID;

/** Seed ids defined once in {@code V2__seed.sql}; keep both files in sync. */
public final class Fixtures {

  public static final UUID RIVERSIDE = id("00000000-0000-0000-d000-000000000001");
  public static final UUID HUDSON = id("00000000-0000-0000-d000-000000000002");
  public static final UUID SOLO = id("00000000-0000-0000-d000-000000000003");

  public static final UUID RIVERSIDE_BAY_1 = id("00000000-0000-0000-b000-000000000011");
  public static final UUID RIVERSIDE_BAY_2 = id("00000000-0000-0000-b000-000000000012");
  public static final UUID RIVERSIDE_BAY_3 = id("00000000-0000-0000-b000-000000000013");
  public static final UUID HUDSON_BAY_1 = id("00000000-0000-0000-b000-000000000021");
  public static final UUID HUDSON_BAY_2 = id("00000000-0000-0000-b000-000000000022");
  public static final UUID SOLO_BAY = id("00000000-0000-0000-b000-000000000031");

  public static final UUID SAM = id("00000000-0000-0000-7000-000000000011");
  public static final UUID LEE = id("00000000-0000-0000-7000-000000000012");
  public static final UUID ANA = id("00000000-0000-0000-7000-000000000013");
  public static final UUID TOM = id("00000000-0000-0000-7000-000000000014");
  public static final UUID JO = id("00000000-0000-0000-7000-000000000021");
  public static final UUID KIM = id("00000000-0000-0000-7000-000000000022");
  public static final UUID SOLO_TECH = id("00000000-0000-0000-7000-000000000031");

  public static final String OIL = "OIL";
  public static final String BRAKES = "BRAKES";
  public static final String DIAG = "DIAG";

  public static final UUID OIL_CHANGE = id("00000000-0000-0000-5000-000000000001");
  public static final UUID BRAKE_INSPECTION = id("00000000-0000-0000-5000-000000000002");
  public static final UUID DIAGNOSTIC = id("00000000-0000-0000-5000-000000000003");

  public static final UUID ALICE = id("00000000-0000-0000-c000-00000000a11c");
  public static final UUID ALICE_SUB = id("00000000-0000-0000-5b00-00000000a11c");
  public static final UUID ALICE_FOCUS = id("00000000-0000-0000-e000-00000000a11c");
  public static final UUID BOB = id("00000000-0000-0000-c000-0000000000b0");
  public static final UUID BOB_SUB = id("00000000-0000-0000-5b00-0000000000b0");
  public static final UUID BOB_GOLF = id("00000000-0000-0000-e000-0000000000b0");

  /** Customers that survive the per-test cleanup. */
  public static final List<UUID> SEED_CUSTOMERS = List.of(ALICE, BOB);

  /** Vehicles that survive the per-test cleanup. */
  public static final List<UUID> SEED_VEHICLES = List.of(ALICE_FOCUS, BOB_GOLF);

  private Fixtures() {}

  private static UUID id(String value) {
    return UUID.fromString(value);
  }
}
