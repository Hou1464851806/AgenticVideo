package com.agenticvideo.api;

import com.agenticvideo.timeline.PlanValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {
  public static Map<String, Object> body(String code, String message, HttpServletRequest request) {
    return Map.of(
        "code",
        code,
        "message",
        message,
        "trace_id",
        String.valueOf(request.getAttribute("trace_id")));
  }

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    new tools.jackson.databind.ObjectMapper()
        .writeValue(response.getOutputStream(), body(code, message, request));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException error, HttpServletRequest request) {
    return ResponseEntity.status(error.status())
        .body(body(error.code(), error.getMessage(), request));
  }

  @ExceptionHandler(PlanValidator.InvalidPlan.class)
  ResponseEntity<?> invalidPlan(PlanValidator.InvalidPlan error, HttpServletRequest request) {
    return ResponseEntity.unprocessableContent()
        .body(
            Map.of(
                "code",
                "EDIT_PLAN_INVALID",
                "message",
                "剪辑方案无效",
                "trace_id",
                String.valueOf(request.getAttribute("trace_id")),
                "details",
                error.violations()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<?> malformed(Exception error, HttpServletRequest request) {
    return ResponseEntity.badRequest().body(body("REQUEST_INVALID", "请求格式或参数无效", request));
  }

  @ExceptionHandler({
    org.springframework.dao.DataAccessResourceFailureException.class,
    org.springframework.transaction.CannotCreateTransactionException.class
  })
  ResponseEntity<?> unavailable(Exception error, HttpServletRequest request) {
    return ResponseEntity.status(503).body(body("DATABASE_UNAVAILABLE", "服务暂时不可用，请稍后重试", request));
  }

  @ExceptionHandler({software.amazon.awssdk.core.exception.SdkException.class})
  ResponseEntity<?> storage(Exception error, HttpServletRequest request) {
    return ResponseEntity.status(503)
        .body(body("STORAGE_UNAVAILABLE", "对象存储暂时不可用，请重试同一请求", request));
  }
}
