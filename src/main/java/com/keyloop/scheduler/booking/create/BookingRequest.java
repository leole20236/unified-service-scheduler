package com.keyloop.scheduler.booking.create;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A booking command. Customers send {@code dealershipId} in the body and never name a customer or
 * resources; dispatchers take the dealership from the path, name {@code customerId} and may pin a
 * bay and/or technician.
 */
public record BookingRequest(
    UUID dealershipId,
    UUID customerId,
    @NotNull UUID vehicleId,
    @NotNull List<UUID> serviceTypeIds,
    @NotNull OffsetDateTime start,
    @Size(max = 500) String customerNotes,
    UUID serviceBayId,
    UUID technicianId,
    UUID holdId,
    boolean overrideHold) {}
