package com.keyloop.scheduler.common.web;

import com.keyloop.scheduler.common.security.CallerContextArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC wiring: the {@code CallerContext} argument resolver. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CallerContextArgumentResolver());
  }
}
