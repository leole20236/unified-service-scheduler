package com.keyloop.scheduler.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.common.api.PreconditionRequiredException;
import com.keyloop.scheduler.common.api.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IfMatchTest {

  @Test
  void strongEtagYieldsTheVersion() {
    assertThat(IfMatch.require("\"7\"")).isEqualTo(7);
    assertThat(IfMatch.optional(" \"0\" ")).hasValue(0);
  }

  @Test
  void missingIsPreconditionRequiredWhenRequired() {
    assertThatThrownBy(() -> IfMatch.require(null))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> IfMatch.require("  "))
        .isInstanceOf(PreconditionRequiredException.class);
  }

  @Test
  void missingIsEmptyWhenOptional() {
    assertThat(IfMatch.optional(null)).isEmpty();
    assertThat(IfMatch.optional("")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "7",
        "W/\"7\"",
        "\"abc\"",
        "\"\"",
        "\"-1\"",
        "\"7",
        "*",
        "\"1234567890123456789\""
      })
  void malformedIsValidationError(String header) {
    assertThatThrownBy(() -> IfMatch.require(header)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> IfMatch.optional(header)).isInstanceOf(ValidationException.class);
  }
}
