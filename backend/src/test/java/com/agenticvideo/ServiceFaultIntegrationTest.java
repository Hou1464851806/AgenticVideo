package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "M1_SERVICE_FAULT_INTEGRATION", matches = "true")
class ServiceFaultIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private static void owned(String container) throws Exception {
    assertEquals(
        "agenticvideo-m1-empty",
        WorkerFaultIntegrationTest.command(
                "docker",
                "inspect",
                "--format",
                "{{index .Config.Labels \"com.docker.compose.project\"}}",
                container)
            .trim());
  }

  @Test
  void siloFailureKeepsDurableIntentAndSameKeyRecovers() throws Exception {
    String container = "agenticvideo-m1-empty-silo-1";
    owned(container);
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Storage outage acceptance")).path("id").asText();
    String key = UUID.randomUUID().toString();
    var body = Map.of("filename", "recovery.mp4", "file_size", 100, "content_type", "video/mp4");
    String endpoint = "/projects/" + project + "/uploads/init";
    WorkerFaultIntegrationTest.command("docker", "stop", container);
    try {
      var failed = user.request("POST", endpoint, body, Map.of("Idempotency-Key", key));
      assertEquals(503, failed.statusCode(), failed.body());
      assertEquals(
          "STORAGE_UNAVAILABLE", TestBrowser.JSON.readTree(failed.body()).path("code").asText());
      assertEquals(project, user.get("/projects/" + project).path("id").asText());
      assertEquals(
          1,
          jdbc.queryForObject(
              "select count(*) from upload_sessions where project_id=? and idempotency_key=?",
              Integer.class,
              UUID.fromString(project),
              key));
    } finally {
      WorkerFaultIntegrationTest.command("docker", "start", container);
    }
    String upload = "";
    long deadline = System.nanoTime() + 30000000000L;
    while (System.nanoTime() < deadline) {
      var result = user.request("POST", endpoint, body, Map.of("Idempotency-Key", key));
      if (result.statusCode() == 201) {
        upload = TestBrowser.JSON.readTree(result.body()).path("id").asText();
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(!upload.isBlank());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from upload_sessions where project_id=? and idempotency_key=?",
            Integer.class,
            UUID.fromString(project),
            key));
    user.post("/uploads/" + upload + "/abort", Map.of());
  }

  @Test
  void postgresOutageReturnsStableFailureAndSessionRecovers() throws Exception {
    String container = "agenticvideo-m1-empty-postgres-1";
    owned(container);
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Database outage acceptance")).path("id").asText();
    WorkerFaultIntegrationTest.command("docker", "stop", container);
    try {
      var result = user.request("GET", "/projects/" + project, null, Map.of());
      assertEquals(503, result.statusCode(), result.body());
      assertEquals(
          "DATABASE_UNAVAILABLE", TestBrowser.JSON.readTree(result.body()).path("code").asText());
    } finally {
      WorkerFaultIntegrationTest.command("docker", "start", container);
    }
    long deadline = System.nanoTime() + 30000000000L;
    boolean recovered = false;
    while (System.nanoTime() < deadline) {
      var result = user.request("GET", "/projects/" + project, null, Map.of());
      if (result.statusCode() == 200) {
        assertEquals(project, TestBrowser.JSON.readTree(result.body()).path("id").asText());
        recovered = true;
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(recovered, "Persisted session and project must survive PostgreSQL restart");
  }
}
