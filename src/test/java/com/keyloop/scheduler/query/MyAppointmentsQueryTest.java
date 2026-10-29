package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.booking.domain.AppointmentStatus;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.query.CursorCodec.Position;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MyAppointmentsQueryTest {

  private final AppointmentReadRepository reads = mock(AppointmentReadRepository.class);
  private final MyAppointmentsQuery query = new MyAppointmentsQuery(reads);

  private static AppointmentView view(int minute) {
    return new AppointmentView(
        UUID.randomUUID(),
        "BOOKED",
        0,
        new AppointmentView.Dealership(Fixtures.SOLO, "Solo", "Europe/London"),
        new AppointmentView.Vehicle(Fixtures.ALICE_FOCUS, "R", "Ford", "Focus"),
        List.of(),
        Instant.parse("2026-11-04T09:00:00Z").minusSeconds(60L * minute),
        Instant.parse("2026-11-04T10:00:00Z"),
        new AppointmentView.ServiceBay(Fixtures.SOLO_BAY, "Bay"),
        new AppointmentView.Technician(Fixtures.SOLO_TECH, "Solo"));
  }

  private Page<AppointmentView> list(Optional<String> cursor, int limit) {
    return query.list(
        Fixtures.ALICE,
        Optional.of(AppointmentStatus.BOOKED),
        Optional.empty(),
        Optional.empty(),
        cursor,
        limit);
  }

  @Test
  void extraRowMeansNextCursorOfLastItemShown() {
    List<AppointmentView> rows = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      rows.add(view(i));
    }
    when(reads.page(eq(Fixtures.ALICE), any(), any(), any(), eq(Optional.empty()), eq(4)))
        .thenReturn(rows);

    Page<AppointmentView> page = list(Optional.empty(), 3);

    assertThat(page.items()).containsExactlyElementsOf(rows.subList(0, 3));
    assertThat(CursorCodec.decode(page.nextCursor()))
        .isEqualTo(new Position(rows.get(2).start(), rows.get(2).id()));
  }

  @Test
  void lastPageHasNoCursor() {
    when(reads.page(any(), any(), any(), any(), any(), eq(21))).thenReturn(List.of(view(0)));

    Page<AppointmentView> page = list(Optional.empty(), 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void cursorIsDecodedForTheRepository() {
    Position after = new Position(Instant.parse("2026-11-04T09:00:00Z"), UUID.randomUUID());
    when(reads.page(any(), any(), any(), any(), any(), eq(11))).thenReturn(List.of());

    list(Optional.of(CursorCodec.encode(after)), 10);

    verify(reads).page(any(), any(), any(), any(), eq(Optional.of(after)), eq(11));
  }

  @Test
  void badCursorAndBadLimitsAreValidationErrors() {
    assertThatThrownBy(() -> list(Optional.of("garbage!!"), 10))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> list(Optional.empty(), 101)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> list(Optional.empty(), 0)).isInstanceOf(ValidationException.class);
  }

  @Test
  void getReturnsOwnedOrNotFound() {
    AppointmentView mine = view(0);
    when(reads.findOwned(Fixtures.ALICE, mine.id())).thenReturn(Optional.of(mine));

    assertThat(query.get(Fixtures.ALICE, mine.id())).isEqualTo(mine);
    assertThatThrownBy(() -> query.get(Fixtures.BOB, mine.id()))
        .isInstanceOf(NotFoundException.class);
  }
}
