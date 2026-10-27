package com.keyloop.scheduler.common.api;

import com.keyloop.scheduler.common.web.MdcFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every failure as {@code application/problem+json} with {@code code} and traceId. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Handles all domain failures. */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(ex.code(), ex.getMessage(), request);
    ex.extras().forEach(problem::setProperty);
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
    if (ex.retryAfterSeconds() != null) {
      builder.header("Retry-After", String.valueOf(ex.retryAfterSeconds()));
    }
    return respond(builder, problem);
  }

  /** Bean-validation failure on a request body. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                (FieldError e) ->
                    Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
            .toList();
    return validation(errors, request);
  }

  /** Bean-validation failure on a parameter. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream()
            .map(
                (ConstraintViolation<?> v) -> {
                  String path = v.getPropertyPath().toString();
                  return Map.of(
                      "field",
                      path.substring(path.lastIndexOf('.') + 1),
                      "message",
                      v.getMessage());
                })
            .toList();
    return validation(errors, request);
  }

  /** A path or query parameter of the wrong type. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return validation(
        List.of(Map.of("field", ex.getName(), "message", "has an invalid value")), request);
  }

  /** A required query parameter is absent. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ProblemDetail> handleMissingParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    return validation(
        List.of(Map.of("field", ex.getParameterName(), "message", "is required")), request);
  }

  /** A missing header: 428 for the precondition headers, otherwise 400. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ProblemDetail> handleMissingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    String header = ex.getHeaderName();
    if ("If-Match".equalsIgnoreCase(header) || "Idempotency-Key".equalsIgnoreCase(header)) {
      return respond(
          ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED),
          problem(
              ProblemCodes.PRECONDITION_REQUIRED,
              header + " header is required for this request.",
              request));
    }
    return validation(List.of(Map.of("field", header, "message", "header is required")), request);
  }

  /** Other binding failures (path variables, remaining request values). */
  @ExceptionHandler(ServletRequestBindingException.class)
  public ResponseEntity<ProblemDetail> handleBinding(
      ServletRequestBindingException ex, HttpServletRequest request) {
    String field =
        ex instanceof MissingPathVariableException missing ? missing.getVariableName() : "request";
    return validation(List.of(Map.of("field", field, "message", "is missing or invalid")), request);
  }

  /** Constraint violations on controller method parameters (Spring 6.1+). */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ProblemDetail> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result -> {
                  String name = result.getMethodParameter().getParameterName();
                  String field = name != null ? name : "parameter";
                  return result.getResolvableErrors().stream()
                      .map(
                          e ->
                              Map.of(
                                  "field",
                                  field,
                                  "message",
                                  String.valueOf(e.getDefaultMessage())));
                })
            .toList();
    return validation(errors, request);
  }

  /** The body is missing or not parseable. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(ProblemCodes.VALIDATION_ERROR, "Request body is missing or malformed.", request);
    return respond(ResponseEntity.status(HttpStatus.BAD_REQUEST), problem);
  }

  /** 405. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    String detail = ex.getMethod() + " is not supported on this resource.";
    return respond(
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED),
        problem(ProblemCodes.METHOD_NOT_ALLOWED, detail, request));
  }

  /** 406. */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleNotAcceptable(
      HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    return respond(
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE),
        problem(
            ProblemCodes.NOT_ACCEPTABLE, "This resource only produces application/json.", request));
  }

  /** 415. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    String supported =
        ex.getSupportedMediaTypes().stream()
            .map(MediaType::toString)
            .collect(Collectors.joining(", "));
    String detail =
        "Content-Type must be " + (supported.isEmpty() ? "application/json" : supported) + ".";
    return respond(
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        problem(ProblemCodes.UNSUPPORTED_MEDIA_TYPE, detail, request));
  }

  /** 404 for unknown routes. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResource(
      NoResourceFoundException ex, HttpServletRequest request) {
    return respond(
        ResponseEntity.status(HttpStatus.NOT_FOUND),
        problem(ProblemCodes.NOT_FOUND, "Resource not found.", request));
  }

  /** Anything unexpected: logged with the trace id, never leaked. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    if (ex instanceof ErrorResponse errorResponse) {
      return handleErrorResponse(errorResponse, request);
    }
    ProblemDetail problem =
        problem(
            ProblemCodes.INTERNAL_ERROR,
            "Something went wrong. Quote the traceId when contacting support.",
            request);
    LOG.error("Unhandled exception, traceId={}", problem.getProperties().get("traceId"), ex);
    return respond(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR), problem);
  }

  private static ResponseEntity<ProblemDetail> handleErrorResponse(
      ErrorResponse ex, HttpServletRequest request) {
    int status = ex.getStatusCode().value();
    ProblemCodes code =
        switch (status) {
          case 401 -> ProblemCodes.UNAUTHENTICATED;
          case 403 -> ProblemCodes.FORBIDDEN;
          case 404 -> ProblemCodes.NOT_FOUND;
          case 405 -> ProblemCodes.METHOD_NOT_ALLOWED;
          case 406 -> ProblemCodes.NOT_ACCEPTABLE;
          case 415 -> ProblemCodes.UNSUPPORTED_MEDIA_TYPE;
          case 428 -> ProblemCodes.PRECONDITION_REQUIRED;
          case 429 -> ProblemCodes.RATE_LIMITED;
          case 503 -> ProblemCodes.SERVICE_UNAVAILABLE;
          default -> status >= 500 ? ProblemCodes.INTERNAL_ERROR : ProblemCodes.VALIDATION_ERROR;
        };
    String detail = ex.getBody().getDetail();
    if (status >= 500 || detail == null) {
      detail = code.title() + ".";
    }
    return respond(ResponseEntity.status(code.status()), problem(code, detail, request));
  }

  private ResponseEntity<ProblemDetail> validation(
      List<Map<String, String>> errors, HttpServletRequest request) {
    String detail =
        errors.size() == 1 ? "1 field is invalid." : errors.size() + " fields are invalid.";
    ProblemDetail problem = problem(ProblemCodes.VALIDATION_ERROR, detail, request);
    problem.setProperty("errors", errors);
    return respond(ResponseEntity.status(HttpStatus.BAD_REQUEST), problem);
  }

  private static ProblemDetail problem(
      ProblemCodes code, String detail, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status()), detail);
    problem.setType(URI.create(code.type()));
    problem.setTitle(code.title());
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code.name());
    problem.setProperty("traceId", MdcFilter.currentTraceId());
    return problem;
  }

  private static ResponseEntity<ProblemDetail> respond(
      ResponseEntity.BodyBuilder builder, ProblemDetail problem) {
    return builder.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
  }
}
