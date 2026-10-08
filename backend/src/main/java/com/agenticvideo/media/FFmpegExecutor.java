package com.agenticvideo.media;

import com.agenticvideo.timeline.TimelineCompiler.RenderSpecification;
import com.agenticvideo.timeline.TimelineCompiler.Segment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Uses task-owned paths, fixed filters and one AAC encode after PCM segment concatenation. */
public final class FFmpegExecutor {
  public record Input(Path file, MediaProbe.Metadata metadata) {}

  private final ProcessRunner runner = new ProcessRunner();

  public Path render(
      RenderSpecification spec,
      Map<UUID, Input> inputs,
      Path directory,
      BooleanSupplier cancelled,
      IntConsumer progress)
      throws java.io.IOException {
    Files.createDirectories(directory);
    var parts = new ArrayList<Path>();
    for (int i = 0; i < spec.segments().size(); i++) {
      Segment segment = spec.segments().get(i);
      // Sub-frame clips may occupy no samples on the cumulative 30 fps grid.
      if (segment.frames() == 0) continue;
      Input input = inputs.get(segment.assetId());
      Path part = directory.resolve("segment-" + i + ".mkv");
      String size = spec.width() + ":" + spec.height();
      String scale =
          segment.cropMode().equals("fit")
              ? "scale="
                  + size
                  + ":force_original_aspect_ratio=decrease,pad="
                  + size
                  + ":(ow-iw)/2:(oh-ih)/2:black"
              : "scale=" + size + ":force_original_aspect_ratio=increase,crop=" + size;
      String video =
          "[0:v:0]setpts=PTS-STARTPTS,fps=30,trim=start_frame="
              + (segment.inMs() * 30 / 1000)
              + ":end_frame="
              + Math.max(segment.inMs() * 30 / 1000 + 1, (segment.outMs() * 30 + 999) / 1000)
              + ",setpts=PTS-STARTPTS,"
              + scale
              + ",setsar=1,fps=30,tpad=stop_mode=clone:stop_duration=0.1,trim=end_frame="
              + segment.frames()
              + "[v]";
      String audio;
      if (input.metadata().audio()) {
        audio =
            "[0:a:0]asetpts=PTS-STARTPTS+("
                + decimal(input.metadata().audioStart() - input.metadata().videoStart())
                + ")/TB,aresample=48000:async=1:first_pts=0,apad=whole_dur="
                + seconds(input.metadata().durationMs())
                + ",atrim=start="
                + seconds(segment.inMs())
                + ":end="
                + seconds(segment.outMs())
                + ",asetpts=PTS-STARTPTS,apad=whole_dur="
                + decimal(segment.seconds())
                + ",atrim=duration="
                + decimal(segment.seconds())
                + ",aformat=sample_rates=48000:channel_layouts=stereo[a]";
      } else {
        audio = "anullsrc=r=48000:cl=stereo,atrim=duration=" + decimal(segment.seconds()) + "[a]";
      }
      var args = base();
      args.addAll(
          List.of(
              "-protocol_whitelist",
              "file,pipe",
              "-i",
              input.file().toString(),
              "-filter_complex",
              video + ";" + audio,
              "-map",
              "[v]",
              "-map",
              "[a]",
              "-threads",
              "2",
              "-c:v",
              "libx264",
              "-preset",
              "veryfast",
              "-crf",
              "18",
              "-maxrate",
              "20M",
              "-bufsize",
              "40M",
              "-pix_fmt",
              "yuv420p",
              "-c:a",
              "pcm_s16le",
              "-t",
              decimal(segment.seconds()),
              part.toString()));
      final int segmentIndex = i;
      var result =
          runner.run(
              args,
              Duration.ofMinutes(10),
              cancelled,
              line -> {
                if (line.startsWith("out_time_us=")) {
                  try {
                    double fraction =
                        Math.min(
                            1, Long.parseLong(line.substring(12)) / (segment.seconds() * 1000000));
                    progress.accept(
                        10 + (int) ((segmentIndex + fraction) * 65 / spec.segments().size()));
                  } catch (NumberFormatException ignored) {
                    /* FFmpeg may report N/A before first frame. */
                  }
                }
              });
      if (result.exitCode() != 0) throw new ProcessRunner.MediaFailure("FFMPEG_FAILED");
      parts.add(part);
      progress.accept(10 + (int) ((i + 1) * 65.0 / spec.segments().size()));
    }
    Path list = directory.resolve("concat.txt");
    Files.writeString(
        list,
        String.join("\n", parts.stream().map(p -> "file '" + p.getFileName() + "'").toList())
            + "\n");
    Path output = directory.resolve("output.mp4");
    var args = base();
    args.addAll(
        List.of(
            "-protocol_whitelist",
            "file,pipe",
            "-f",
            "concat",
            "-safe",
            "1",
            "-i",
            list.toString(),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0",
            "-threads",
            "2",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-maxrate",
            "20M",
            "-bufsize",
            "40M",
            "-pix_fmt",
            "yuv420p",
            "-r",
            "30",
            "-c:a",
            "aac",
            "-ar",
            "48000",
            "-ac",
            "2",
            "-t",
            decimal(spec.frames() / 30.0),
            "-movflags",
            "+faststart",
            output.toString()));
    var result =
        runner.run(
            args,
            Duration.ofMinutes(15),
            cancelled,
            line -> {
              if (line.startsWith("out_time_us=")) {
                try {
                  progress.accept(
                      75
                          + (int)
                              Math.min(
                                  9,
                                  Long.parseLong(line.substring(12))
                                      * 9.0
                                      / (spec.durationMs() * 1000)));
                } catch (NumberFormatException ignored) {
                  /* No first frame yet. */
                }
              }
            });
    if (result.exitCode() != 0) throw new ProcessRunner.MediaFailure("FFMPEG_FAILED");
    progress.accept(85);
    var probe = new MediaProbe();
    var metadata = probe.probe(output, cancelled);
    if (metadata.width() != spec.width()
        || metadata.height() != spec.height()
        || !metadata.audio()
        || Math.abs(metadata.durationMs() - spec.durationMs()) > 34)
      throw new ProcessRunner.MediaFailure("OUTPUT_INVALID");
    probe.decode(output, cancelled);
    progress.accept(90);
    return output;
  }

  private static ArrayList<String> base() {
    return new ArrayList<>(
        List.of(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-progress",
            "pipe:1",
            "-stats_period",
            "0.5",
            "-y",
            "-threads",
            "2",
            "-filter_threads",
            "1",
            "-filter_complex_threads",
            "1"));
  }

  private static String seconds(long ms) {
    return decimal(ms / 1000.0);
  }

  private static String decimal(double seconds) {
    return String.format(Locale.ROOT, "%.6f", seconds);
  }
}
