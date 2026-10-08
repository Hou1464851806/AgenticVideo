package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Small fixtures with lowered limits test enforcement, not large-media capacity. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_MEDIA_BOUNDARY_INTEGRATION", matches = "true")
class MediaBoundaryIntegrationTest {
  @LocalServerPort int port;

  @Test
  void realProbeEnforcesAssetAndProjectDurationUsingSmallFixtures() throws Exception {
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
        user.post("/projects", Map.of("name", "Small duration boundaries")).path("id").asText();
    try {
      limits("1000", "4000");
      String rejected =
          RenderIntegrationTest.uploadToState(
              user, project, Path.of("../.runtime/media/red.mp4"), "INVALID");
      assertEquals(
          "ASSET_DURATION_EXCEEDED", user.get("/assets/" + rejected).path("error_code").asText());
      limits("4000", "4000");
      String accepted =
          RenderIntegrationTest.upload(user, project, Path.of("../.runtime/media/red.mp4"));
      assertEquals(3000, user.get("/assets/" + accepted).path("duration_ms").asInt());
      String excess =
          RenderIntegrationTest.uploadToState(
              user, project, Path.of("../.runtime/media/blue.mp4"), "INVALID");
      assertEquals(
          "PROJECT_DURATION_EXCEEDED", user.get("/assets/" + excess).path("error_code").asText());
    } finally {
      limits(null, null);
    }
  }

  private static void limits(String asset, String project) throws Exception {
    var command =
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
    if (asset == null) command.environment().remove("ASSET_DURATION_MS");
    else command.environment().put("ASSET_DURATION_MS", asset);
    if (project == null) command.environment().remove("PROJECT_DURATION_MS");
    else command.environment().put("PROJECT_DURATION_MS", project);
    var process = command.start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
