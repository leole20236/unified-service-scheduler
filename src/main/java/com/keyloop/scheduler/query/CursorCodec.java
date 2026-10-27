package com.keyloop.scheduler.query;

import com.keyloop.scheduler.common.api.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque pagination cursor: base64url of {@code <start_at>|<id>}, the sort key of the last item of
 * a page. Anything that does not decode to exactly that is a 400 VALIDATION_ERROR.
 */
public final class CursorCodec {

  /** Where the next page starts after: the last item's start and id. */
  public record Position(Instant startAt, UUID id) {}

  private static final char SEPARATOR = '|';

  private CursorCodec() {}

  /** Encodes a position. */
  public static String encode(Position position) {
    String raw = position.startAt() + String.valueOf(SEPARATOR) + position.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Decodes a cursor; tampered or garbage input throws {@link ValidationException}. */
  public static Position decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int at = raw.indexOf(SEPARATOR);
      if (at < 0) {
        throw invalid();
      }
      return new Position(
          Instant.parse(raw.substring(0, at)), UUID.fromString(raw.substring(at + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw invalid();
    }
  }

  private static ValidationException invalid() {
    return new ValidationException(
        "1 field is invalid.", List.of(Map.of("field", "cursor", "message", "is not valid")));
  }
}
