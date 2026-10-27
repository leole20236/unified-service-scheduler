package com.keyloop.scheduler.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the request id into the logging MDC and echoes it as {@code X-Request-Id}. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcFilter extends OncePerRequestFilter {

  private static final String REQUEST_ID = "requestId";

  /** The OTel trace id when present, else the request id, else a fresh id. */
  public static String currentTraceId() {
    String traceId = MDC.get("traceId");
    if (traceId != null && !traceId.isBlank()) {
      return traceId;
    }
    String requestId = MDC.get(REQUEST_ID);
    return requestId != null ? requestId : UUID.randomUUID().toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = request.getHeader("X-Request-Id");
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    MDC.put(REQUEST_ID, requestId);
    response.setHeader("X-Request-Id", requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID);
      MDC.remove("userId");
    }
  }
}
