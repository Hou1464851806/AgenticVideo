package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_RESOURCE_FAULT_INTEGRATION", matches = "true")
class ResourceFaultIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void insufficientDiskFailsBeforeEncodingAndRetrySucceedsAfterCapacityRestored() throws Exception {
    assertEquals(
        "agenticvideo-m1-empty",
        WorkerFaultIntegrationTest.command(
                "docker",
                "inspect",
                "--format",
                "{{index .Config.Labels \"com.docker.compose.project\"}}",
                "agenticvideo-m1-empty-worker-1")
            .trim());
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Disk budget acceptance")).path("id").asText();
    String asset =
        RenderIntegrationTest.upload(user, project, Path.of("../.runtime/media/red.mp4"));
    var dsl =
        TestBrowser.JSON.readTree(
            java.nio.file.Files.readString(Path.of("../contracts/fixtures/valid.json")));
    dsl.path("tracks")
        .get(0)
        .path("clips")
        .forEach(c -> ((com.fasterxml.jackson.databind.node.ObjectNode) c).put("asset_id", asset));
    var plan =
        user.request(
            "POST",
            "/projects/" + project + "/edit-plans",
            Map.of("name", "Disk test", "dsl", dsl),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, plan.statusCode());
    String planId = TestBrowser.JSON.readTree(plan.body()).path("plan_id").asText();
    String job = "";
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from render_jobs where state in ('QUEUED','RUNNING','CANCEL_REQUESTED')",
            Integer.class));
    try {
      worker("1");
      var response =
          user.request(
              "POST",
              "/render-jobs",
              Map.of("plan_id", planId, "revision", 1, "render_profile", "PREVIEW_720P"),
              Map.of("Idempotency-Key", UUID.randomUUID().toString()));
      assertEquals(202, response.statusCode());
      job = TestBrowser.JSON.readTree(response.body()).path("id").asText();
      long deadline = System.nanoTime() + 30000000000L;
      String code = "";
      while (System.nanoTime() < deadline) {
        var state = user.get("/render-jobs/" + job);
        if (state.path("status").asText().equals("FAILED")) {
          code = state.path("error_code").asText();
          break;
        }
        Thread.sleep(200);
      }
      assertEquals("DISK_INSUFFICIENT", code);
      assertEquals(
          409, user.request("GET", "/render-jobs/" + job + "/output", null, Map.of()).statusCode());
    } finally {
      worker(null);
    }
    var retry =
        user.request(
            "POST",
            "/render-jobs/" + job + "/retry",
            Map.of(),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(202, retry.statusCode(), retry.body());
    String next = TestBrowser.JSON.readTree(retry.body()).path("id").asText();
    RenderIntegrationTest.await(user, "/render-jobs/" + next, "status", "SUCCEEDED", 30);
  }

  private static void worker(String budget) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "compose",
                "--env-file",
                "../.runtime/empty.env",
                "-p",
                "agenticvideo-m1-empty",
                "-f",
                "../infra/compose.yaml",
                "up",
                "-d",
                "--wait",
                "worker")
            .redirectErrorStream(true);
    if (budget == null) process.environment().remove("WORKER_DISK_BYTES");
    else process.environment().put("WORKER_DISK_BYTES", budget);
    var running = process.start();
    String output =
        new String(
            running.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, running.waitFor(), output);
  }
}
