package com.keyloop.scheduler.booking.create;

/** A response stored under an idempotency key: HTTP status and JSON body. */
public record StoredResponse(int status, String body) {}
