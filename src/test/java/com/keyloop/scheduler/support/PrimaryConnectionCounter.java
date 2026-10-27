package com.keyloop.scheduler.support;

import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test configuration that counts connections borrowed from the primary pool, to prove that a code
 * path never reaches the primary database. The pool bean is wrapped in a class-based proxy, so it
 * is still injectable as a {@code HikariDataSource}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PrimaryConnectionCounter {

  /** Number of {@code getConnection} calls on the primary pool so far. */
  public static final AtomicInteger BORROWED = new AtomicInteger();

  @Bean
  static BeanPostProcessor countPrimaryConnections() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (!"primaryDataSource".equals(name)) {
          return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            (MethodInterceptor)
                invocation -> {
                  if ("getConnection".equals(invocation.getMethod().getName())) {
                    BORROWED.incrementAndGet();
                  }
                  return invocation.proceed();
                });
        return proxy.getProxy();
      }
    };
  }
}
