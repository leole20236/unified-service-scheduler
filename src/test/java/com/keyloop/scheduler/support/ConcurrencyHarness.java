package com.keyloop.scheduler.support;

import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Fires many requests at the same instant from a start gate (double-booking races). */
public final class ConcurrencyHarness {

  private static final long TIMEOUT_SECONDS = 30;

  private ConcurrencyHarness() {}

  /**
   * Runs {@code call} on {@code threads} threads (argument = thread index). Every thread first
   * blocks on one {@link CountDownLatch}; the gate opens once all are waiting, so the requests fire
   * together. Returns the responses in completion order; fails if any call throws or the whole race
   * takes longer than 30 seconds.
   */
  public static List<Response> race(int threads, IntFunction<Response> call) {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch gate = new CountDownLatch(1);
    Queue<Response> completed = new ConcurrentLinkedQueue<>();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  gate.await();
                  completed.add(call.apply(index));
                  return null;
                }));
      }
      if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("Threads did not reach the start gate in time");
      }
      gate.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      for (Future<?> future : futures) {
        future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      }
      return List.copyOf(completed);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Race interrupted", e);
    } catch (Exception e) {
      throw new AssertionError("Race failed: " + e, e);
    } finally {
      pool.shutdownNow();
    }
  }
}
