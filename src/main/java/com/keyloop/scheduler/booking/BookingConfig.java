package com.keyloop.scheduler.booking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Booking transactions and the default (no-op) after-commit listener. */
@Configuration(proxyBeanMethods = false)
class BookingConfig {

  /** One READ COMMITTED read-write transaction per allocation attempt (spec 8.1, 8.3). */
  @Bean
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
