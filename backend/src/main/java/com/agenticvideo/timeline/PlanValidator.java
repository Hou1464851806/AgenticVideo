package com.agenticvideo.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates the shared schema and timeline without performing storage or process operations. */
public final class PlanValidator {
  public record Asset(UUID id, UUID projectId, int version, long durationMs, boolean ready) {}

  public record Violation(String code, String path, String message) {}

  public static final class InvalidPlan extends RuntimeException {
    private final List<Violation> violations;

    public InvalidPlan(List<Violation> violations) {
      super("Editing plan validation failed");
      this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
      return violations;
    }
  }

  private final Schema schema;

  public PlanValidator() {
    schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(PlanValidator.class.getResourceAsStream("/contracts/m1-dsl.schema.json"));
  }

  public long validateStructure(JsonNode plan, long maxDurationMs) {
    var errors = schema.validate(plan);
    if (!errors.isEmpty())
      throw new InvalidPlan(
          errors.stream()
              .map(
                  e ->
                      new Violation(
                          "DSL_SCHEMA_INVALID",
                          e.getInstanceLocation().toString().isEmpty()
                              ? "/"
                              : e.getInstanceLocation().toString(),
                          e.getMessage()))
              .toList());
    long time = 0;
    var ids = new HashSet<String>();
    var clips = plan.path("tracks").get(0).path("clips");
    for (int i = 0; i < clips.size(); i++) {
      var clip = clips.get(i);
      String path = "/tracks/0/clips/" + i;
      if (!ids.add(clip.path("clip_id").asText())) fail("DUPLICATE_CLIP_ID", path + "/clip_id");
      long in = clip.path("source_in_ms").asLong();
      long out = clip.path("source_out_ms").asLong();
      if (out <= in) fail("SOURCE_RANGE_INVALID", path + "/source_out_ms");
      if (clip.path("timeline_start_ms").asLong() != time)
        fail("TIMELINE_NOT_CONTIGUOUS", path + "/timeline_start_ms");
      time = Math.addExact(time, out - in);
      if (time > maxDurationMs) fail("OUTPUT_DURATION_EXCEEDED", path + "/source_out_ms");
    }
    var target = plan.path("constraints").path("target_duration_ms");
    if (!target.isMissingNode() && target.asLong() != time)
      fail("TARGET_DURATION_MISMATCH", "/constraints/target_duration_ms");
    return time;
  }

  public long validate(JsonNode plan, UUID projectId, Map<UUID, Asset> assets, long maxDurationMs) {
    long time = validateStructure(plan, maxDurationMs);
    var clips = plan.path("tracks").get(0).path("clips");
    for (int i = 0; i < clips.size(); i++) {
      var clip = clips.get(i);
      String path = "/tracks/0/clips/" + i;
      var asset = assets.get(UUID.fromString(clip.path("asset_id").asText()));
      if (asset == null || !asset.projectId().equals(projectId))
        fail("ASSET_NOT_FOUND", path + "/asset_id");
      if (!asset.ready()) fail("ASSET_NOT_READY", path + "/asset_id");
      if (asset.version() != clip.path("asset_version").asInt())
        fail("ASSET_VERSION_CONFLICT", path + "/asset_version");
      if (clip.path("source_out_ms").asLong() > asset.durationMs())
        fail("SOURCE_RANGE_EXCEEDED", path + "/source_out_ms");
    }
    return time;
  }

  private static void fail(String code, String path) {
    throw new InvalidPlan(List.of(new Violation(code, path, code)));
  }
}
