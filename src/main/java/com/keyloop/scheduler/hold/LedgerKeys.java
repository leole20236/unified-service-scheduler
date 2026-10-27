package com.keyloop.scheduler.hold;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Redis key names of one dealership-day's slot ledger (spec 8.6). Every key shares the hash tag
 * {@code {d:<dealershipId>:<localDate>}}, so they live in one Redis Cluster slot and one Lua script
 * may touch them all atomically. {@code holdref:<holdId>} deliberately has no tag: it is how a hold
 * id alone finds its dealership-day.
 */
public record LedgerKeys(UUID dealershipId, LocalDate date) {

  /** Keys of one dealership-day. */
  public static LedgerKeys of(UUID dealershipId, LocalDate date) {
    return new LedgerKeys(dealershipId, date);
  }

  /** The hash tag, braces included; Lua scripts build key names from it. */
  public String tag() {
    return "{d:" + dealershipId + ":" + date + "}";
  }

  /** Sorted set of intervals on one bay or technician. */
  public String ledger(UUID resourceId) {
    return "ledger:" + tag() + ":" + resourceId;
  }

  /** Hash describing one hold. */
  public String hold(UUID holdId) {
    return "hold:" + tag() + ":" + holdId;
  }

  /** The user's current hold id on this dealership-day. */
  public String userHold(UUID userId) {
    return "userhold:" + tag() + ":" + userId;
  }

  /** Marker: present while the ledger mirrors the primary; missing means rebuild before use. */
  public String loaded() {
    return "ledger-loaded:" + tag();
  }

  /**
   * Write counter: bumped by every write-through and by {@link SlotLedger#markDirty}, so a rebuild
   * whose database snapshot is older than a concurrent write never sets the marker.
   */
  public String generation() {
    return "ledger-gen:" + tag();
  }

  /** {@code holdref:<holdId>} (no hash tag). */
  public static String holdRef(UUID holdId) {
    return "holdref:" + holdId;
  }

  /** The value stored under {@link #holdRef}: {@code <dealershipId>:<localDate>}. */
  public String holdRefValue() {
    return dealershipId + ":" + date;
  }

  /** Parses a {@link #holdRefValue}. */
  public static SlotLedger.HoldLocation parseHoldRef(String value) {
    int colon = value.indexOf(':');
    return new SlotLedger.HoldLocation(
        UUID.fromString(value.substring(0, colon)), LocalDate.parse(value.substring(colon + 1)));
  }
}
