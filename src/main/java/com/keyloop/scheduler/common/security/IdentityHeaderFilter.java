package com.keyloop.scheduler.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.web.MdcFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Parses the gateway identity headers into a {@link CallerContext}; missing or malformed values
 * answer 401 (spec 11.2).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdentityHeaderFilter extends OncePerRequestFilter {

  private static final String DETAIL = "Missing or invalid identity headers.";

  private final ObjectMapper objectMapper;

  /** Creates the problem with its human-readable detail. */
  public IdentityHeaderFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/actuator")
        || path.startsWith("/v3/api-docs")
        || path.startsWith("/swagger-ui");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CallerContext caller;
    try {
      caller = parse(request);
    } catch (IllegalArgumentException e) {
      reject(request, response);
      return;
    }
    request.setAttribute(CallerContext.REQUEST_ATTRIBUTE, caller);
    MDC.put("userId", caller.userId().toString());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("userId");
    }
  }

  private static CallerContext parse(HttpServletRequest request) {
    String userId = request.getHeader("X-User-Id");
    String roles = request.getHeader("X-User-Roles");
    if (userId == null || roles == null) {
      throw new IllegalArgumentException("missing identity header");
    }
    Set<Role> parsedRoles = EnumSet.noneOf(Role.class);
    for (String role : split(roles)) {
      parsedRoles.add(Role.valueOf(role));
    }
    if (parsedRoles.isEmpty()) {
      throw new IllegalArgumentException("no roles");
    }
    String dealerships = request.getHeader("X-Dealership-Ids");
    Set<UUID> dealershipIds =
        dealerships == null
            ? Set.of()
            : split(dealerships).stream().map(UUID::fromString).collect(Collectors.toSet());
    return new CallerContext(
        UUID.fromString(userId.trim()), Set.copyOf(parsedRoles), dealershipIds);
  }

  private static java.util.List<String> split(String value) {
    return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    ProblemCodes code = ProblemCodes.UNAUTHENTICATED;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", code.type());
    body.put("title", code.title());
    body.put("status", code.status());
    body.put("detail", DETAIL);
    body.put("instance", request.getRequestURI());
    body.put("code", code.name());
    body.put("traceId", MdcFilter.currentTraceId());
    response.setStatus(code.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
