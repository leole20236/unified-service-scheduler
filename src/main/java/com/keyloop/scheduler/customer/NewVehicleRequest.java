package com.keyloop.scheduler.customer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of the add-vehicle endpoints. */
public record NewVehicleRequest(
    @NotNull @Pattern(regexp = "[A-HJ-NPR-Z0-9]{17}", message = "must be a 17-character VIN")
        String vin,
    @Size(max = 15) String registration,
    @Size(max = 60) String make,
    @Size(max = 60) String model,
    @Min(1950) @Max(2100) Integer modelYear,
    @Min(0) Integer mileage) {}
