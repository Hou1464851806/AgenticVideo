package com.agenticvideo.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute("trace_id", id);
    response.setHeader("X-Request-ID", id);
    if (request.getContentLengthLong() > 1048576) {
      tooLarge(request, response);
      return;
    }
    HttpServletRequest bounded = request;
    if (request.getRequestURI().startsWith("/api/")
        && java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
      byte[] body = request.getInputStream().readNBytes(1048577);
      if (body.length > 1048576) {
        tooLarge(request, response);
        return;
      }
      bounded =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              var input = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                @Override
                public int read() {
                  return input.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                  return input.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                  return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Synchronous bounded API body");
                }
              };
            }

            @Override
            public java.io.BufferedReader getReader() {
              return new java.io.BufferedReader(
                  new java.io.InputStreamReader(
                      getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
    }
    try {
      chain.doFilter(bounded, response);
    } catch (Exception error) {
      Throwable cause = error;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      if (!response.isCommitted()
          && (error instanceof org.springframework.dao.DataAccessException
              || cause instanceof java.sql.SQLException)) {
        response.resetBuffer();
        Errors.write(request, response, 503, "DATABASE_UNAVAILABLE", "服务暂时不可用，请稍后重试");
        return;
      }
      if (error instanceof ServletException servlet) throw servlet;
      if (error instanceof IOException io) throw io;
      if (error instanceof RuntimeException runtime) throw runtime;
      throw new ServletException(error);
    }
  }

  private static void tooLarge(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Errors.write(request, response, 413, "REQUEST_TOO_LARGE", "业务请求超过 1 MiB");
  }
}
