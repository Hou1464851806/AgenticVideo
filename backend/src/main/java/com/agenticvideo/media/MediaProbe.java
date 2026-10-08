package com.agenticvideo.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class MediaProbe {
  public record Metadata(
      long durationMs,
      int width,
      int height,
      String fps,
      int rotation,
      boolean audio,
      double videoStart,
      double audioStart,
      String format) {}

  private final ProcessRunner runner = new ProcessRunner();

  public Metadata probe(Path file, BooleanSupplier cancelled) {
    var result =
        runner.run(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-protocol_whitelist",
                "file,pipe",
                "-show_streams",
                "-show_format",
                "-of",
                "json",
                file.toString()),
            Duration.ofSeconds(60),
            cancelled);
    if (result.exitCode() != 0) throw new ProcessRunner.MediaFailure("MEDIA_INVALID");
    try {
      JsonNode data = new ObjectMapper().readTree(result.stdout());
      JsonNode video = null, audio = null;
      int videos = 0, audios = 0;
      for (var stream : data.path("streams")) {
        if (stream.path("codec_type").asText().equals("video")) {
          video = stream;
          videos++;
        }
        if (stream.path("codec_type").asText().equals("audio")) {
          audio = stream;
          audios++;
        }
      }
      String format = data.path("format").path("format_name").asText();
      if (videos != 1
          || audios > 1
          || video == null
          || !format.contains("mov")
          || !video.path("codec_name").asText().equals("h264")
          || (audio != null && !audio.path("codec_name").asText().equals("aac")))
        throw new ProcessRunner.MediaFailure("MEDIA_UNSUPPORTED");
      if (!List.of("yuv420p", "yuvj420p").contains(video.path("pix_fmt").asText())
          || List.of("smpte2084", "arib-std-b67").contains(video.path("color_transfer").asText()))
        throw new ProcessRunner.MediaFailure("MEDIA_UNSUPPORTED_HDR");
      long duration = Math.round(video.path("duration").asDouble(0) * 1000);
      if (duration <= 0 || video.path("width").asInt() <= 0 || video.path("height").asInt() <= 0)
        throw new ProcessRunner.MediaFailure("MEDIA_INVALID");
      int rotation = 0;
      for (var side : video.path("side_data_list"))
        if (side.has("rotation")) rotation = side.path("rotation").asInt();
      if (rotation % 90 != 0) throw new ProcessRunner.MediaFailure("MEDIA_UNSUPPORTED_ROTATION");
      return new Metadata(
          duration,
          video.path("width").asInt(),
          video.path("height").asInt(),
          video.path("avg_frame_rate").asText(),
          rotation,
          audio != null,
          video.path("start_time").asDouble(),
          audio == null ? 0 : audio.path("start_time").asDouble(),
          format);
    } catch (java.io.IOException e) {
      throw new ProcessRunner.MediaFailure("MEDIA_INVALID");
    }
  }

  public void decode(Path file, BooleanSupplier cancelled) {
    var result =
        runner.run(
            List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-xerror",
                "-threads",
                "2",
                "-protocol_whitelist",
                "file,pipe",
                "-i",
                file.toString(),
                "-map",
                "0:v:0",
                "-map",
                "0:a:0?",
                "-f",
                "null",
                "-"),
            Duration.ofMinutes(10),
            cancelled);
    if (result.exitCode() != 0) throw new ProcessRunner.MediaFailure("MEDIA_DECODE_FAILED");
  }
}
