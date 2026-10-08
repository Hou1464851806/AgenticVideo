package com.agenticvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

final class TestBrowser {
  static final ObjectMapper JSON = new ObjectMapper();
  final HttpClient http =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .build();
  private final String base;
  private String header, token;

  TestBrowser(int port) {
    base = "http://localhost:" + port + "/api/v1";
  }

  HttpResponse<String> request(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    if (!method.equals("GET") && token != null) request.header(header, token);
    headers.forEach(request::header);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode get(String path) throws Exception {
    var response = request("GET", path, null, Map.of());
    if (response.statusCode() != 200) throw new IllegalStateException(response.body());
    return JSON.readTree(response.body());
  }

  JsonNode post(String path, Object body) throws Exception {
    var response = request("POST", path, body, Map.of());
    if (response.statusCode() >= 300) throw new IllegalStateException(response.body());
    return JSON.readTree(response.body());
  }

  UUID login() throws Exception {
    csrf();
    var credentials =
        Map.of(
            "email",
            "m1-integration-" + UUID.randomUUID() + "@example.test",
            "password",
            "Test-only-long-password-2026");
    post("/auth/register", credentials);
    var identity = post("/auth/login", credentials);
    csrf();
    return UUID.fromString(identity.path("id").asText());
  }

  private void csrf() throws Exception {
    var csrf = get("/auth/csrf");
    header = csrf.path("header_name").asText();
    token = csrf.path("token").asText();
  }
}
