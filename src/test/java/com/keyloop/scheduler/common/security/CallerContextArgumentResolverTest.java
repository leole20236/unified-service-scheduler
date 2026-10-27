package com.keyloop.scheduler.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.common.api.UnauthenticatedException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class CallerContextArgumentResolverTest {

  private final CallerContextArgumentResolver resolver = new CallerContextArgumentResolver();

  @SuppressWarnings("unused")
  void sample(CallerContext caller, String other) {}

  private MethodParameter parameter(int index) throws Exception {
    return new MethodParameter(
        getClass().getDeclaredMethod("sample", CallerContext.class, String.class), index);
  }

  @Test
  void supportsOnlyCallerContext() throws Exception {
    assertThat(resolver.supportsParameter(parameter(0))).isTrue();
    assertThat(resolver.supportsParameter(parameter(1))).isFalse();
  }

  @Test
  void resolvesRequestAttribute() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    CallerContext caller = new CallerContext(UUID.randomUUID(), Set.of(Role.CUSTOMER), Set.of());
    request.setAttribute(CallerContext.REQUEST_ATTRIBUTE, caller);

    Object resolved =
        resolver.resolveArgument(parameter(0), null, new ServletWebRequest(request), null);

    assertThat(resolved).isSameAs(caller);
  }

  @Test
  void missingAttributeIsUnauthenticated() {
    assertThatThrownBy(
            () ->
                resolver.resolveArgument(
                    parameter(0), null, new ServletWebRequest(new MockHttpServletRequest()), null))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void callerContextHasRole() {
    CallerContext caller = new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of());
    assertThat(caller.has(Role.DISPATCHER)).isTrue();
    assertThat(caller.has(Role.CUSTOMER)).isFalse();
  }
}
