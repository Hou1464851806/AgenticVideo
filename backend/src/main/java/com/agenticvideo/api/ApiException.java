package com.agenticvideo.api;

public final class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException notFound() {
    return new ApiException(404, "RESOURCE_NOT_FOUND", "资源不存在或无权限");
  }
}
