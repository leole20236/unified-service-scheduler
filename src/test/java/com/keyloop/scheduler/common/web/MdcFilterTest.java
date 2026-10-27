package com.keyloop.scheduler.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MdcFilterTest {

  private final MdcFilter filter = new MdcFilter();

  @Test
  void propagatesSuppliedRequestIdAndClearsMdc() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    request.addHeader("X-Request-Id", "req-123");
    MockHttpServletResponse response = new MockHttpServletResponse();
    List<String> seen = new ArrayList<>();
    FilterChain chain =
        (req, res) -> {
          seen.add(MDC.get("requestId"));
          seen.add(MdcFilter.currentTraceId());
        };

    filter.doFilter(request, response, chain);

    assertThat(seen).containsExactly("req-123", "req-123");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("userId")).isNull();
  }

  @Test
  void generatesRequestIdWhenAbsentOrBlank() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    request.addHeader("X-Request-Id", " ");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader("X-Request-Id")).isNotBlank().isNotEqualTo(" ");
  }

  @Test
  void traceIdPrefersMdcTraceId() {
    MDC.put("traceId", "otel-trace");
    MDC.put("requestId", "req");
    try {
      assertThat(MdcFilter.currentTraceId()).isEqualTo("otel-trace");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void traceIdFallsBackToGeneratedValueOutsideRequest() {
    assertThat(MdcFilter.currentTraceId()).isNotBlank();
  }

  @Test
  void clearsMdcWhenChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    MDC.put("userId", "u");

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
