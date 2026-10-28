package com.keyloop.scheduler.hold;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /me/holds} body. {@code rescheduleOf} (optional) names the caller's own appointment
 * the hold is for: its current slot then does not count as taken.
 */
public record HoldRequest(
    @NotNull UUID dealershipId,
    @NotNull UUID vehicleId,
    @NotNull List<UUID> serviceTypeIds,
    @NotNull OffsetDateTime start,
    UUID rescheduleOf) {}
