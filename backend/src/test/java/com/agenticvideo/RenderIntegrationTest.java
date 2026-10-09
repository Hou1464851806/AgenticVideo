package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
class RenderIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void immutableApprovedRevisionToRealPreviewAndExport() throws Exception {
    var user = new TestBrowser(port);
    user.login();
    var other = new TestBrowser(port);
    other.login();
    String project =
        user.post("/projects", Map.of("name", "Render acceptance")).path("id").asText();
    String asset = upload(user, project, Path.of("../.runtime/media/red.mp4"));
    ObjectNode dsl =
        (ObjectNode)
            TestBrowser.JSON.readTree(
                Files.readString(Path.of("../contracts/fixtures/valid.json")));
    var clips = dsl.path("tracks").get(0).path("clips");
    clips.forEach(c -> ((ObjectNode) c).put("asset_id", asset));
    String key = UUID.randomUUID().toString();
    var created =
        user.request(
            "POST",
            "/projects/" + project + "/edit-plans",
            Map.of("name", "Immutable test", "dsl", dsl),
            Map.of("Idempotency-Key", key));
    assertEquals(201, created.statusCode(), created.body());
    var plan = TestBrowser.JSON.readTree(created.body());
    String id = plan.path("plan_id").asText();
    assertEquals(
        created.body(),
        user.request(
                "POST",
                "/projects/" + project + "/edit-plans",
                Map.of("name", "Immutable test", "dsl", dsl),
                Map.of("Idempotency-Key", key))
            .body());
    assertEquals(404, other.request("GET", "/edit-plans/" + id, null, Map.of()).statusCode());
    assertEquals(409, user.request("DELETE", "/assets/" + asset, null, Map.of()).statusCode());
    var finalBody = Map.of("plan_id", id, "revision", 1, "render_profile", "FINAL_1080P");
    assertEquals(
        409,
        user.request(
                "POST",
                "/render-jobs",
                finalBody,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()))
            .statusCode());
    user.post(
        "/edit-plans/" + id + "/approve", Map.of("revision", 1, "render_profile", "FINAL_1080P"));
    String updateKey = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () ->
                  user.request(
                      "PUT",
                      "/edit-plans/" + id,
                      Map.of("dsl", dsl),
                      Map.of("If-Match", "\"revision-1\"", "Idempotency-Key", updateKey)));
      var b =
          pool.submit(
              () ->
                  user.request(
                      "PUT",
                      "/edit-plans/" + id,
                      Map.of("dsl", dsl),
                      Map.of(
                          "If-Match",
                          "\"revision-1\"",
                          "Idempotency-Key",
                          UUID.randomUUID().toString())));
      int sa = a.get().statusCode(), sb = b.get().statusCode();
      assertTrue((sa == 200 && sb == 409) || (sa == 409 && sb == 200));
    }
    assertEquals(2, user.get("/edit-plans/" + id).path("revision").asInt());
    assertEquals(plan, user.get("/edit-plans/" + id + "/revisions/1"));
    assertEquals(
        409,
        user.request(
                "POST",
                "/render-jobs",
                Map.of("plan_id", id, "revision", 2, "render_profile", "FINAL_1080P"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()))
            .statusCode());
    for (String profile : List.of("PREVIEW_720P", "FINAL_1080P")) {
      String renderKey = UUID.randomUUID().toString();
      var body = Map.of("plan_id", id, "revision", 1, "render_profile", profile);
      var response =
          user.request("POST", "/render-jobs", body, Map.of("Idempotency-Key", renderKey));
      assertEquals(202, response.statusCode(), response.body());
      String job = TestBrowser.JSON.readTree(response.body()).path("id").asText();
      assertEquals(
          job,
          TestBrowser.JSON
              .readTree(
                  user.request("POST", "/render-jobs", body, Map.of("Idempotency-Key", renderKey))
                      .body())
              .path("id")
              .asText());
      assertEquals(
          409,
          user.request(
                  "POST",
                  "/render-jobs",
                  Map.of("plan_id", id, "revision", 2, "render_profile", profile),
                  Map.of("Idempotency-Key", renderKey))
              .statusCode());
      assertEquals(404, other.request("GET", "/render-jobs/" + job, null, Map.of()).statusCode());
      JsonNode finished = await(user, "/render-jobs/" + job, "status", "SUCCEEDED", 90);
      assertEquals(100, finished.path("progress").asInt());
      assertEquals(
          409,
          user.request("POST", "/render-jobs/" + job + "/cancel", Map.of(), Map.of()).statusCode());
      assertEquals("SUCCEEDED", user.get("/render-jobs/" + job).path("status").asText());
      var url = user.get("/render-jobs/" + job + "/output").path("url").asText();
      var video =
          user.http.send(
              HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-63").GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(206, video.statusCode());
      assertEquals(64, video.body().length);
      assertEquals(
          404,
          other.request("GET", "/render-jobs/" + job + "/output", null, Map.of()).statusCode());
      var stored =
          jdbc.queryForMap(
              "select revision,output_hash,output_bytes,ffmpeg_version,compiler_hash from render_jobs where id=?",
              UUID.fromString(job));
      assertEquals(1, stored.get("revision"));
      assertEquals(64, stored.get("output_hash").toString().length());
      assertTrue(((Number) stored.get("output_bytes")).longValue() > 1000);
      assertTrue(stored.get("ffmpeg_version").toString().contains("6.1.1"));
      assertEquals(64, stored.get("compiler_hash").toString().length());
    }
  }

  @Test
  void invalidPlansCreateNoExecutableStateAndDeleteCannotRaceNewReference() throws Exception {
    var user = new TestBrowser(port);
    user.login();
    String project = user.post("/projects", Map.of("name", "Reference race")).path("id").asText();
    for (int round = 0; round < 3; round++) {
      String asset = upload(user, project, Path.of("../.runtime/media/red.mp4"));
      var dsl =
          (ObjectNode)
              TestBrowser.JSON.readTree(
                  Files.readString(Path.of("../contracts/fixtures/valid.json")));
      dsl.path("tracks").get(0).path("clips").forEach(c -> ((ObjectNode) c).put("asset_id", asset));
      var invalid = dsl.deepCopy();
      ((ObjectNode) invalid.path("tracks").get(0).path("clips").get(0))
          .put("source_out_ms", 999999);
      var rejected =
          user.request(
              "POST",
              "/projects/" + project + "/edit-plans",
              Map.of("name", "Invalid", "dsl", invalid),
              Map.of("Idempotency-Key", UUID.randomUUID().toString()));
      assertEquals(422, rejected.statusCode());
      assertTrue(
          !TestBrowser.JSON
              .readTree(rejected.body())
              .path("details")
              .get(0)
              .path("path")
              .asText()
              .isEmpty());
      assertEquals(
          round,
          jdbc.queryForObject(
                  "select count(*) from edit_plans where project_id=?",
                  Integer.class,
                  UUID.fromString(project))
              + jdbc.queryForObject(
                  "select count(*) from assets where project_id=? and state in ('DELETING','DELETED')",
                  Integer.class,
                  UUID.fromString(project)));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from render_jobs where project_id=?",
              Integer.class,
              UUID.fromString(project)));
      try (var pool = Executors.newFixedThreadPool(2)) {
        var saved =
            pool.submit(
                () ->
                    user.request(
                        "POST",
                        "/projects/" + project + "/edit-plans",
                        Map.of("name", "Race", "dsl", dsl),
                        Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        var deleted = pool.submit(() -> user.request("DELETE", "/assets/" + asset, null, Map.of()));
        int saveStatus = saved.get().statusCode(), deleteStatus = deleted.get().statusCode();
        assertTrue(
            (saveStatus == 201 && deleteStatus == 409)
                || (saveStatus == 422 && deleteStatus == 202),
            "Save/delete must have one winner: " + saveStatus + "/" + deleteStatus);
        if (saveStatus == 201)
          assertEquals("MEDIA_READY", user.get("/assets/" + asset).path("status").asText());
        else
          assertEquals(
              0,
              jdbc.queryForObject(
                  "select count(*) from revision_asset_refs where asset_id=?",
                  Integer.class,
                  UUID.fromString(asset)));
      }
    }
  }

  @Test
  void saveAndDeleteEnforceBothOrderings() throws Exception {
    var user = new TestBrowser(port);
    user.login();
    for (boolean saveFirst : new boolean[] {true, false}) {
      String project =
          user.post("/projects", Map.of("name", "Ordered reference protection"))
              .path("id")
              .asText();
      String asset = upload(user, project, Path.of("../.runtime/media/red.mp4"));
      var dsl =
          (ObjectNode)
              TestBrowser.JSON.readTree(
                  Files.readString(Path.of("../contracts/fixtures/valid.json")));
      dsl.path("tracks").get(0).path("clips").forEach(c -> ((ObjectNode) c).put("asset_id", asset));
      java.util.concurrent.Callable<HttpResponse<String>> save =
          () ->
              user.request(
                  "POST",
                  "/projects/" + project + "/edit-plans",
                  Map.of("name", "Ordered", "dsl", dsl),
                  Map.of("Idempotency-Key", UUID.randomUUID().toString()));
      if (saveFirst) {
        assertEquals(201, save.call().statusCode());
        assertEquals(409, user.request("DELETE", "/assets/" + asset, null, Map.of()).statusCode());
        assertEquals("MEDIA_READY", user.get("/assets/" + asset).path("status").asText());
      } else {
        assertEquals(202, user.request("DELETE", "/assets/" + asset, null, Map.of()).statusCode());
        var rejected = save.call();
        assertEquals(422, rejected.statusCode(), rejected.body());
        assertEquals(
            "EDIT_PLAN_INVALID", TestBrowser.JSON.readTree(rejected.body()).path("code").asText());
      }
      assertEquals(
          saveFirst ? 1 : 0,
          jdbc.queryForObject(
              "select count(*) from revision_asset_refs where asset_id=?",
              Integer.class,
              UUID.fromString(asset)));
      assertEquals(
          saveFirst ? 1 : 0,
          jdbc.queryForObject(
              "select count(*) from edit_plans where project_id=?",
              Integer.class,
              UUID.fromString(project)));
    }
  }

  @Test
  void runningCancelRejectsSuccessAndStopsEncoding() throws Exception {
    var user = new TestBrowser(port);
    user.login();
    String project =
        user.post("/projects", Map.of("name", "Cancellation acceptance")).path("id").asText();
    String asset = upload(user, project, Path.of("../.runtime/media/red.mp4"));
    ObjectNode dsl =
        (ObjectNode)
            TestBrowser.JSON.readTree(
                Files.readString(Path.of("../contracts/fixtures/valid.json")));
    var clips =
        (com.fasterxml.jackson.databind.node.ArrayNode) dsl.path("tracks").get(0).path("clips");
    dsl.remove("constraints");
    var template = (ObjectNode) clips.get(0).deepCopy();
    clips.removeAll();
    for (int i = 0; i < 100; i++)
      clips.add(
          template
              .deepCopy()
              .put("clip_id", "cancel-" + i)
              .put("asset_id", asset)
              .put("source_in_ms", 0)
              .put("source_out_ms", 3000)
              .put("timeline_start_ms", i * 3000));
    var response =
        user.request(
            "POST",
            "/projects/" + project + "/edit-plans",
            Map.of("name", "Long render", "dsl", dsl),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, response.statusCode(), response.body());
    String plan = TestBrowser.JSON.readTree(response.body()).path("plan_id").asText();
    var queued =
        user.request(
            "POST",
            "/render-jobs",
            Map.of("plan_id", plan, "revision", 1, "render_profile", "FINAL_1080P"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(409, queued.statusCode());
    queued =
        user.request(
            "POST",
            "/render-jobs",
            Map.of("plan_id", plan, "revision", 1, "render_profile", "PREVIEW_720P"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(202, queued.statusCode(), queued.body());
    String job = TestBrowser.JSON.readTree(queued.body()).path("id").asText();
    await(user, "/render-jobs/" + job, "status", "RUNNING", 20);
    var cancelled = user.post("/render-jobs/" + job + "/cancel", Map.of());
    assertTrue(
        List.of("CANCEL_REQUESTED", "CANCELLED").contains(cancelled.path("status").asText()));
    await(user, "/render-jobs/" + job, "status", "CANCELLED", 15);
    assertEquals(
        409, user.request("GET", "/render-jobs/" + job + "/output", null, Map.of()).statusCode());
    assertEquals(
        409,
        user.request(
                "POST",
                "/render-jobs/" + job + "/retry",
                Map.of(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()))
            .statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from render_jobs where id=? and output_key is not null",
            Integer.class,
            UUID.fromString(job)));
  }

  static String upload(TestBrowser user, String project, Path file) throws Exception {
    return uploadToState(user, project, file, "MEDIA_READY");
  }

  static String uploadToState(TestBrowser user, String project, Path file, String target)
      throws Exception {
    var init =
        user.request(
            "POST",
            "/projects/" + project + "/uploads/init",
            Map.of(
                "filename", "test.mp4", "content_type", "video/mp4", "file_size", Files.size(file)),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, init.statusCode(), init.body());
    String upload = TestBrowser.JSON.readTree(init.body()).path("id").asText();
    var sign =
        user.post("/uploads/" + upload + "/parts/presign", Map.of("part_numbers", List.of(1)));
    var put =
        user.http.send(
            HttpRequest.newBuilder(URI.create(sign.path("parts").get(0).path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, put.statusCode(), put.body());
    String asset =
        user.post(
                "/uploads/" + upload + "/complete",
                Map.of(
                    "parts",
                    List.of(
                        Map.of(
                            "part_number",
                            1,
                            "etag",
                            put.headers().firstValue("etag").orElseThrow()))))
            .path("asset_id")
            .asText();
    await(user, "/assets/" + asset, "status", target, 30);
    return asset;
  }

  static JsonNode await(TestBrowser user, String path, String field, String target, int seconds)
      throws Exception {
    long deadline = System.nanoTime() + seconds * 1000000000L;
    JsonNode state = null;
    while (System.nanoTime() < deadline) {
      state = user.get(path);
      String actual = state.path(field).asText();
      if (actual.equals(target)) return state;
      if (actual.equals("FAILED") || actual.equals("INVALID")) fail(state.toString());
      Thread.sleep(200);
    }
    fail("Timed out: " + state);
    return state;
  }
}
