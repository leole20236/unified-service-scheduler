package com.keyloop.scheduler.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdentityHeaderFilterTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final IdentityHeaderFilter filter = new IdentityHeaderFilter(mapper);
  private final FilterChain chain = mock(FilterChain.class);
  private final UUID user = UUID.randomUUID();

  private MockHttpServletRequest request(String path) {
    return new MockHttpServletRequest("GET", path);
  }

  private void assertUnauthenticated(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(body.get("title").asText()).isEqualTo("Authentication required");
    assertThat(body.get("status").asInt()).isEqualTo(401);
    assertThat(body.get("traceId").asText()).isNotBlank();
    verifyNoInteractions(chain);
  }

  @Test
  void missingUserIdIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Roles", "CUSTOMER");
    assertUnauthenticated(request);
  }

  @Test
  void malformedUserIdIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Id", "not-a-uuid");
    request.addHeader("X-User-Roles", "CUSTOMER");
    assertUnauthenticated(request);
  }

  @Test
  void missingRolesIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Id", user.toString());
    assertUnauthenticated(request);
  }

  @Test
  void blankRolesIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Id", user.toString());
    request.addHeader("X-User-Roles", " , ");
    assertUnauthenticated(request);
  }

  @Test
  void unknownRoleIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Id", user.toString());
    request.addHeader("X-User-Roles", "CUSTOMER,ADMIN");
    assertUnauthenticated(request);
  }

  @Test
  void malformedDealershipIdIs401() throws Exception {
    MockHttpServletRequest request = request("/api/v1/dealerships/x/schedule");
    request.addHeader("X-User-Id", user.toString());
    request.addHeader("X-User-Roles", "DISPATCHER");
    request.addHeader("X-Dealership-Ids", "nope");
    assertUnauthenticated(request);
  }

  @Test
  void validCustomerSetsCallerContext() throws Exception {
    MockHttpServletRequest request = request("/api/v1/me/appointments");
    request.addHeader("X-User-Id", user.toString());
    request.addHeader("X-User-Roles", "CUSTOMER");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    CallerContext caller = (CallerContext) request.getAttribute(CallerContext.REQUEST_ATTRIBUTE);
    assertThat(caller.userId()).isEqualTo(user);
    assertThat(caller.roles()).containsExactly(Role.CUSTOMER);
    assertThat(caller.dealershipIds()).isEmpty();
    assertThat(MDC.get("userId")).isNull();
  }

  @Test
  void dispatcherParsesCommaSeparatedDealershipsWithSpaces() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    MockHttpServletRequest request = request("/api/v1/dealerships/" + a + "/schedule");
    request.addHeader("X-User-Id", user.toString());
    request.addHeader("X-User-Roles", "CUSTOMER , DISPATCHER");
    request.addHeader("X-Dealership-Ids", a + " , " + b);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    CallerContext caller = (CallerContext) request.getAttribute(CallerContext.REQUEST_ATTRIBUTE);
    assertThat(caller.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.DISPATCHER);
    assertThat(caller.dealershipIds()).isEqualTo(Set.of(a, b));
  }

  @Test
  void documentationAndActuatorPathsAreSkipped() throws Exception {
    for (String path :
        new String[] {"/actuator/health", "/v3/api-docs", "/swagger-ui/index.html"}) {
      FilterChain skipped = mock(FilterChain.class);
      MockHttpServletRequest request = request(path);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, skipped);
      verify(skipped).doFilter(request, response);
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }
}
