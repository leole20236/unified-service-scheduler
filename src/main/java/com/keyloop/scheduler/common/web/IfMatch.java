package com.keyloop.scheduler.common.web;

import com.keyloop.scheduler.common.api.PreconditionRequiredException;
import com.keyloop.scheduler.common.api.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Parses the {@code If-Match} request header: a strong ETag holding the appointment version. */
public final class IfMatch {

  private IfMatch() {}

  /** The expected version; a missing header is 428, a malformed one 400. */
  public static long require(String header) {
    if (header == null || header.isBlank()) {
      throw new PreconditionRequiredException(
          "This change needs an If-Match header with the ETag of the appointment you last read.");
    }
    return parse(header);
  }

  /** The expected version if the header is present; a malformed one is 400. */
  public static OptionalLong optional(String header) {
    return header == null || header.isBlank()
        ? OptionalLong.empty()
        : OptionalLong.of(parse(header));
  }

  private static long parse(String header) {
    String value = header.trim();
    if (value.length() >= 3 && value.startsWith("\"") && value.endsWith("\"")) {
      String digits = value.substring(1, value.length() - 1);
      if (digits.chars().allMatch(c -> c >= '0' && c <= '9') && digits.length() <= 18) {
        return Long.parseLong(digits);
      }
    }
    throw new ValidationException(
        "1 field is invalid.",
        List.of(Map.of("field", "If-Match", "message", "must be a strong ETag such as \"3\"")));
  }
}
