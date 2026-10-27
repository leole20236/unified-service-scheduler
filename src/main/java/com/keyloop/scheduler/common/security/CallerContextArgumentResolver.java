package com.keyloop.scheduler.common.security;

import com.keyloop.scheduler.common.api.UnauthenticatedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link CallerContext} set by {@link IdentityHeaderFilter} into controllers. */
public class CallerContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return CallerContext.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object caller =
        webRequest.getAttribute(CallerContext.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (caller == null) {
      throw new UnauthenticatedException("Missing or invalid identity headers.");
    }
    return caller;
  }
}
