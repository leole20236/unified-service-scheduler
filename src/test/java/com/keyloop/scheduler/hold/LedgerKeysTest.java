package com.keyloop.scheduler.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LedgerKeysTest {

  private static final UUID DEALERSHIP = UUID.fromString("00000000-0000-0000-d000-000000000001");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-b000-000000000011");
  private static final UUID HOLD = UUID.fromString("0192f0b9-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("00000000-0000-0000-5b00-00000000a11c");
  private static final Pattern TAG = Pattern.compile("\\{([^}]*)}");

  private static String tagOf(String key) {
    Matcher m = TAG.matcher(key);
    assertThat(m.find()).as(key).isTrue();
    return m.group(1);
  }

  @Test
  void everyKeyOfOneDealershipDaySharesTheHashTag() {
    LedgerKeys keys = LedgerKeys.of(DEALERSHIP, DAY);
    List<String> all =
        List.of(
            keys.ledger(RESOURCE),
            keys.hold(HOLD),
            keys.userHold(USER),
            keys.loaded(),
            keys.generation());

    assertThat(all).extracting(LedgerKeysTest::tagOf).containsOnly("d:" + DEALERSHIP + ":" + DAY);
    assertThat(keys.tag()).isEqualTo("{d:" + DEALERSHIP + ":2026-11-02}");
  }

  @Test
  void keysFollowTheSpecTable() {
    LedgerKeys keys = LedgerKeys.of(DEALERSHIP, DAY);
    String tag = "{d:" + DEALERSHIP + ":2026-11-02}";

    assertThat(keys.ledger(RESOURCE)).isEqualTo("ledger:" + tag + ":" + RESOURCE);
    assertThat(keys.hold(HOLD)).isEqualTo("hold:" + tag + ":" + HOLD);
    assertThat(keys.userHold(USER)).isEqualTo("userhold:" + tag + ":" + USER);
    assertThat(keys.loaded()).isEqualTo("ledger-loaded:" + tag);
  }

  @Test
  void otherDaysAndDealershipsUseOtherTags() {
    assertThat(LedgerKeys.of(DEALERSHIP, DAY).tag())
        .isNotEqualTo(LedgerKeys.of(DEALERSHIP, DAY.plusDays(1)).tag())
        .isNotEqualTo(LedgerKeys.of(UUID.randomUUID(), DAY).tag());
  }

  @Test
  void holdRefHasNoHashTag() {
    assertThat(LedgerKeys.holdRef(HOLD)).isEqualTo("holdref:" + HOLD).doesNotContain("{");
  }

  @Test
  void holdRefValueRoundTrips() {
    String value = LedgerKeys.of(DEALERSHIP, DAY).holdRefValue();

    assertThat(value).isEqualTo(DEALERSHIP + ":2026-11-02");
    assertThat(LedgerKeys.parseHoldRef(value))
        .isEqualTo(new SlotLedger.HoldLocation(DEALERSHIP, DAY));
  }
}
