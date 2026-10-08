package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Faults are restricted to the dedicated Compose test project, never development containers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_FAULT_INTEGRATION", matches = "true")
class WorkerFaultIntegrationTest {
  @LocalServerPort int port;
  private static final String WORKER = "agenticvideo-m1-empty-worker-1";
  private static final String SECOND = "agenticvideo-m1-empty-fault-worker";

  @Test
  void killWorkerExpiresRunningAttemptAndReplacementConsumesQueuedJob() throws Exception {
    fault(false);
  }

  @Test
  void pausedOldAttemptCannotCommitAfterLeaseExpires() throws Exception {
    fault(true);
  }

  @Test
  void simultaneousWorkersClaimEachJobOnlyOnce() throws Exception {
    assertEquals(
        "agenticvideo-m1-empty",
        command(
                "docker",
                "inspect",
                "--format",
                "{{index .Config.Labels \"com.docker.compose.project\"}}",
                WORKER)
            .trim());
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Concurrent claims")).path("id").asText();
    String asset =
        RenderIntegrationTest.upload(user, project, Path.of("../.runtime/media/red.mp4"));
    var dsl =
        (ObjectNode)
            TestBrowser.JSON.readTree(
                Files.readString(Path.of("../contracts/fixtures/valid.json")));
    dsl.path("tracks").get(0).path("clips").forEach(c -> ((ObjectNode) c).put("asset_id", asset));
    String plan = create(user, project, dsl);
    try {
      command(
          "docker",
          "compose",
          "--env-file",
          "../.runtime/empty.env",
          "-p",
          "agenticvideo-m1-empty",
          "-f",
          "../infra/compose.yaml",
          "run",
          "-d",
          "--no-deps",
          "--name",
          SECOND,
          "worker");
      long deadline = System.nanoTime() + 30000000000L;
      while (!command("docker", "logs", SECOND).contains("Started Application")) {
        assertTrue(System.nanoTime() < deadline, "Second Worker must start");
        Thread.sleep(200);
      }
      var jobs = new java.util.ArrayList<String>();
      for (int i = 0; i < 4; i++) jobs.add(render(user, plan));
      for (String job : jobs)
        RenderIntegrationTest.await(user, "/render-jobs/" + job, "status", "SUCCEEDED", 60);
      String primary = command("docker", "logs", WORKER),
          secondary = command("docker", "logs", SECOND);
      for (String job : jobs) {
        assertEquals(
            1L,
            (primary + secondary)
                .lines()
                .filter(line -> line.contains("render_claimed") && line.contains(job))
                .count());
      }
      assertTrue(
          jobs.stream()
              .anyMatch(
                  job ->
                      primary
                          .lines()
                          .anyMatch(
                              line -> line.contains("render_claimed") && line.contains(job))));
      assertTrue(
          jobs.stream()
              .anyMatch(
                  job ->
                      secondary
                          .lines()
                          .anyMatch(
                              line -> line.contains("render_claimed") && line.contains(job))));
    } finally {
      command("docker", "rm", "-f", SECOND);
    }
  }

  private void fault(boolean pause) throws Exception {
    assertEquals(
        "agenticvideo-m1-empty",
        command(
                "docker",
                "inspect",
                "--format",
                "{{index .Config.Labels \"com.docker.compose.project\"}}",
                WORKER)
            .trim());
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Worker fault acceptance")).path("id").asText();
    String asset =
        RenderIntegrationTest.upload(user, project, Path.of("../.runtime/media/red.mp4"));
    ObjectNode shortDsl =
        (ObjectNode)
            TestBrowser.JSON.readTree(
                Files.readString(Path.of("../contracts/fixtures/valid.json")));
    shortDsl
        .path("tracks")
        .get(0)
        .path("clips")
        .forEach(c -> ((ObjectNode) c).put("asset_id", asset));
    ObjectNode longDsl = shortDsl.deepCopy();
    longDsl.remove("constraints");
    var clips = (ArrayNode) longDsl.path("tracks").get(0).path("clips");
    var template = (ObjectNode) clips.get(0).deepCopy();
    clips.removeAll();
    for (int i = 0; i < 100; i++)
      clips.add(
          template
              .deepCopy()
              .put("clip_id", "fault-" + i)
              .put("source_in_ms", 0)
              .put("source_out_ms", 3000)
              .put("timeline_start_ms", i * 3000));
    String longPlan = create(user, project, longDsl), shortPlan = create(user, project, shortDsl);
    command("docker", "update", "--restart=no", WORKER);
    String active = render(user, longPlan);
    RenderIntegrationTest.await(user, "/render-jobs/" + active, "status", "RUNNING", 20);
    try {
      command("docker", pause ? "pause" : "kill", WORKER);
      String queued = render(user, shortPlan);
      assertEquals("QUEUED", user.get("/render-jobs/" + queued).path("status").asText());
      command(
          "docker",
          "compose",
          "--env-file",
          "../.runtime/empty.env",
          "-p",
          "agenticvideo-m1-empty",
          "-f",
          "../infra/compose.yaml",
          "run",
          "-d",
          "--no-deps",
          "--name",
          SECOND,
          "worker");
      RenderIntegrationTest.await(user, "/render-jobs/" + queued, "status", "SUCCEEDED", 30);
      JsonNode failed = null;
      long deadline = System.nanoTime() + 45000000000L;
      while (System.nanoTime() < deadline) {
        failed = user.get("/render-jobs/" + active);
        if (failed.path("status").asText().equals("FAILED")) break;
        Thread.sleep(250);
      }
      assertEquals("FAILED", failed.path("status").asText(), failed.toString());
      assertEquals("WORKER_LOST", failed.path("error_code").asText());
      if (pause) {
        command("docker", "unpause", WORKER);
        Thread.sleep(2000);
        assertEquals("FAILED", user.get("/render-jobs/" + active).path("status").asText());
      }
      assertEquals(
          409,
          user.request("GET", "/render-jobs/" + active + "/output", null, Map.of()).statusCode());
      String processes =
          command(
              "docker",
              "exec",
              SECOND,
              "sh",
              "-c",
              "for p in /proc/[0-9]*/comm; do read n < \"$p\"; case \"$n\" in ffmpeg|ffprobe) echo active-media;; esac; done");
      assertTrue(
          !processes.contains("active-media"), "Replacement must be idle after terminal jobs");
    } finally {
      if (pause) command("docker", "unpause", WORKER);
      command("docker", "rm", "-f", SECOND);
      command("docker", "start", WORKER);
      command("docker", "update", "--restart=unless-stopped", WORKER);
    }
  }

  private String create(TestBrowser user, String project, ObjectNode dsl) throws Exception {
    var response =
        user.request(
            "POST",
            "/projects/" + project + "/edit-plans",
            Map.of("name", "Fault plan", "dsl", dsl),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, response.statusCode(), response.body());
    return TestBrowser.JSON.readTree(response.body()).path("plan_id").asText();
  }

  private String render(TestBrowser user, String plan) throws Exception {
    var response =
        user.request(
            "POST",
            "/render-jobs",
            Map.of("plan_id", plan, "revision", 1, "render_profile", "PREVIEW_720P"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(202, response.statusCode(), response.body());
    return TestBrowser.JSON.readTree(response.body()).path("id").asText();
  }

  static String command(String... args) throws Exception {
    var process = new ProcessBuilder(List.of(args)).redirectErrorStream(true).start();
    String result =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Test Docker command timed out");
    }
    // Cleanup is idempotent: unpause can report already unpaused after a successful assertion.
    if (process.exitValue() != 0
        && !(args.length > 1 && args[1].equals("unpause") && result.contains("not paused")))
      throw new IllegalStateException(result);
    return result;
  }
}
