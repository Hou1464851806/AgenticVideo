package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION", matches = "true")
class IdentityProjectIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();
  private final String password = "Test-only-long-password-2026";

  private final class Browser {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String header = "X-CSRF-TOKEN", token;

    HttpResponse<String> request(String method, String path, Object body, boolean withCsrf)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .header("Content-Type", "application/json");
      if (withCsrf) request.header(header, token);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    void csrf() throws Exception {
      var data = json.readTree(request("GET", "/auth/csrf", null, false).body());
      header = data.path("header_name").asText();
      token = data.path("token").asText();
    }

    UUID login(String email) throws Exception {
      csrf();
      assertEquals(
          201,
          request("POST", "/auth/register", Map.of("email", email, "password", password), true)
              .statusCode());
      assertEquals(401, request("GET", "/auth/me", null, false).statusCode());
      assertEquals(
          200,
          request("POST", "/auth/login", Map.of("email", email, "password", password), true)
              .statusCode());
      csrf();
      var me = request("GET", "/auth/me", null, false);
      assertEquals(200, me.statusCode());
      return UUID.fromString(json.readTree(me.body()).path("id").asText());
    }
  }

  @Test
  void realRegistrationSessionCsrfAndOwnership() throws Exception {
    Browser alice = new Browser(), bob = new Browser();
    String email = "m1-alice-" + UUID.randomUUID() + "@example.test";
    UUID user = alice.login(email);
    bob.login("m1-bob-" + UUID.randomUUID() + "@example.test");
    assertFalse(
        password.equals(
            jdbc.queryForObject("select password_hash from users where id=?", String.class, user)));
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from business_schema.spring_session where principal_name=?",
                Long.class,
                user.toString())
            > 0);
    var missingCsrf = alice.request("POST", "/projects", Map.of("name", "denied"), false);
    assertEquals(403, missingCsrf.statusCode());
    assertEquals("CSRF_INVALID", json.readTree(missingCsrf.body()).path("code").asText());
    var created = alice.request("POST", "/projects", Map.of("name", "M1 private project"), true);
    assertEquals(201, created.statusCode());
    JsonNode project = json.readTree(created.body());
    String id = project.path("id").asText();
    assertEquals(200, alice.request("GET", "/projects/" + id, null, false).statusCode());
    var forbidden = bob.request("GET", "/projects/" + id, null, false);
    assertEquals(404, forbidden.statusCode());
    assertEquals("RESOURCE_NOT_FOUND", json.readTree(forbidden.body()).path("code").asText());
    assertTrue(forbidden.headers().firstValue("X-Request-ID").isPresent());
    var bobsProjects = json.readTree(bob.request("GET", "/projects", null, false).body());
    assertEquals(0, bobsProjects.path("total").asInt());
    assertEquals(
        400,
        alice
            .request(
                "POST",
                "/projects",
                Map.of("name", "fake owner", "owner_id", UUID.randomUUID()),
                true)
            .statusCode());
    assertEquals(422, alice.request("GET", "/projects?limit=101", null, false).statusCode());
    assertEquals(
        409,
        alice
            .request("POST", "/auth/register", Map.of("email", email, "password", password), true)
            .statusCode());
    assertEquals(
        401,
        alice
            .request(
                "POST",
                "/auth/login",
                Map.of("email", email, "password", "incorrect-password"),
                true)
            .statusCode());
    assertEquals(204, alice.request("POST", "/auth/logout", null, true).statusCode());
    assertEquals(401, alice.request("GET", "/projects/" + id, null, false).statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from business_schema.spring_session where principal_name=?",
            Long.class,
            user.toString()));
  }
}
