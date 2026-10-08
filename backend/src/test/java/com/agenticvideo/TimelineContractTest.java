package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticvideo.timeline.PlanValidator;
import com.agenticvideo.timeline.TimelineCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimelineContractTest {
  private final ObjectMapper json = new ObjectMapper();
  private final PlanValidator validator = new PlanValidator();

  @Test
  void sharedPositiveAndNegativeFixtures() throws Exception {
    var directory = Path.of("../contracts/fixtures");
    var valid = json.readTree(directory.resolve("valid.json").toFile());
    assertEquals(2000, validator.validateStructure(valid, 600000));
    try (var files = Files.list(directory)) {
      for (var file :
          files.filter(p -> p.getFileName().toString().startsWith("invalid-")).toList()) {
        var invalid = json.readTree(file.toFile());
        var error =
            assertThrows(
                PlanValidator.InvalidPlan.class,
                () -> validator.validateStructure(invalid, 600000),
                file.getFileName().toString());
        assertTrue(
            error.violations().stream().allMatch(v -> !v.code().isBlank() && !v.path().isBlank()));
      }
    }
  }

  @Test
  void enforcesAssetProjectVersionReadinessAndSourceDuration() throws Exception {
    var plan = json.readTree(Path.of("../contracts/fixtures/valid.json").toFile());
    UUID project = UUID.randomUUID(),
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var ready = new PlanValidator.Asset(id, project, 1, 3000, true);
    var readySecond = new PlanValidator.Asset(second, project, 1, 3000, true);
    assertEquals(
        2000, validator.validate(plan, project, Map.of(id, ready, second, readySecond), 600000));
    for (var invalid :
        java.util.List.of(
            new PlanValidator.Asset(id, UUID.randomUUID(), 1, 3000, true),
            new PlanValidator.Asset(id, project, 2, 3000, true),
            new PlanValidator.Asset(id, project, 1, 3000, false),
            new PlanValidator.Asset(id, project, 1, 1000, true))) {
      assertThrows(
          PlanValidator.InvalidPlan.class,
          () ->
              validator.validate(plan, project, Map.of(id, invalid, second, readySecond), 600000));
    }
    assertThrows(PlanValidator.InvalidPlan.class, () -> validator.validateStructure(plan, 1999));
  }

  @Test
  void cumulativeFrameRoundingDoesNotAccumulateError() throws Exception {
    var plan = json.readTree(Path.of("../contracts/fixtures/valid.json").toFile());
    var clips =
        (com.fasterxml.jackson.databind.node.ArrayNode) plan.path("tracks").get(0).path("clips");
    var template = clips.get(0).deepCopy();
    clips.removeAll();
    for (int i = 0; i < 31; i++) {
      var clip = (com.fasterxml.jackson.databind.node.ObjectNode) template.deepCopy();
      clip.put("clip_id", "clip_" + i)
          .put("source_in_ms", 500)
          .put("source_out_ms", 519)
          .put("timeline_start_ms", 19 * i);
      clips.add(clip);
    }
    ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("constraints"))
        .put("target_duration_ms", 589);
    var spec = new TimelineCompiler().compile(plan, TimelineCompiler.Profile.PREVIEW_720P);
    assertEquals(18, spec.frames());
    assertEquals(spec.frames(), spec.segments().stream().mapToLong(s -> s.frames()).sum());
    assertTrue(Math.abs(spec.frames() * 1000.0 / 30 - spec.durationMs()) <= 33.4);
  }

  @Test
  void previewDerivesProfileWithoutChangingImmutableDsl() throws Exception {
    var plan = json.readTree(Path.of("../contracts/fixtures/valid.json").toFile());
    String original = plan.toString();
    var compiler = new TimelineCompiler();
    var preview = compiler.compile(plan, TimelineCompiler.Profile.PREVIEW_720P);
    var export = compiler.compile(plan, TimelineCompiler.Profile.FINAL_1080P);
    assertEquals(1280, preview.width());
    assertEquals(1920, export.width());
    assertEquals(60, preview.frames());
    assertEquals(original, plan.toString());
    assertEquals(500, preview.segments().getFirst().inMs());
  }
}
