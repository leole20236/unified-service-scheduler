package com.keyloop.scheduler.booking.domain;

import java.util.UUID;

/** A candidate bay and technician for one job. */
public record ResourcePair(UUID bayId, UUID technicianId) {}
