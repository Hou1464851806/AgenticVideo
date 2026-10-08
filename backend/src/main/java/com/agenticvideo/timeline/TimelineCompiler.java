package com.agenticvideo.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure compilation: all external paths and process execution belong to the executor. */
public final class TimelineCompiler {
  public enum Profile {
    PREVIEW_720P,
    FINAL_1080P
  }

  public record Segment(
      UUID assetId, int assetVersion, long inMs, long outMs, String cropMode, long frames) {
    public double seconds() {
      return frames / 30.0;
    }
  }

  public record RenderSpecification(
      List<Segment> segments,
      int width,
      int height,
      long durationMs,
      long frames,
      Profile profile) {
    public RenderSpecification {
      segments = List.copyOf(segments);
    }
  }

  public RenderSpecification compile(JsonNode plan, Profile profile) {
    long total = new PlanValidator().validateStructure(plan, Long.MAX_VALUE);
    boolean portrait =
        plan.path("output").path("height").asInt() > plan.path("output").path("width").asInt();
    int width =
        profile == Profile.PREVIEW_720P ? (portrait ? 720 : 1280) : (portrait ? 1080 : 1920);
    int height =
        profile == Profile.PREVIEW_720P ? (portrait ? 1280 : 720) : (portrait ? 1920 : 1080);
    long frames = 0, elapsed = 0;
    var segments = new ArrayList<Segment>();
    for (var clip : plan.path("tracks").get(0).path("clips")) {
      long in = clip.path("source_in_ms").asLong(), out = clip.path("source_out_ms").asLong();
      elapsed += out - in;
      long boundary = Math.max(1, Math.round(elapsed * 30.0 / 1000));
      segments.add(
          new Segment(
              UUID.fromString(clip.path("asset_id").asText()),
              clip.path("asset_version").asInt(),
              in,
              out,
              clip.path("crop_mode").asText(),
              boundary - frames));
      frames = boundary;
    }
    return new RenderSpecification(segments, width, height, total, frames, profile);
  }
}
