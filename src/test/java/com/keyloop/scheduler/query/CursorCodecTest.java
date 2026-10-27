package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.query.CursorCodec.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorCodecTest {

  private static final Position POSITION =
      new Position(Instant.parse("2026-11-04T09:00:00.123456Z"), UUID.randomUUID());

  @Test
  void roundTripKeepsMicrosecondsAndId() {
    String cursor = CursorCodec.encode(POSITION);

    assertThat(CursorCodec.decode(cursor)).isEqualTo(POSITION);
  }

  @Test
  void cursorIsOpaqueUrlSafeBase64() {
    String cursor = CursorCodec.encode(POSITION);

    assertThat(cursor).matches("[A-Za-z0-9_-]+").doesNotContain(POSITION.id().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"garbage!!", "", "AAAA", "%%%"})
  void garbageIsValidationError(String cursor) {
    assertThatThrownBy(() -> CursorCodec.decode(cursor))
        .isInstanceOfSatisfying(
            ValidationException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.VALIDATION_ERROR));
  }

  @Test
  void tamperedPartsAreRejected() {
    for (String raw :
        new String[] {
          "2026-11-04T09:00:00Z",
          "not-a-time|" + UUID.randomUUID(),
          "2026-11-04T09:00:00Z|not-a-uuid"
        }) {
      String cursor =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> CursorCodec.decode(cursor)).isInstanceOf(ValidationException.class);
    }
  }
}
