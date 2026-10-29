package com.keyloop.scheduler.booking.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.booking.event.BookingListener;
import com.keyloop.scheduler.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring of the booking transactions, listener and advisory locks. */
class BookingInfrastructureIT extends IntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired ResourceLocks locks;
  @Autowired TransactionOperations defaultTransactions;

  @Test
  void bootsDefaultTransactionTemplateIsKeptBesideTheBookingOne() {
    assertThat(context.containsBean("transactionTemplate")).isTrue();
    TransactionTemplate booking = context.getBean("bookingTransactions", TransactionTemplate.class);
    assertThat(booking.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(((TransactionTemplate) defaultTransactions).getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  @Test
  void exactlyOneBookingListener() {
    assertThat(context.getBeansOfType(BookingListener.class)).hasSize(1);
  }

  @Test
  void resourceLocksRequireTheCallersTransaction() {
    assertThatThrownBy(() -> locks.lockAll(List.of(UUID.randomUUID())))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void resourceLocksAreHeldUntilTheTransactionEnds() {
    UUID resource = UUID.randomUUID();
    Long held =
        defaultTransactions.execute(
            status -> {
              locks.lockAll(List.of(resource, resource));
              return jdbc.queryForObject(
                  "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
                      + " AND pid = pg_backend_pid()",
                  Long.class);
            });
    assertThat(held).isEqualTo(1L);
  }
}
