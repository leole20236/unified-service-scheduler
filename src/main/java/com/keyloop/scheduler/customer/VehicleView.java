package com.keyloop.scheduler.customer;

import java.util.UUID;

/** A vehicle as exposed by the API. */
public record VehicleView(
    UUID id,
    String vin,
    String registration,
    String make,
    String model,
    Integer modelYear,
    Integer mileage) {}
