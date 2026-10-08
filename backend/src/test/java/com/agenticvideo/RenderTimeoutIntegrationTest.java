package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Requires a separate real media Worker configured with a two-second render deadline. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_TIMEOUT_INTEGRATION", matches = "true")
class RenderTimeoutIntegrationTest {
  @LocalServerPort int port;

  @Test
  void deadlinePersistsFailureAndRetryCreatesNewTask() throws Exception {
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Timeout acceptance")).path("id").asText();
    String asset =
        RenderIntegrationTest.upload(user, project, Path.of("../.runtime/media/red.mp4"));
    ObjectNode dsl =
        (ObjectNode)
            TestBrowser.JSON.readTree(
                Files.readString(Path.of("../contracts/fixtures/valid.json")));
    dsl.remove("constraints");
    var clips = (ArrayNode) dsl.path("tracks").get(0).path("clips");
    var template = (ObjectNode) clips.get(0).deepCopy();
    clips.removeAll();
    for (int i = 0; i < 100; i++)
      clips.add(
          template
              .deepCopy()
              .put("clip_id", "timeout-" + i)
              .put("asset_id", asset)
              .put("source_in_ms", 0)
              .put("source_out_ms", 3000)
              .put("timeline_start_ms", i * 3000));
    var plan =
        user.request(
            "POST",
            "/projects/" + project + "/edit-plans",
            Map.of("name", "Timeout", "dsl", dsl),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, plan.statusCode(), plan.body());
    String planId = TestBrowser.JSON.readTree(plan.body()).path("plan_id").asText();
    var job =
        user.request(
            "POST",
            "/render-jobs",
            Map.of("plan_id", planId, "revision", 1, "render_profile", "PREVIEW_720P"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(202, job.statusCode());
    String id = TestBrowser.JSON.readTree(job.body()).path("id").asText();
    long deadline = System.nanoTime() + 30000000000L;
    String code = "";
    while (System.nanoTime() < deadline) {
      var state = user.get("/render-jobs/" + id);
      if (state.path("status").asText().equals("FAILED")) {
        code = state.path("error_code").asText();
        break;
      }
      Thread.sleep(200);
    }
    assertEquals("RENDER_TIMEOUT", code);
    assertEquals(
        409, user.request("GET", "/render-jobs/" + id + "/output", null, Map.of()).statusCode());
    String key = UUID.randomUUID().toString();
    var retried =
        user.request(
            "POST", "/render-jobs/" + id + "/retry", Map.of(), Map.of("Idempotency-Key", key));
    assertEquals(202, retried.statusCode(), retried.body());
    var retry = TestBrowser.JSON.readTree(retried.body());
    String retryId = retry.path("id").asText();
    assertTrue(!retryId.equals(id));
    assertEquals(id, retry.path("retry_of").asText());
    assertEquals(
        retryId,
        TestBrowser.JSON
            .readTree(
                user.request(
                        "POST",
                        "/render-jobs/" + id + "/retry",
                        Map.of(),
                        Map.of("Idempotency-Key", key))
                    .body())
            .path("id")
            .asText());
    user.post("/render-jobs/" + retryId + "/cancel", Map.of());
  }
}
