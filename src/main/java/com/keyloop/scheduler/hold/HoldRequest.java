package com.keyloop.scheduler.hold;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code POST /me/holds} body. */
public record HoldRequest(
    @NotNull UUID dealershipId,
    @NotNull UUID vehicleId,
    @NotNull List<UUID> serviceTypeIds,
    @NotNull OffsetDateTime start) {}
