package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DealershipAppointmentQueryTest {

  private final AppointmentReadRepository reads = mock(AppointmentReadRepository.class);
  private final DealershipAppointmentQuery query = new DealershipAppointmentQuery(reads);

  @Test
  void onlyTheDealershipsOwnAppointmentIsFound() {
    UUID id = UUID.randomUUID();
    AppointmentView view =
        new AppointmentView(
            id,
            "BOOKED",
            3,
            new AppointmentView.Dealership(Fixtures.SOLO, "Solo", "Europe/London"),
            null,
            List.of(),
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(60),
            null,
            null);
    when(reads.find(id)).thenReturn(Optional.of(view));

    assertThat(query.get(Fixtures.SOLO, id)).isSameAs(view);
    assertThatThrownBy(() -> query.get(Fixtures.RIVERSIDE, id))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> query.get(Fixtures.SOLO, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }
}
