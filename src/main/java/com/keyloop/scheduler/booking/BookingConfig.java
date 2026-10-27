package com.keyloop.scheduler.booking;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Booking transactions. */
@Configuration(proxyBeanMethods = false)
class BookingConfig {

  /** Qualifier of {@link #bookingTransactions}. */
  static final String BOOKING_TRANSACTIONS = "bookingTransactions";

  /**
   * One READ COMMITTED read-write transaction per allocation attempt (spec 8.1, 8.3). Not a default
   * candidate, so it is injected only where asked for by qualifier and Spring Boot's own {@code
   * transactionTemplate} (REQUIRED) still exists for everyone else.
   */
  @Bean(defaultCandidate = false)
  @Qualifier(BOOKING_TRANSACTIONS)
  TransactionTemplate bookingTransactions(PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setReadOnly(false);
    return template;
  }

  /** Does nothing until the slot ledger provides a real listener. */
  @Bean
  BookingListener noOpBookingListener() {
    return event -> {};
  }
}
