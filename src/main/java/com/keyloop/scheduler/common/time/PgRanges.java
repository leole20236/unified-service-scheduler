package com.keyloop.scheduler.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.postgresql.util.PGobject;

/** Converts {@link TimeRange} to and from PostgreSQL {@code tstzrange} text. */
public final class PgRanges {

  private PgRanges() {}

  /** Half-open literal in UTC, e.g. {@code [2026-11-02T09:00:00Z,2026-11-02T11:00:00Z)}. */
  public static String toLiteral(TimeRange range) {
    return "[" + range.start() + "," + range.end() + ")";
  }

  /** Parses the value of a {@code tstzrange} column. */
  public static TimeRange parse(PGobject object) {
    return parse(object == null ? null : object.getValue());
  }

  /** Parses {@code tstzrange} text, with or without quoted database-style timestamps. */
  public static TimeRange parse(String text) {
    if (text == null || text.length() < 5 || text.charAt(0) != '[' || !text.endsWith(")")) {
      throw new IllegalArgumentException("Not a half-open tstzrange: " + text);
    }
    String[] parts = text.substring(1, text.length() - 1).split(",", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("Not a tstzrange: " + text);
    }
    return new TimeRange(instant(parts[0]), instant(parts[1]));
  }

  private static Instant instant(String raw) {
    String value = raw.replace("\"", "").trim().replace(' ', 'T');
    if (value.matches(".*[+-]\\d{2}$")) {
      value = value + ":00";
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("Bad timestamp in range: " + raw, e);
    }
  }
}
