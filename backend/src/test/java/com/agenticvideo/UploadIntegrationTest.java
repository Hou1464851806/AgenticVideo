package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_INTEGRATION", matches = "true")
class UploadIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void atomicQuotaReservationsAndAbortRelease() throws Exception {
    var browser = new TestBrowser(port);
    browser.login();
    String project =
        browser.post("/projects", Map.of("name", "Concurrent quota test")).path("id").asText();
    String endpoint = "/projects/" + project + "/uploads/init";
    var body = Map.of("filename", "quota.mp4", "file_size", 100, "content_type", "video/mp4");
    var requests = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 31; i++)
        requests.add(
            executor.submit(
                () ->
                    browser.request(
                        "POST",
                        endpoint,
                        body,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()))));
      int success = 0, rejected = 0;
      var ids = new ArrayList<String>();
      for (var future : requests) {
        var result = future.get();
        if (result.statusCode() == 201) {
          success++;
          ids.add(TestBrowser.JSON.readTree(result.body()).path("id").asText());
        } else {
          assertEquals(413, result.statusCode());
          rejected++;
        }
      }
      assertEquals(30, success);
      assertEquals(1, rejected);
      for (var id : ids) browser.post("/uploads/" + id + "/abort", Map.of());
    }
    var large =
        browser.request(
            "POST",
            endpoint,
            Map.of(
                "filename", "limit.mp4", "file_size", 4_000_000_000L, "content_type", "video/mp4"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, large.statusCode());
    assertEquals(
        413,
        browser
            .request(
                "POST",
                endpoint,
                Map.of(
                    "filename",
                    "too-large.mp4",
                    "file_size",
                    4_000_000_001L,
                    "content_type",
                    "video/mp4"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()))
            .statusCode());
    browser.post(
        "/uploads/" + TestBrowser.JSON.readTree(large.body()).path("id").asText() + "/abort",
        Map.of());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from upload_sessions where project_id=? and state in ('INITIATED','COMPLETING')",
            Long.class,
            UUID.fromString(project)));
  }

  @Test
  void actualSizeMismatchInvalidManifestExpiryAndCorruption() throws Exception {
    var browser = new TestBrowser(port);
    browser.login();
    String project =
        browser.post("/projects", Map.of("name", "Upload failure tests")).path("id").asText();
    for (boolean mismatch : List.of(true, false)) {
      byte[] corrupt = "not a media file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var result =
          browser.request(
              "POST",
              "/projects/" + project + "/uploads/init",
              Map.of(
                  "filename",
                  "corrupt.mp4",
                  "file_size",
                  corrupt.length + (mismatch ? 1 : 0),
                  "content_type",
                  "video/mp4"),
              Map.of("Idempotency-Key", UUID.randomUUID().toString()));
      assertEquals(201, result.statusCode());
      String id = TestBrowser.JSON.readTree(result.body()).path("id").asText();
      var urls =
          browser.post("/uploads/" + id + "/parts/presign", Map.of("part_numbers", List.of(1)));
      var uploaded =
          browser.http.send(
              HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText()))
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(corrupt))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, uploaded.statusCode());
      String etag = uploaded.headers().firstValue("etag").orElseThrow();
      assertEquals(
          422,
          browser
              .request("POST", "/uploads/" + id + "/complete", Map.of("parts", List.of()), Map.of())
              .statusCode());
      var complete =
          browser.request(
              "POST",
              "/uploads/" + id + "/complete",
              Map.of("parts", List.of(Map.of("part_number", 1, "etag", etag))),
              Map.of());
      if (mismatch) {
        assertEquals(422, complete.statusCode());
        assertEquals(
            "UPLOAD_SIZE_MISMATCH",
            TestBrowser.JSON.readTree(complete.body()).path("code").asText());
        assertEquals("FAILED", browser.get("/uploads/" + id).path("status").asText());
      } else {
        assertEquals(202, complete.statusCode());
        String asset = TestBrowser.JSON.readTree(complete.body()).path("asset_id").asText();
        long end = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        while (browser.get("/assets/" + asset).path("status").asText().equals("VALIDATING")
            && System.nanoTime() < end) Thread.sleep(200);
        assertEquals("INVALID", browser.get("/assets/" + asset).path("status").asText());
        assertEquals("MEDIA_INVALID", browser.get("/assets/" + asset).path("error_code").asText());
        assertEquals(
            409,
            browser.request("GET", "/assets/" + asset + "/content", null, Map.of()).statusCode());
      }
    }
    var expired =
        browser.request(
            "POST",
            "/projects/" + project + "/uploads/init",
            Map.of("filename", "expired.mp4", "file_size", 10, "content_type", "video/mp4"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    UUID id = UUID.fromString(TestBrowser.JSON.readTree(expired.body()).path("id").asText());
    jdbc.update("update upload_sessions set expires_at=now()-interval '1 second' where id=?", id);
    assertEquals(
        409,
        browser
            .request(
                "POST",
                "/uploads/" + id + "/parts/presign",
                Map.of("part_numbers", List.of(1)),
                Map.of())
            .statusCode());
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from assets where project_id=? and state='MEDIA_READY'",
                Long.class,
                UUID.fromString(project))
            == 0);
  }
}
