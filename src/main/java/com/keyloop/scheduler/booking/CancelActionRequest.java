package com.keyloop.scheduler.booking;

import jakarta.validation.constraints.Size;

/** Body of the dispatcher's {@code POST …/{id}/cancel}: an optional reason for the record. */
public record CancelActionRequest(@Size(max = 200) String reason) {}
