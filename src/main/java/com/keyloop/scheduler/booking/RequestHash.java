package com.keyloop.scheduler.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SHA-256 of the canonical JSON of a booking request plus its route: keys sorted, times as UTC
 * instants, so the same request always hashes the same and the same key on another endpoint does
 * not.
 */
public final class RequestHash {

  private static final ObjectMapper JSON = new ObjectMapper();

  private RequestHash() {}

  /** Lowercase hex SHA-256 of the canonical request. */
  public static String of(BookingChannel channel, BookingRequest r) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("route", channel.route());
    canonical.put("dealershipId", text(r.dealershipId()));
    canonical.put("customerId", text(r.customerId()));
    canonical.put("vehicleId", text(r.vehicleId()));
    canonical.put("serviceTypeIds", r.serviceTypeIds().stream().map(RequestHash::text).toList());
    canonical.put("start", r.start().toInstant().toString());
    canonical.put("customerNotes", r.customerNotes());
    canonical.put("serviceBayId", text(r.serviceBayId()));
    canonical.put("technicianId", text(r.technicianId()));
    canonical.put("holdId", text(r.holdId()));
    canonical.put("overrideHold", r.overrideHold());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical));
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot hash request", e);
    }
  }

  private static String text(UUID id) {
    return Objects.toString(id, null);
  }
}
