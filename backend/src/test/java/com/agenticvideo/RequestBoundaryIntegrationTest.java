package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION", matches = "true")
class RequestBoundaryIntegrationTest {
  @LocalServerPort int port;

  @Test
  void chunkedAndDeclaredLargeBodiesRejectBeforeBusinessProcessing() throws Exception {
    var browser = new TestBrowser(port);
    byte[] body = new byte[1048577];
    java.util.Arrays.fill(body, (byte) 'x');
    for (var publisher :
        List.of(
            HttpRequest.BodyPublishers.ofByteArray(body),
            HttpRequest.BodyPublishers.ofByteArrays(List.of(body)))) {
      var response =
          browser.http.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                  .header("Content-Type", "application/json")
                  .POST(publisher)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, response.statusCode(), response.body());
      assertEquals(
          "REQUEST_TOO_LARGE", TestBrowser.JSON.readTree(response.body()).path("code").asText());
    }
  }

  @Test
  void typedInputDoesNotCoerceStringsToNumbers() throws Exception {
    var browser = new TestBrowser(port);
    browser.login();
    var response =
        browser.request(
            "POST",
            "/render-jobs",
            Map.of(
                "plan_id",
                "00000000-0000-0000-0000-000000000001",
                "revision",
                "1",
                "render_profile",
                "PREVIEW_720P"),
            Map.of("Idempotency-Key", "coercion-test"));
    assertEquals(400, response.statusCode(), response.body());
    assertEquals(
        "REQUEST_INVALID", TestBrowser.JSON.readTree(response.body()).path("code").asText());
  }
}
