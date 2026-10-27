package com.keyloop.scheduler.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("GET", "/api/v1/me/appointments");

  private record Case(ApiException ex, int status, String code, String title) {}

  private void assertProblem(
      ResponseEntity<ProblemDetail> response, int status, String code, String title) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail body = response.getBody();
    assertThat(body.getStatus()).isEqualTo(status);
    assertThat(body.getTitle()).isEqualTo(title);
    assertThat(body.getInstance().toString()).isEqualTo("/api/v1/me/appointments");
    assertThat(body.getProperties()).containsEntry("code", code);
    assertThat(body.getProperties().get("traceId")).asString().isNotBlank();
  }

  @Test
  void simpleApiExceptionsMapToSpecStatusCodeAndTitle() {
    List<Case> cases =
        List.of(
            new Case(
                new NotFoundException("Appointment not found."),
                404,
                "NOT_FOUND",
                "Resource not found"),
            new Case(new ForbiddenException("no"), 403, "FORBIDDEN", "Not allowed"),
            new Case(
                new UnauthenticatedException("no"),
                401,
                "UNAUTHENTICATED",
                "Authentication required"),
            new Case(new SlotHeldException("held"), 409, "SLOT_HELD", "Time is held by a customer"),
            new Case(
                new VersionConflictException("v"),
                412,
                "VERSION_CONFLICT",
                "Appointment was changed by someone else"),
            new Case(
                new InvalidTransitionException("t"),
                409,
                "INVALID_TRANSITION",
                "Action not allowed in current status"),
            new Case(
                new PreconditionRequiredException("p"),
                428,
                "PRECONDITION_REQUIRED",
                "Precondition header required"),
            new Case(
                new BusinessRuleException("OUTSIDE_BOOKING_WINDOW", "d"),
                422,
                "OUTSIDE_BOOKING_WINDOW",
                "Time is outside the booking window"),
            new Case(
                new BusinessRuleException("HOLD_MISMATCH", "d"),
                422,
                "HOLD_MISMATCH",
                "Hold does not match the request"));
    for (Case c : cases) {
      ResponseEntity<ProblemDetail> response = handler.handleApi(c.ex(), request);
      assertProblem(response, c.status(), c.code(), c.title());
      assertThat(response.getBody().getDetail()).isEqualTo(c.ex().getMessage());
      assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
    }
  }

  @Test
  void slotUnavailableCarriesAlternativesAndConflicts() {
    List<OffsetDateTime> alternatives = List.of(OffsetDateTime.parse("2026-11-02T09:15:00Z"));
    List<UUID> conflicts = List.of(UUID.randomUUID());
    ResponseEntity<ProblemDetail> response =
        handler.handleApi(
            new SlotUnavailableException("none free", alternatives, conflicts), request);

    assertProblem(response, 409, "SLOT_UNAVAILABLE", "Requested time is not available");
    assertThat(response.getBody().getProperties())
        .containsEntry("alternatives", alternatives)
        .containsEntry("conflicts", conflicts);
  }

  @Test
  void validationExceptionCarriesFieldErrors() {
    ValidationException ex =
        new ValidationException(
            "1 field is invalid.", List.of(Map.of("field", "vin", "message", "bad")));
    ResponseEntity<ProblemDetail> response = handler.handleApi(ex, request);
    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getProperties())
        .containsEntry("errors", List.of(Map.of("field", "vin", "message", "bad")));
  }

  @Test
  void retryAfterHeaders() {
    ResponseEntity<ProblemDetail> inProgress =
        handler.handleApi(new RequestInProgressException("busy"), request);
    assertProblem(inProgress, 409, "REQUEST_IN_PROGRESS", "Duplicate request in progress");
    assertThat(inProgress.getHeaders().getFirst("Retry-After")).isEqualTo("1");

    ResponseEntity<ProblemDetail> holds =
        handler.handleApi(new HoldsUnavailableException("down"), request);
    assertProblem(holds, 503, "HOLDS_UNAVAILABLE", "Slot holds temporarily unavailable");
    assertThat(holds.getHeaders().getFirst("Retry-After")).isEqualTo("30");
  }

  static void target(String value) {}

  @Test
  void methodArgumentNotValidListsFields() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
    binding.addError(new FieldError("body", "vin", "must be 17 characters"));
    binding.addError(new FieldError("body", "plate", "must not be blank"));
    Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("target", String.class);
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

    ResponseEntity<ProblemDetail> response = handler.handleMethodArgumentNotValid(ex, request);

    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getDetail()).isEqualTo("2 fields are invalid.");
    assertThat(response.getBody().getProperties().get("errors"))
        .isEqualTo(
            List.of(
                Map.of("field", "vin", "message", "must be 17 characters"),
                Map.of("field", "plate", "message", "must not be blank")));
  }

  @Test
  void singleViolationUsesSingularDetail() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
    binding.addError(new FieldError("body", "vin", "bad"));
    Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("target", String.class);
    ResponseEntity<ProblemDetail> response =
        handler.handleMethodArgumentNotValid(
            new MethodArgumentNotValidException(new MethodParameter(method, 0), binding), request);
    assertThat(response.getBody().getDetail()).isEqualTo("1 field is invalid.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void constraintViolationUsesLeafPropertyName() {
    ConstraintViolation<Object> violation = Mockito.mock(ConstraintViolation.class);
    Path path = Mockito.mock(Path.class);
    Mockito.when(path.toString()).thenReturn("list.limit");
    Mockito.when(violation.getPropertyPath()).thenReturn(path);
    Mockito.when(violation.getMessage()).thenReturn("must be at most 100");

    ResponseEntity<ProblemDetail> response =
        handler.handleConstraintViolation(
            new ConstraintViolationException(Set.of(violation)), request);

    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getProperties().get("errors"))
        .isEqualTo(List.of(Map.of("field", "limit", "message", "must be at most 100")));
  }

  @Test
  void typeMismatchNamesParameter() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException("abc", UUID.class, "dealershipId", null, null);
    ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex, request);
    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getProperties().get("errors"))
        .isEqualTo(List.of(Map.of("field", "dealershipId", "message", "has an invalid value")));
  }

  @Test
  void missingParameterNamesParameter() {
    ResponseEntity<ProblemDetail> response =
        handler.handleMissingParameter(
            new MissingServletRequestParameterException("from", "String"), request);
    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getProperties().get("errors"))
        .isEqualTo(List.of(Map.of("field", "from", "message", "is required")));
  }

  @Test
  void unreadableBodyIsBadRequest() {
    ResponseEntity<ProblemDetail> response =
        handler.handleNotReadable(
            new HttpMessageNotReadableException("bad json", Mockito.mock(HttpInputMessage.class)),
            request);
    assertProblem(response, 400, "VALIDATION_ERROR", "Request is invalid");
    assertThat(response.getBody().getDetail()).isEqualTo("Request body is missing or malformed.");
  }

  @Test
  void springWebExceptionsAreMapped() {
    assertProblem(
        handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE", List.of("GET")), request),
        405,
        "METHOD_NOT_ALLOWED",
        "Method not allowed");
    assertProblem(
        handler.handleNotAcceptable(new HttpMediaTypeNotAcceptableException("no"), request),
        406,
        "NOT_ACCEPTABLE",
        "Not acceptable");
    ResponseEntity<ProblemDetail> unsupported =
        handler.handleUnsupportedMediaType(
            new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON,
                List.of(MediaType.valueOf("application/merge-patch+json"))),
            request);
    assertProblem(unsupported, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");
    assertThat(unsupported.getBody().getDetail()).contains("application/merge-patch+json");
    assertProblem(
        handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/nope"), request),
        404,
        "NOT_FOUND",
        "Resource not found");
  }

  @Test
  void unexpectedExceptionIsInternalErrorWithoutLeakingDetails() {
    ResponseEntity<ProblemDetail> response =
        handler.handleUnexpected(new IllegalStateException("secret SQL select * from x"), request);
    assertProblem(response, 500, "INTERNAL_ERROR", "Unexpected error");
    assertThat(response.getBody().getDetail())
        .isEqualTo("Something went wrong. Quote the traceId when contacting support.");
    assertThat(response.getBody().getProperties()).doesNotContainKeys("trace", "exception");
  }

  @Test
  void problemCodesCoverSpecTable() {
    assertThat(ProblemCodes.values()).hasSize(28);
    assertThat(ProblemCodes.valueOf("RATE_LIMITED").title()).isEqualTo("Too many requests");
    assertThat(ProblemCodes.valueOf("SERVICE_UNAVAILABLE").status()).isEqualTo(503);
  }
}
