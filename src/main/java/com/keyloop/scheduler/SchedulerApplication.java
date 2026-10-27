package com.keyloop.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Unified Service Scheduler. */
@SpringBootApplication
public class SchedulerApplication {

  /** Starts the application. */
  public static void main(String[] args) {
    SpringApplication.run(SchedulerApplication.class, args);
  }
}
